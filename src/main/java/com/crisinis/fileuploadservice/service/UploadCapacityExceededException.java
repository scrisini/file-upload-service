package com.crisinis.fileuploadservice.service;

/** This instance is streaming as many uploads as it's allowed to; the caller should retry shortly. */
public class UploadCapacityExceededException extends RuntimeException {

    public UploadCapacityExceededException(int limit) {
        super("this instance is already streaming " + limit + " uploads; retry shortly");
    }
}
