package com.simplerag.application.dto;

/**
 * One progress report while the local embedding model downloads.
 *
 * <p>Progress is per file rather than per install: the total size of the whole model is only known
 * once every response header has been read, and reporting a percentage that jumps backwards when the
 * second file starts would be worse than reporting which file is in flight.
 *
 * @param file name of the file being written
 * @param fileIndex 1-based position of that file
 * @param fileCount how many files the model needs in total
 * @param bytes bytes written so far for this file
 * @param totalBytes size this file is expected to reach, or {@code -1} when the server did not say
 */
public record ModelDownloadProgress(String file, int fileIndex, int fileCount, long bytes,
                                    long totalBytes) {
    /** Completion of the current file in percent, or {@code -1} when the size is unknown. */
    public int percent() {
        if (totalBytes <= 0) return -1;
        return (int) Math.min(100, bytes * 100 / totalBytes);
    }
}
