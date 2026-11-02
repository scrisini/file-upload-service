package com.crisinis.fileuploadservice.web;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.crisinis.fileuploadservice.model.IdempotencyKey;
import com.crisinis.fileuploadservice.model.ScanSummary;
import com.crisinis.fileuploadservice.service.ScanMonitor;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.service.UploadService;
import com.crisinis.fileuploadservice.service.UploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The upload API: both upload styles, file status, download, and scans in progress. README "REST API". */
@Tag(name = "files", description = "Upload files, follow their scans, download them")
@RestController
@RequestMapping("/api/v1")
public class FileController {

    /**
     * Asks for a presigned upload.
     *
     * @param contentType the file's media type, e.g. {@code image/png}
     * @param size        the file's exact length in bytes
     */
    public record UploadRequest(String fileName, String contentType, Long size) {
    }

    /**
     * Where and how to PUT the file.
     *
     * @param headers send exactly these with the PUT; they're signed into the URL
     */
    public record UploadInstructions(String method, URI url, Map<String, String> headers, java.time.Instant expiresAt) {
    }

    /** @param upload where to PUT the file; absent when a retry finds it has already landed */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadResponse(FileView file, UploadInstructions upload) {
    }

    /** Marks an answer to a retry: the file the first attempt with the same key created. */
    static final String REPLAYED = "Idempotent-Replayed";
    private static final String KEY_DESCRIPTION = "Optional. A unique value per file (a UUID). Send the same key "
            + "when retrying an upload whose answer was lost: the retry gets the same file instead of a new one. "
            + "A key used for another file is refused (422).";

    private final UploadService uploads;
    private final ScanMonitor scans;

    public FileController(UploadService uploads, ScanMonitor scans) {
        this.uploads = uploads;
        this.scans = scans;
    }

    /** Style 1, presigned URL: returns a URL the caller PUTs the file to, straight to S3. */
    @Operation(summary = "Presigned style: get a URL to PUT the file to S3",
            description = "PUT the file to `upload.url` before `upload.expiresAt`, with exactly `upload.headers`. "
                    + "The URL is signed for the content type, the exact size and one write: S3 answers 403 to "
                    + "anything else, and 412 to a second PUT. The file's content is checked after its scan.")
    @ApiResponse(responseCode = "201", description = "The file is AWAITING_UPLOAD; PUT it to the URL. A retry with "
            + "the same Idempotency-Key gets the same file (Idempotent-Replayed: true), without `upload` once it landed")
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another file", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Larger than uploads.max-size", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "Content type not accepted", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/uploads")
    ResponseEntity<UploadResponse> requestUpload(
            @RequestBody UploadRequest request,
            @Parameter(in = ParameterIn.HEADER, description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey) {
        if (request.size() == null) {
            throw new IllegalArgumentException("size is required: the file's length in bytes");
        }
        UploadResult result = uploads.requestUpload(request.fileName(), request.contentType(), request.size(),
                key(idempotencyKey));
        var upload = result.upload();
        return ResponseEntity.created(URI.create(filePath(result.file().id())))
                .header(REPLAYED, Boolean.toString(result.replayed()))
                .body(new UploadResponse(FileView.of(result.file()), upload == null ? null
                        : new UploadInstructions("PUT", upload.url(), upload.headers(), upload.expiresAt())));
    }

    /**
     * Style 2, stream: the request body is the file, with its media type as Content-Type and its
     * length as Content-Length. Not multipart: multipart parsing would need disk.
     */
    @Operation(summary = "Stream style: send the file as the request body",
            description = "The body is the raw file (not multipart). `Content-Type` is its media type and "
                    + "`Content-Length` its size. The type, size and first bytes are checked before anything is "
                    + "stored; the bytes are then streamed to S3 as they arrive.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = {
                    @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/gif", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/heic", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/tiff", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))}))
    @ApiResponse(responseCode = "202", description = "Stored in quarantine; the file is SCANNING. A retry with the "
            + "same Idempotency-Key gets the same file (Idempotent-Replayed: true) and isn't stored twice")
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another file",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "The body ended before Content-Length (reason INCOMPLETE)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "411", description = "No Content-Length (chunked)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "Larger than uploads.max-size",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "Type not accepted, or the content isn't the declared type",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "This instance is streaming its maximum: retry after Retry-After",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/files")
    ResponseEntity<FileView> stream(
            @Parameter(description = "The file's name, kept for downloads; any path is dropped")
            @RequestParam(required = false) String fileName,
            @Parameter(in = ParameterIn.HEADER, description = KEY_DESCRIPTION)
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(hidden = true) HttpServletRequest request)
            throws IOException {
        UploadResult result = uploads.stream(fileName, request.getContentType(), request.getContentLengthLong(),
                request.getInputStream(), key(idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create(filePath(result.file().id())))
                .header(REPLAYED, Boolean.toString(result.replayed()))
                .body(FileView.of(result.file()));
    }

    private static Optional<IdempotencyKey> key(String header) {
        return header == null ? Optional.empty() : Optional.of(new IdempotencyKey(header));
    }

    /** The file's status and when each step happened. */
    @Operation(summary = "A file's status, and when each step happened",
            description = "`submittedForScanAt` is when the file landed in quarantine, which is when GuardDuty "
                    + "picked it up; `scanCompletedAt` when GuardDuty reported. Final statuses: AVAILABLE, INFECTED, "
                    + "REJECTED, NOT_SCANNED.")
    @ApiResponse(responseCode = "200", description = "The file")
    @ApiResponse(responseCode = "404", description = "No such file",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/files/{id}")
    FileView file(@PathVariable String id) {
        return FileView.of(uploads.find(checked(id)));
    }

    /** Redirects to a short-lived download link, once the file is AVAILABLE. */
    @Operation(summary = "Download: redirects to a 5-minute S3 link")
    @ApiResponse(responseCode = "303", description = "Follow Location to download the file")
    @ApiResponse(responseCode = "409", description = "The file isn't AVAILABLE",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/files/{id}/content")
    ResponseEntity<Void> content(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(uploads.downloadUrl(checked(id))).build();
    }

    /** How many files GuardDuty is scanning now, across all instances. */
    @Operation(summary = "Files GuardDuty is scanning now, across all instances",
            description = "GuardDuty reports no count of its own: these are files that landed in quarantine and "
                    + "have no result yet.")
    @GetMapping("/scans")
    ScanSummary scans() {
        return scans.summary();
    }

    static String filePath(String id) {
        return "/api/v1/files/" + id;
    }

    static String contentPath(String id) {
        return filePath(id) + "/content";
    }

    /** Only ids this service could have issued reach the table. */
    private static String checked(String id) {
        try {
            if (UUID.fromString(id).toString().equals(id)) {
                return id;
            }
        } catch (IllegalArgumentException notAnId) {
            // falls through
        }
        throw new UnknownFileException(id);
    }
}
