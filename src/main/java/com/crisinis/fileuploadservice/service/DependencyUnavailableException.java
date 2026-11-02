package com.crisinis.fileuploadservice.service;

/** An AWS dependency (S3, DynamoDB, SQS) failed or timed out; callers get 503 and may retry. */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;
    private final String operation;

    public DependencyUnavailableException(String dependency, String operation, Throwable cause) {
        super(dependency + " " + operation + " failed: " + cause.getMessage(), cause);
        this.dependency = dependency;
        this.operation = operation;
    }

    public String dependency() {
        return dependency;
    }

    public String operation() {
        return operation;
    }
}
