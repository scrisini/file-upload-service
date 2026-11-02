package com.crisinis.fileuploadservice.model;

/** How the bytes reached the quarantine bucket. Both are offered; README "Upload styles" compares them. */
public enum UploadMethod {

    /** The caller PUTs straight to S3 with a URL the service signed; the bytes never pass through the service. */
    PRESIGNED_URL,
    /** The caller sends the bytes to the service, which streams them to S3 without storing them. */
    STREAM
}
