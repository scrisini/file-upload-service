package com.crisinis.fileuploadservice.service;

import com.crisinis.fileuploadservice.model.FileStatus;

/** The file exists but can't be downloaded: it isn't AVAILABLE (not yet scanned, or refused). */
public class FileNotAvailableException extends RuntimeException {

    public FileNotAvailableException(String id, FileStatus status) {
        super("file " + id + " is " + status + ", not AVAILABLE");
    }
}
