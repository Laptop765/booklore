package com.adityachandel.booklore.service.bookdrop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class FileStabilityServiceTest {

    private FileStabilityService fileStabilityService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileStabilityService = new FileStabilityService();
    }

    @Test
    void testIsFileStable_NonExistentFile_ReturnsFalse() throws IOException {
        Path nonExistentFile = tempDir.resolve("nonexistent.txt");
        
        assertFalse(fileStabilityService.isFileStable(nonExistentFile));
        assertEquals(0, fileStabilityService.getTrackedFileCount());
    }

    @Test
    void testIsFileStable_FirstCheck_ReturnsFalse() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "test content".getBytes());
        
        boolean result = fileStabilityService.isFileStable(testFile);
        
        assertFalse(result);
        assertEquals(1, fileStabilityService.getTrackedFileCount());
    }

    @Test
    void testIsFileStable_FileSizeChanged_ResetTracking() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "initial content".getBytes());
        
        // First check - starts tracking
        assertFalse(fileStabilityService.isFileStable(testFile));
        assertEquals(1, fileStabilityService.getTrackedFileCount());
        
        // Modify file
        Files.write(testFile, "modified content with different size".getBytes());
        
        // Second check - should reset tracking due to size change
        assertFalse(fileStabilityService.isFileStable(testFile));
        assertEquals(1, fileStabilityService.getTrackedFileCount());
    }

    @Test
    void testIsFileStable_StableAfterDelay_ReturnsTrue() throws IOException, InterruptedException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "stable content".getBytes());
        
        // First check - starts tracking
        assertFalse(fileStabilityService.isFileStable(testFile, 100)); // 100ms stability delay
        
        // Wait for stability period
        Thread.sleep(150);
        
        // Second check - should be stable
        assertTrue(fileStabilityService.isFileStable(testFile, 100));
        assertEquals(0, fileStabilityService.getTrackedFileCount()); // Should be cleaned up
    }

    @Test
    void testIsFileStable_MaxStabilityChecks_AssumesStable() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "content".getBytes());
        
        // Simulate exceeding max checks by manipulating internal state
        ConcurrentMap<String, Object> fileTracker = new ConcurrentHashMap<>();
        String pathKey = testFile.toAbsolutePath().toString();
        
        // Create metadata with high check count using reflection
        try {
            Class<?> fileMetadataClass = Class.forName("com.adityachandel.booklore.service.bookdrop.FileStabilityService$FileMetadata");
            Object fileMetadata = fileMetadataClass.getDeclaredConstructor(long.class, Instant.class, int.class)
                    .newInstance(Files.size(testFile), Instant.now(), 11); // Exceed MAX_STABILITY_CHECKS (10)
            
            fileTracker.put(pathKey, fileMetadata);
            ReflectionTestUtils.setField(fileStabilityService, "fileTracker", fileTracker);
            
            // Should assume stable due to max checks exceeded
            assertTrue(fileStabilityService.isFileStable(testFile));
            assertEquals(0, fileStabilityService.getTrackedFileCount());
            
        } catch (Exception e) {
            fail("Failed to test max stability checks: " + e.getMessage());
        }
    }

    @Test
    void testWaitForFileStability_FileBecomesStable_ReturnsTrue() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "content".getBytes());
        
        // Should become stable within the wait time
        boolean result = fileStabilityService.waitForFileStability(testFile, 100, 1000);
        
        assertTrue(result);
    }


    @Test
    void testWaitForFileStability_InterruptedException_ReturnsFalse() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "content".getBytes());
        
        Thread testThread = new Thread(() -> {
            boolean result = fileStabilityService.waitForFileStability(testFile, 1000, 5000);
            assertFalse(result);
        });
        
        testThread.start();
        // Interrupt the thread while it's waiting
        testThread.interrupt();
        
        try {
            testThread.join(1000);
        } catch (InterruptedException e) {
            fail("Test thread join interrupted");
        }
    }

    @Test
    void testRemoveFromTracker() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "content".getBytes());
        
        // Start tracking
        fileStabilityService.isFileStable(testFile);
        assertEquals(1, fileStabilityService.getTrackedFileCount());
        
        // Remove from tracker
        String pathKey = testFile.toAbsolutePath().toString();
        fileStabilityService.removeFromTracker(pathKey);
        assertEquals(0, fileStabilityService.getTrackedFileCount());
    }

    @Test
    void testCleanupOldEntries() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.write(testFile, "content".getBytes());
        
        // Start tracking
        fileStabilityService.isFileStable(testFile);
        assertEquals(1, fileStabilityService.getTrackedFileCount());
        
        // Manually set old timestamp using reflection
        try {
            ConcurrentMap<String, Object> fileTracker = (ConcurrentMap<String, Object>) 
                    ReflectionTestUtils.getField(fileStabilityService, "fileTracker");
            String pathKey = testFile.toAbsolutePath().toString();
            
            Class<?> fileMetadataClass = Class.forName("com.adityachandel.booklore.service.bookdrop.FileStabilityService$FileMetadata");
            Object oldFileMetadata = fileMetadataClass.getDeclaredConstructor(long.class, Instant.class, int.class)
                    .newInstance(Files.size(testFile), Instant.now().minusSeconds(7200), 1); // 2 hours ago
            
            fileTracker.put(pathKey, oldFileMetadata);
            
            // Run cleanup
            fileStabilityService.cleanupOldEntries();
            
            // Should be cleaned up
            assertEquals(0, fileStabilityService.getTrackedFileCount());
            
        } catch (Exception e) {
            fail("Failed to test cleanup: " + e.getMessage());
        }
    }

    @Test
    void testIsFileStable_IOError_ReturnsFalse() {
        // Test with a path that will cause IO error (e.g., permission denied)
        Path invalidPath = Path.of("/root/nonexistent/file.txt");
        
        assertFalse(fileStabilityService.isFileStable(invalidPath));
        assertEquals(0, fileStabilityService.getTrackedFileCount());
    }

    @Test
    void testConcurrentAccess() throws IOException, InterruptedException {
        Path testFile1 = tempDir.resolve("test1.txt");
        Path testFile2 = tempDir.resolve("test2.txt");
        Files.write(testFile1, "content1".getBytes());
        Files.write(testFile2, "content2".getBytes());
        
        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                fileStabilityService.isFileStable(testFile1);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                fileStabilityService.isFileStable(testFile2);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        thread1.start();
        thread2.start();
        
        thread1.join();
        thread2.join();
        
        // Should handle concurrent access without issues
        assertTrue(fileStabilityService.getTrackedFileCount() <= 2);
    }
}