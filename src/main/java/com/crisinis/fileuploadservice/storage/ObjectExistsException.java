package com.crisinis.fileuploadservice.storage;

/** Quarantine already holds an object for this id; uploads never replace one. */
public class ObjectExistsException extends RuntimeException {

    public ObjectExistsException(String id) {
        super("an upload for file " + id + " already exists");
    }
}
