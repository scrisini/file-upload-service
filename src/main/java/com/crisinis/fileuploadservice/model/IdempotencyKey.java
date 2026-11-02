package com.crisinis.fileuploadservice.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A caller's {@code Idempotency-Key}: the same key on a retried upload means "the same file". The
 * key decides the file's id (a name-based UUID), so every attempt with it, retried, concurrent or
 * after a crash, works on the same record and the same S3 object, and the conditional writes let
 * only one of them store the bytes.
 *
 * @param value 1 to 128 characters: letters, digits and {@code - _ . :} (a UUID is ideal)
 */
public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    /** Keeps these ids apart from any other name-based UUIDs. */
    private static final String NAMESPACE = "file-upload-service/idempotency-key/";

    public IdempotencyKey {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(HEADER + " must be 1 to 128 letters, digits or - _ . : (a UUID works)");
        }
    }

    /** The id of the file this key stands for: the same for every attempt. */
    public String fileId() {
        return UUID.nameUUIDFromBytes((NAMESPACE + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
