package com.crisinis.fileuploadservice.service;

/** No file with this id: never created, or created and never uploaded (its record expired). */
public class UnknownFileException extends RuntimeException {

    public UnknownFileException(String id) {
        super("no file " + id);
    }
}
