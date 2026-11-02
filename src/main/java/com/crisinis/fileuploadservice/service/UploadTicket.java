package com.crisinis.fileuploadservice.service;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.storage.PresignedUpload;

/** A file waiting for its bytes, and where the caller sends them. */
public record UploadTicket(FileRecord file, PresignedUpload upload) {
}
