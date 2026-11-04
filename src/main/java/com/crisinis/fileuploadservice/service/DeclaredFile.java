package com.crisinis.fileuploadservice.service;

import com.crisinis.fileuploadservice.model.ExtractionKind;

/**
 * A file as its uploader describes it, before anything is checked.
 *
 * @param fileName   as sent; cleaned before it's kept
 * @param mediaType  the declared Content-Type
 * @param size       bytes; -1 when a stream has no Content-Length
 * @param extraction what to extract from it once available; null when the uploader didn't opt in
 */
public record DeclaredFile(String fileName, String mediaType, long size, ExtractionKind extraction) {

    /** A file nobody asked to extract anything from. */
    public static DeclaredFile of(String fileName, String mediaType, long size) {
        return new DeclaredFile(fileName, mediaType, size, null);
    }
}
