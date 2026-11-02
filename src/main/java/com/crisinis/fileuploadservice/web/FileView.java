package com.crisinis.fileuploadservice.web;

import java.time.Instant;
import java.util.Locale;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A file as callers see it. Steps not reached yet are left out. Threat names and GuardDuty's reasons
 * stay in the logs; a REJECTED file says why, since the caller can fix that.
 *
 * @param submittedForScanAt when the file landed in quarantine and GuardDuty picked it up
 * @param scanCompletedAt    when GuardDuty reported its result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileView(
        String id,
        String fileName,
        String contentType,
        long size,
        String uploadMethod,
        FileStatus status,
        Instant createdAt,
        Instant submittedForScanAt,
        Instant scanCompletedAt,
        Instant availableAt,
        String scanResult,
        String rejectedBecause,
        String contentUrl) {

    static FileView of(FileRecord file) {
        return new FileView(file.id(), file.fileName(), file.type().mediaType(), file.size(),
                file.method().name().toLowerCase(Locale.ROOT).replace('_', '-'), file.status(), file.createdAt(),
                file.submittedAt(), file.scannedAt(), file.availableAt(),
                file.scanResult() == null ? null : file.scanResult().name(),
                file.status() == FileStatus.REJECTED ? file.reason() : null,
                file.status() == FileStatus.AVAILABLE ? FileController.contentPath(file.id()) : null);
    }
}
