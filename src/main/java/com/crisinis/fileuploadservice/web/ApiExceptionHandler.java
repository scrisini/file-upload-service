package com.crisinis.fileuploadservice.web;

import com.crisinis.fileuploadservice.logging.LogEvent;
import com.crisinis.fileuploadservice.logging.RequestIds;
import com.crisinis.fileuploadservice.service.DependencyUnavailableException;
import com.crisinis.fileuploadservice.service.FileNotAvailableException;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.service.UploadCapacityExceededException;
import com.crisinis.fileuploadservice.service.UploadRejectedException;
import com.crisinis.fileuploadservice.storage.ObjectExistsException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain errors to RFC 9457 problem responses. Add a handler per domain exception.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler}, so Spring's own errors (unknown path, wrong
 * method, unreadable body) keep their status codes: Spring picks the most specific handler, and
 * only exceptions nothing else handles reach {@link #unexpected}. Error bodies carry the
 * {@code requestId}, which callers quote to support.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail uploadRejected(UploadRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.reason().status(), e.getMessage());
        problem.setProperty("reason", e.reason().name());
        return withRequestId(problem);
    }

    @ExceptionHandler(UnknownFileException.class)
    ProblemDetail unknownFile(UnknownFileException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler({FileNotAvailableException.class, ObjectExistsException.class})
    ProblemDetail conflict(RuntimeException e) {
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(UploadCapacityExceededException.class)
    ResponseEntity<ProblemDetail> busy(UploadCapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage())));
    }

    /** S3 or DynamoDB failed: logged with what failed; the caller is told to retry, without internals. */
    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ProblemDetail> dependencyUnavailable(DependencyUnavailableException e, HttpServletRequest request) {
        LogEvent.DEPENDENCY_UNAVAILABLE.at(log)
                .with("dependency", e.dependency())
                .with("operation", e.operation())
                .with("method", request.getMethod())
                .with("path", request.getRequestURI())
                .cause(e)
                .log();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "storage is unavailable; retry shortly and quote the requestId if it persists")));
    }

    /** Anything without a more specific handler: logged once with its code and request id; no internals in the response. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e, HttpServletRequest request) {
        LogEvent.UNEXPECTED_ERROR.at(log)
                .with("method", request.getMethod())
                .with("path", request.getRequestURI())
                .cause(e)
                .log();
        return withRequestId(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "unexpected error; quote the requestId to support"));
    }

    /** Spring's own errors (unknown path, wrong method, unreadable body) carry the request id too. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withRequestId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail withRequestId(ProblemDetail problem) {
        String requestId = RequestIds.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
