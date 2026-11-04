package com.crisinis.fileuploadservice.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.crisinis.fileuploadservice.model.FileRecord;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.FileType;
import com.crisinis.fileuploadservice.model.ScanResult;
import com.crisinis.fileuploadservice.model.ScanSummary;
import com.crisinis.fileuploadservice.model.UploadMethod;
import com.crisinis.fileuploadservice.service.DeclaredFile;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import com.crisinis.fileuploadservice.service.ScanMonitor;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.service.UploadCapacityExceededException;
import com.crisinis.fileuploadservice.service.UploadRejectedException;
import com.crisinis.fileuploadservice.service.UploadService;
import com.crisinis.fileuploadservice.service.UploadResult;
import com.crisinis.fileuploadservice.storage.PresignedUpload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileController.class)
@Import({ApiExceptionHandler.class, RequestIdFilter.class})
class FileControllerTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
    static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UploadService uploads;

    @MockitoBean
    ScanMonitor scans;

    static FileRecord file(FileStatus status, ScanResult result, String reason) {
        return new FileRecord(ID, "beach.png", FileType.PNG, 1234, UploadMethod.PRESIGNED_URL, status, T0,
                status == FileStatus.AWAITING_UPLOAD ? null : T0.plusSeconds(2),
                result == null ? null : T0.plusSeconds(20),
                status == FileStatus.AVAILABLE ? T0.plusSeconds(21) : null, result, reason, null, null);
    }

    @Test
    void presignedStyleReturnsWhereToPutTheFile() throws Exception {
        when(uploads.requestUpload(DeclaredFile.of("beach.png", "image/png", 1234), java.util.Optional.empty())).thenReturn(new UploadResult(
                file(FileStatus.AWAITING_UPLOAD, null, null),
                new PresignedUpload(URI.create("https://file-upload-quarantine.s3.amazonaws.com/uploads/" + ID + "?X-Amz-Signature=x"),
                        Map.of("Content-Type", "image/png", "If-None-Match", "*"), T0.plus(Duration.ofMinutes(15))), false));

        mvc.perform(post("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"beach.png\",\"contentType\":\"image/png\",\"size\":1234}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/files/" + ID))
                .andExpect(jsonPath("$.file.id").value(ID))
                .andExpect(jsonPath("$.file.status").value("AWAITING_UPLOAD"))
                .andExpect(jsonPath("$.file.uploadMethod").value("presigned-url"))
                .andExpect(jsonPath("$.file.submittedForScanAt").doesNotExist())
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.url").value(org.hamcrest.Matchers.startsWith("https://file-upload-quarantine.")))
                .andExpect(jsonPath("$.upload.headers.If-None-Match").value("*"))
                .andExpect(jsonPath("$.upload.expiresAt").value("2026-11-02T10:15:00Z"));
    }

    @Test
    void aRetryWithTheSameKeyIsMarkedReplayedAndOmitsTheUrlOnceLanded() throws Exception {
        var key = new com.crisinis.fileuploadservice.model.IdempotencyKey("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        when(uploads.requestUpload(DeclaredFile.of("beach.png", "image/png", 1234), java.util.Optional.of(key)))
                .thenReturn(new UploadResult(file(FileStatus.SCANNING, null, null), null, true));

        mvc.perform(post("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key.value())
                        .content("{\"fileName\":\"beach.png\",\"contentType\":\"image/png\",\"size\":1234}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.file.status").value("SCANNING"))
                .andExpect(jsonPath("$.upload").doesNotExist());
    }

    @Test
    void anInvalidKeyIsABadRequestAndAReusedOneUnprocessable() throws Exception {
        mvc.perform(post("/api/v1/files").header("Idempotency-Key", "not valid!").contentType("image/png").content(new byte[] {1}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));

        when(uploads.stream(any(), any(), any())).thenThrow(new UploadRejectedException(
                UploadRejectedException.Reason.IDEMPOTENCY_KEY_REUSED, "used for another file"));
        mvc.perform(post("/api/v1/files").header("Idempotency-Key", "k-1").contentType("image/png").content(new byte[] {1}))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void anUploadCanOptInToExtraction() throws Exception {
        var policy = com.crisinis.fileuploadservice.model.ExtractionKind.INSURANCE_POLICY;
        when(uploads.stream(eq(new DeclaredFile("p.png", "image/png", 1, policy)), any(), any()))
                .thenReturn(new UploadResult(file(FileStatus.SCANNING, null, null), null, false));
        when(uploads.requestUpload(eq(new DeclaredFile("p.png", "image/png", 10L, policy)), any()))
                .thenReturn(new UploadResult(file(FileStatus.SCANNING, null, null), null, false));

        mvc.perform(post("/api/v1/files").param("fileName", "p.png").param("extract", "insurance-policy")
                        .contentType("image/png").content(new byte[] {1}))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"p.png\",\"contentType\":\"image/png\",\"size\":10,\"extract\":\"INSURANCE_POLICY\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/files").param("extract", "car-registration").contentType("image/png").content(new byte[] {1}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("INSURANCE_POLICY")));
    }

    @Test
    void presignedStyleNeedsTheSize() throws Exception {
        mvc.perform(post("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"beach.png\",\"contentType\":\"image/png\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("size is required: the file's length in bytes"));
    }

    @Test
    void streamStyleTakesTheFileAsTheBody() throws Exception {
        byte[] body = {(byte) 0x89, 'P', 'N', 'G'};
        when(uploads.stream(eq(DeclaredFile.of("beach.png", "image/png", 4L)), any(), any()))
                .thenReturn(new UploadResult(file(FileStatus.SCANNING, null, null), null, false));

        mvc.perform(post("/api/v1/files").param("fileName", "beach.png").contentType("image/png").content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/files/" + ID))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.submittedForScanAt").value("2026-11-02T10:00:02Z"));
    }

    @Test
    void aRefusedUploadSaysWhyWithItsStatus() throws Exception {
        when(uploads.stream(any(), any(), any())).thenThrow(new UploadRejectedException(
                UploadRejectedException.Reason.CONTENT_MISMATCH, "the content is not image/png"));

        mvc.perform(post("/api/v1/files").contentType("image/png").content(new byte[] {'M', 'Z'}))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.reason").value("CONTENT_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("the content is not image/png"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void aBusyInstanceAsksTheCallerToRetry() throws Exception {
        when(uploads.stream(any(), any(), any())).thenThrow(new UploadCapacityExceededException(64));

        mvc.perform(post("/api/v1/files").contentType("image/png").content(new byte[] {1}))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void anAwsFailureIsA503WithoutInternals() throws Exception {
        when(uploads.find(ID)).thenThrow(new DependencyUnavailableException("dynamodb", "GetItem",
                new RuntimeException("arn:aws:dynamodb:secret-detail")));

        mvc.perform(get("/api/v1/files/" + ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("arn"))));
    }

    @Test
    void showsAnAvailableFileWithItsTimesAndLink() throws Exception {
        when(uploads.find(ID)).thenReturn(file(FileStatus.AVAILABLE, ScanResult.NO_THREATS_FOUND, null));

        mvc.perform(get("/api/v1/files/" + ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.scanResult").value("NO_THREATS_FOUND"))
                .andExpect(jsonPath("$.scanCompletedAt").value("2026-11-02T10:00:20Z"))
                .andExpect(jsonPath("$.availableAt").value("2026-11-02T10:00:21Z"))
                .andExpect(jsonPath("$.contentUrl").value("/api/v1/files/" + ID + "/content"));
    }

    @Test
    void neverShowsTheThreatName() throws Exception {
        when(uploads.find(ID)).thenReturn(file(FileStatus.INFECTED, ScanResult.THREATS_FOUND, "Trojan.Example"));

        mvc.perform(get("/api/v1/files/" + ID))
                .andExpect(jsonPath("$.status").value("INFECTED"))
                .andExpect(jsonPath("$.rejectedBecause").doesNotExist())
                .andExpect(jsonPath("$.contentUrl").doesNotExist());
    }

    @Test
    void unknownAndMalformedIdsAreNotFound() throws Exception {
        when(uploads.find(ID)).thenThrow(new UnknownFileException(ID));

        mvc.perform(get("/api/v1/files/" + ID)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/not-an-id")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/files/" + ID.toUpperCase())).andExpect(status().isNotFound());
    }

    @Test
    void contentRedirectsToAShortLivedLink() throws Exception {
        when(uploads.downloadUrl(ID)).thenReturn(URI.create("https://file-upload-clean.s3.amazonaws.com/files/" + ID + "?sig"));

        mvc.perform(get("/api/v1/files/" + ID + "/content"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://file-upload-clean.s3.amazonaws.com/files/" + ID + "?sig"));
    }

    @Test
    void reportsScansInProgress() throws Exception {
        when(scans.summary()).thenReturn(new ScanSummary(3, T0, Duration.ofMinutes(5)));

        mvc.perform(get("/api/v1/scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inProgress").value(3))
                .andExpect(jsonPath("$.oldestSubmittedAt").value("2026-11-02T10:00:00Z"))
                .andExpect(jsonPath("$.overdueAfter").value("PT5M"));
    }

    @Test
    void multipartIsRefusedWithoutBeingParsed() throws Exception {
        when(uploads.stream(org.mockito.ArgumentMatchers.argThat(d -> d != null && "multipart/form-data; boundary=x".equals(d.mediaType())), any(), any()))
                .thenThrow(new UploadRejectedException(UploadRejectedException.Reason.UNSUPPORTED_TYPE, "not accepted"));

        mvc.perform(post("/api/v1/files").contentType("multipart/form-data; boundary=x").content("--x--"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(scans);
    }
}
