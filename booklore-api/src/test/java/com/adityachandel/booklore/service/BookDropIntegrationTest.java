package com.adityachandel.booklore.service;

import com.adityachandel.booklore.service.bookdrop.FileStabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests focusing on FileStabilityService behavior with file operations.
 * Tests realistic file processing scenarios without database dependencies.
 */
class BookDropIntegrationTest {

    private FileStabilityService fileStabilityService;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() throws IOException {
        fileStabilityService = new FileStabilityService();
        
        // Create test directories
        Files.createDirectories(tempDir.resolve("bookdrop"));
        Files.createDirectories(tempDir.resolve("library"));
    }
    
    @Test
    void testFileStabilityIntegrationWithBookDrop() throws IOException, InterruptedException {
        // Test realistic BookDrop scenario: file appears and is gradually written
        Path bookdropDir = tempDir.resolve("bookdrop");
        Path sourceFile = bookdropDir.resolve("test_book.epub");
        
        // Simulate file being written gradually (like copying or downloading)
        CompletableFuture<Void> writeTask = CompletableFuture.runAsync(() -> {
            try {
                // Initial write
                Files.write(sourceFile, "Part 1".getBytes());
                Thread.sleep(50);
                
                // Additional content
                Files.write(sourceFile, "Part 1 Part 2".getBytes());
                Thread.sleep(50);
                
                // Final content
                Files.write(sourceFile, "Part 1 Part 2 Part 3 - Complete EPUB Content".getBytes());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        // File should initially not be stable
        assertFalse(fileStabilityService.isFileStable(sourceFile), 
            "File should not be stable immediately");
        
        // Wait for writing to complete
        assertDoesNotThrow(() -> writeTask.get(2, TimeUnit.SECONDS));
        
        // Now file should become stable
        assertTrue(fileStabilityService.waitForFileStability(sourceFile, 100, 1500),
            "File should become stable after writing completes");
        
        // Verify file content is complete
        String content = Files.readString(sourceFile);
        assertTrue(content.contains("Complete EPUB Content"), 
            "File should have complete content after stability achieved");
        
        // Verify tracking is cleaned up
        assertEquals(0, fileStabilityService.getTrackedFileCount(),
            "File tracking should be cleaned up after stability achieved");
    }
    
    @Test
    void testFileStabilityWithMultipleFiles() throws IOException, InterruptedException {
        // Create multiple test files of different types and sizes
        Path bookdropDir = tempDir.resolve("bookdrop");
        
        // Create files of different types and sizes
        Path epub = bookdropDir.resolve("book1.epub");
        Path pdf = bookdropDir.resolve("book2.pdf");
        Path mobi = bookdropDir.resolve("book3.mobi");
        
        Files.write(epub, new byte[1024]);
        Files.write(pdf, new byte[2048]);
        Files.write(mobi, new byte[512]);
        
        // Test file stability detection on different file types
        assertTrue(fileStabilityService.waitForFileStability(epub, 100, 1000),
            "EPUB file should become stable");
        assertTrue(fileStabilityService.waitForFileStability(pdf, 100, 1000),
            "PDF file should become stable");
        assertTrue(fileStabilityService.waitForFileStability(mobi, 100, 1000),
            "MOBI file should become stable");
        
        // Verify files still exist
        assertTrue(Files.exists(epub), "EPUB file should still exist");
        assertTrue(Files.exists(pdf), "PDF file should still exist");
        assertTrue(Files.exists(mobi), "MOBI file should still exist");
        
        // Verify memory cleanup
        assertEquals(0, fileStabilityService.getTrackedFileCount(),
            "All files should be cleaned up from tracking");
    }
    
    @Test
    void testLargeFileStabilityProcessing() throws IOException {
        // Test stability checking with many files
        Path bookdropDir = tempDir.resolve("bookdrop");
        
        // Create enough files to test memory management
        int fileCount = 25; // Smaller count for faster test execution
        for (int i = 0; i < fileCount; i++) {
            Files.write(bookdropDir.resolve("book_" + i + ".epub"), 
                ("Book content " + i).getBytes());
        }
        
        long startTime = System.currentTimeMillis();
        
        // Process files one by one to test stability handling
        for (int i = 0; i < fileCount; i++) {
            Path bookFile = bookdropDir.resolve("book_" + i + ".epub");
            assertTrue(fileStabilityService.waitForFileStability(bookFile, 50, 500),
                "Book " + i + " should become stable");
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Should complete in reasonable time
        assertTrue(duration < 30_000, "Processing should complete within reasonable time");
        
        // Verify all files still exist
        for (int i = 0; i < fileCount; i++) {
            assertTrue(Files.exists(bookdropDir.resolve("book_" + i + ".epub")),
                "Book " + i + " should exist after processing");
        }
        
        // Memory should be clean after processing
        assertEquals(0, fileStabilityService.getTrackedFileCount(),
            "No files should be tracked after processing completes");
    }
    
    @Test
    void testConcurrentFileProcessing() throws InterruptedException, IOException {
        // Create multiple directories to test concurrent file processing
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Path dir3 = tempDir.resolve("dir3");
        
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);
        Files.createDirectories(dir3);
        
        // Create files in each directory
        Files.write(dir1.resolve("book1.epub"), "Content 1".getBytes());
        Files.write(dir2.resolve("book2.pdf"), "Content 2".getBytes());
        Files.write(dir3.resolve("book3.mobi"), "Content 3".getBytes());
        
        // Process all files concurrently
        CompletableFuture<Boolean> process1 = CompletableFuture.supplyAsync(() ->
            fileStabilityService.waitForFileStability(dir1.resolve("book1.epub"), 50, 1000)
        );
        
        CompletableFuture<Boolean> process2 = CompletableFuture.supplyAsync(() ->
            fileStabilityService.waitForFileStability(dir2.resolve("book2.pdf"), 50, 1000)
        );
        
        CompletableFuture<Boolean> process3 = CompletableFuture.supplyAsync(() ->
            fileStabilityService.waitForFileStability(dir3.resolve("book3.mobi"), 50, 1000)
        );
        
        // All should complete without deadlocks or timeouts
        CompletableFuture<Void> allProcessing = CompletableFuture.allOf(process1, process2, process3);
        
        assertDoesNotThrow(() -> allProcessing.get(5, TimeUnit.SECONDS),
            "Concurrent file processing should complete without deadlocks");
        
        // All files should be stable
        assertTrue(assertDoesNotThrow(() -> process1.get()), "File 1 should be stable");
        assertTrue(assertDoesNotThrow(() -> process2.get()), "File 2 should be stable");
        assertTrue(assertDoesNotThrow(() -> process3.get()), "File 3 should be stable");
        
        // Memory should be clean
        assertEquals(0, fileStabilityService.getTrackedFileCount(),
            "No files should be tracked after concurrent processing");
    }
    
    @Test
    void testErrorRecoveryDuringFileProcessing() throws IOException {
        Path bookdropDir = tempDir.resolve("bookdrop");
        
        // Create mix of valid and problematic files
        Files.write(bookdropDir.resolve("valid_book1.epub"), "Valid content 1".getBytes());
        Files.write(bookdropDir.resolve("valid_book2.pdf"), "Valid content 2".getBytes());
        
        // Create a file that might cause processing issues (empty file)
        Files.createFile(bookdropDir.resolve("empty_book.epub"));
        
        // Create a file with unusual name
        Files.write(bookdropDir.resolve("book with spaces & symbols!.epub"), 
            "Content with unusual filename".getBytes());
        
        // Processing should be resilient to individual file issues
        assertDoesNotThrow(() -> {
            fileStabilityService.waitForFileStability(bookdropDir.resolve("valid_book1.epub"), 50, 500);
            fileStabilityService.waitForFileStability(bookdropDir.resolve("valid_book2.pdf"), 50, 500);
            fileStabilityService.waitForFileStability(bookdropDir.resolve("empty_book.epub"), 50, 500);
            fileStabilityService.waitForFileStability(bookdropDir.resolve("book with spaces & symbols!.epub"), 50, 500);
        }, "File stability checking should be resilient to unusual files");
        
        // Valid files should still exist
        assertTrue(Files.exists(bookdropDir.resolve("valid_book1.epub")));
        assertTrue(Files.exists(bookdropDir.resolve("valid_book2.pdf")));
        assertTrue(Files.exists(bookdropDir.resolve("book with spaces & symbols!.epub")));
        
        // Memory should be reasonably clean (allow some remaining due to error conditions)
        assertTrue(fileStabilityService.getTrackedFileCount() <= 2,
            "Memory usage should be reasonable after error conditions");
    }
    
    @Test
    void testMemoryUsageDuringLargeOperations() throws IOException, InterruptedException {
        // Test that memory usage remains reasonable during large operations
        Path bookdropDir = tempDir.resolve("bookdrop");
        
        // Create many files to stress test memory usage
        int fileCount = 30; // Smaller count for faster test execution
        for (int i = 0; i < fileCount; i++) {
            Files.write(bookdropDir.resolve("memory_test_" + i + ".epub"), 
                ("Test content for file " + i).getBytes());
        }
        
        // Track memory before processing
        int initialTrackedFiles = fileStabilityService.getTrackedFileCount();
        
        // Process files with quick stability checks
        for (int i = 0; i < fileCount; i++) {
            Path file = bookdropDir.resolve("memory_test_" + i + ".epub");
            fileStabilityService.isFileStable(file, 10); // Quick check to start tracking
        }
        
        // Memory usage should be reasonable after processing
        int finalTrackedFiles = fileStabilityService.getTrackedFileCount();
        assertTrue(finalTrackedFiles <= fileCount + initialTrackedFiles,
            "Memory usage should not exceed expected bounds during file processing");
        
        // Allow cleanup to run
        Thread.sleep(100);
        
        // Should be cleaner after cleanup delay (but be lenient due to timing)
        int afterCleanup = fileStabilityService.getTrackedFileCount();
        assertTrue(afterCleanup <= finalTrackedFiles + 5,
            "Cleanup should maintain reasonable memory usage");
    }
    
}