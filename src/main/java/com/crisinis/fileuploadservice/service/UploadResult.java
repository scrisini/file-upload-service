package com.crisinis.fileuploadservice.service;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.storage.PresignedUpload;

/**
 * An accepted upload.
 *
 * @param file     the file
 * @param upload   where the caller PUTs the bytes (presigned style, until they've landed); null otherwise
 * @param replayed whether this answers a retry with the same idempotency key, with the file the
 *                 first attempt created, instead of a new one
 */
public record UploadResult(FileRecord file, PresignedUpload upload, boolean replayed) {
}
