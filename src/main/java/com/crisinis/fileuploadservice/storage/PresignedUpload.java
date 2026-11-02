package com.crisinis.fileuploadservice.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * What a caller needs to PUT a file straight to S3. The URL is signed for the content type, the
 * exact length and {@code If-None-Match: *}: a PUT with another type, another length or without
 * that header is refused (403), and a second PUT to the same URL is refused too (412).
 *
 * @param url       where to PUT the bytes
 * @param headers   headers the caller must set on the PUT, exactly; Content-Length isn't among them
 *                  because the browser sets it from the body (it is still signed)
 * @param expiresAt when the URL stops working
 */
public record PresignedUpload(URI url, Map<String, String> headers, Instant expiresAt) {
}
