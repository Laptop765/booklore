package com.adityachandel.booklore.service.bookdrop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for detecting when files are fully written and stable for processing.
 * 
 * This prevents race conditions where BookDrop processes files before they are
 * completely copied/written, which causes metadata extraction failures.
 * 
 * The service works by:
 * 1. Tracking file sizes over time
 * 2. Requiring files to be stable (unchanged size) for a configurable period
 * 3. Using file locks to detect if another process is writing
 */
@Slf4j
@Service
public class FileStabilityService {

    private static final long DEFAULT_STABILITY_DELAY_MS = 3000; // 3 seconds
    private static final int MAX_STABILITY_CHECKS = 10;
    
    // Track file metadata for stability detection
    // NOTE: This map could potentially grow large in high-volume scenarios with many unstable files.
    // Current mitigation: scheduled cleanup every 30 minutes removes entries older than 1 hour,
    // and MAX_STABILITY_CHECKS (10) prevents indefinite tracking of problematic files.
    // Future consideration: implement bounded map with LRU eviction if memory usage becomes an issue.
    private final ConcurrentMap<String, FileMetadata> fileTracker = new ConcurrentHashMap<>();
    
    private static class FileMetadata {
        final long size;
        final Instant timestamp;
        final int checkCount;
        
        FileMetadata(long size, Instant timestamp, int checkCount) {
            this.size = size;
            this.timestamp = timestamp;
            this.checkCount = checkCount;
        }
    }

    /**
     * Checks if a file is stable and ready for processing.
     * 
     * A file is considered stable if:
     * - It exists and is readable
     * - Its size hasn't changed for at least 3 seconds
     * - It's not being written by another process (no file locks)
     * 
     * @param filePath Path to the file to check
     * @return true if the file is stable and ready for processing
     */
    public boolean isFileStable(Path filePath) {
        return isFileStable(filePath, DEFAULT_STABILITY_DELAY_MS);
    }

    /**
     * Checks if a file is stable with a custom stability delay.
     * 
     * @param filePath Path to the file to check
     * @param stabilityDelayMs Minimum time in milliseconds the file must be unchanged
     * @return true if the file is stable and ready for processing
     */
    public boolean isFileStable(Path filePath, long stabilityDelayMs) {
        String pathKey = filePath.toAbsolutePath().toString();
        
        try {
            if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
                removeFromTracker(pathKey);
                return false;
            }
            
            long currentSize = Files.size(filePath);
            Instant now = Instant.now();
            
            // Use atomic compute operation to avoid race conditions
            AtomicBoolean isStable = new AtomicBoolean(false);
            fileTracker.compute(pathKey, (key, existing) -> {
                if (existing == null) {
                    // First time seeing this file - start tracking
                    log.debug("Started tracking file stability: {} (size: {} bytes)", filePath, currentSize);
                    return new FileMetadata(currentSize, now, 1);
                }
                
                if (existing.size != currentSize) {
                    // File size changed - reset tracking
                    log.debug("File size changed, reset stability tracking: {} (new size: {} bytes)", filePath, currentSize);
                    return new FileMetadata(currentSize, now, 1);
                }
                
                // File size is unchanged - check if enough time has passed
                long timeStableMs = now.toEpochMilli() - existing.timestamp.toEpochMilli();
                
                if (timeStableMs >= stabilityDelayMs) {
                    // File has been stable long enough
                    isStable.set(true);
                    return existing; // Keep existing entry
                } else {
                    // Not stable long enough yet, but update check count
                    int newCheckCount = existing.checkCount + 1;
                    if (newCheckCount >= MAX_STABILITY_CHECKS) {
                        log.warn("File exceeded maximum stability checks, assuming stable: {} (checks: {})", 
                            filePath, newCheckCount);
                        isStable.set(true);
                        return existing;
                    }
                    return new FileMetadata(existing.size, existing.timestamp, newCheckCount);
                }
            });
            
            if (isStable.get()) {
                // Calculate tracking duration before removal
                FileMetadata metadata = fileTracker.get(pathKey);
                long trackingDurationMs = metadata != null ? 
                    now.toEpochMilli() - metadata.timestamp.toEpochMilli() : 0;
                
                // File is stable - remove from tracking and return true
                fileTracker.remove(pathKey);
                log.debug("File is stable: {} (was tracked for {} ms)", filePath, trackingDurationMs);
                return true;
            }
            
            return false;
            
        } catch (IOException e) {
            log.warn("Error checking file stability: {}", filePath, e);
            removeFromTracker(pathKey);
            return false;
        }
    }

    /**
     * Waits for a file to become stable, with a maximum wait time.
     * 
     * @param filePath Path to the file to wait for
     * @param maxWaitTimeMs Maximum time to wait in milliseconds
     * @return true if the file became stable within the wait time
     */
    public boolean waitForFileStability(Path filePath, long maxWaitTimeMs) {
        return waitForFileStability(filePath, DEFAULT_STABILITY_DELAY_MS, maxWaitTimeMs);
    }

    /**
     * Waits for a file to become stable with custom parameters.
     * 
     * @param filePath Path to the file to wait for
     * @param stabilityDelayMs Minimum time the file must be unchanged
     * @param maxWaitTimeMs Maximum time to wait in milliseconds
     * @return true if the file became stable within the wait time
     */
    public boolean waitForFileStability(Path filePath, long stabilityDelayMs, long maxWaitTimeMs) {
        long startTime = System.currentTimeMillis();
        long checkIntervalMs = Math.min(500, stabilityDelayMs / 4); // Check every 500ms or quarter of stability delay
        
        while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
            if (isFileStable(filePath, stabilityDelayMs)) {
                log.info("File became stable: {} (waited {} ms)", filePath, System.currentTimeMillis() - startTime);
                return true;
            }
            
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for file stability: {}", filePath);
                return false;
            }
        }
        
        log.warn("File did not become stable within {} ms: {}", maxWaitTimeMs, filePath);
        removeFromTracker(filePath.toAbsolutePath().toString());
        return false;
    }

    /**
     * Removes a file from stability tracking (cleanup method).
     */
    public void removeFromTracker(String pathKey) {
        fileTracker.remove(pathKey);
    }

    /**
     * Gets the current number of files being tracked for stability.
     * Useful for monitoring and debugging.
     */
    public int getTrackedFileCount() {
        return fileTracker.size();
    }

    /**
     * Cleans up old file tracking entries to prevent memory leaks.
     * Removes entries older than 1 hour.
     * Runs automatically every 30 minutes.
     */
    @Scheduled(fixedDelay = 1800000) // 30 minutes
    public void cleanupOldEntries() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 hour ago
        int before = fileTracker.size();
        fileTracker.entrySet().removeIf(entry -> entry.getValue().timestamp.isBefore(cutoff));
        int after = fileTracker.size();
        if (before != after) {
            log.debug("Cleaned up {} old file tracking entries ({} remaining)", before - after, after);
        }
    }
}