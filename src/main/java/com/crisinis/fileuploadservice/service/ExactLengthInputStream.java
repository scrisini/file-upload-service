package com.crisinis.fileuploadservice.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A request body that must be exactly {@code length} bytes. Remembers whether the caller stopped
 * early (end of stream, or the connection failed while reading) so a failed upload is blamed on the
 * caller (400) rather than on S3 (503). Holds no bytes itself.
 */
final class ExactLengthInputStream extends FilterInputStream {

    private final long length;
    private long read;
    private boolean endedEarly;

    ExactLengthInputStream(InputStream in, long length) {
        super(in);
        this.length = length;
    }

    /** Whether the caller sent fewer bytes than it declared, or its connection failed. */
    boolean endedEarly() {
        return endedEarly;
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            count(b < 0 ? -1 : 1);
            return b;
        } catch (IOException e) {
            endedEarly = true;
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        try {
            int n = super.read(buffer, offset, count);
            count(n);
            return n;
        } catch (IOException e) {
            endedEarly = true;
            throw e;
        }
    }

    private void count(int n) {
        if (n < 0) {
            endedEarly |= read < length;
        } else {
            read += n;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        throw new IOException("skip is not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
