package com.crisinis.fileuploadservice.web;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.ScanSummary;
import com.crisinis.fileuploadservice.service.ScanMonitor;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.service.UploadService;
import com.crisinis.fileuploadservice.service.UploadTicket;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The upload API: both upload styles, file status, download, and scans in progress. README "REST API". */
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

    public record UploadResponse(FileView file, UploadInstructions upload) {
    }

    private final UploadService uploads;
    private final ScanMonitor scans;

    public FileController(UploadService uploads, ScanMonitor scans) {
        this.uploads = uploads;
        this.scans = scans;
    }

    /** Style 1, presigned URL: returns a URL the caller PUTs the file to, straight to S3. */
    @PostMapping("/uploads")
    ResponseEntity<UploadResponse> requestUpload(@RequestBody UploadRequest request) {
        if (request.size() == null) {
            throw new IllegalArgumentException("size is required: the file's length in bytes");
        }
        UploadTicket ticket = uploads.requestUpload(request.fileName(), request.contentType(), request.size());
        var upload = ticket.upload();
        return ResponseEntity.created(URI.create(filePath(ticket.file().id())))
                .body(new UploadResponse(FileView.of(ticket.file()),
                        new UploadInstructions("PUT", upload.url(), upload.headers(), upload.expiresAt())));
    }

    /**
     * Style 2, stream: the request body is the file, with its media type as Content-Type and its
     * length as Content-Length. Not multipart: multipart parsing would need disk.
     */
    @PostMapping("/files")
    ResponseEntity<FileView> stream(@RequestParam(required = false) String fileName, HttpServletRequest request)
            throws IOException {
        FileRecord file = uploads.stream(fileName, request.getContentType(), request.getContentLengthLong(),
                request.getInputStream());
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create(filePath(file.id())))
                .body(FileView.of(file));
    }

    /** The file's status and when each step happened. */
    @GetMapping("/files/{id}")
    FileView file(@PathVariable String id) {
        return FileView.of(uploads.find(checked(id)));
    }

    /** Redirects to a short-lived download link, once the file is AVAILABLE. */
    @GetMapping("/files/{id}/content")
    ResponseEntity<Void> content(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(uploads.downloadUrl(checked(id))).build();
    }

    /** How many files GuardDuty is scanning now, across all instances. */
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
