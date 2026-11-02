package com.crisinis.fileuploadservice.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileTypeTests {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void recognisesEachTypeByItsSignature() {
        assertThat(FileType.detect(JPEG)).contains(FileType.JPEG);
        assertThat(FileType.detect(PNG)).contains(FileType.PNG);
        assertThat(FileType.detect(ascii("GIF89a\u0001\u0000"))).contains(FileType.GIF);
        assertThat(FileType.detect(ascii("GIF87a"))).contains(FileType.GIF);
        assertThat(FileType.detect(ascii("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))).contains(FileType.WEBP);
        assertThat(FileType.detect(ascii("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000"))).contains(FileType.HEIC);
        assertThat(FileType.detect(ascii("\u0000\u0000\u0000\u0018ftypmif1\u0000\u0000"))).contains(FileType.HEIC);
        assertThat(FileType.detect(ascii("II*\u0000\u0008\u0000"))).contains(FileType.TIFF);
        assertThat(FileType.detect(ascii("MM\u0000*\u0000\u0000"))).contains(FileType.TIFF);
        assertThat(FileType.detect(ascii("%PDF-1.7\n"))).contains(FileType.PDF);
    }

    @Test
    void recognisesNothingElse() {
        assertThat(FileType.detect(ascii("MZ\u0090\u0000"))).isEmpty();                      // Windows executable
        assertThat(FileType.detect(ascii("<svg xmlns="))).isEmpty();
        assertThat(FileType.detect(ascii("RIFF\u0010\u0000\u0000\u0000WAVEfmt "))).isEmpty();  // RIFF, but audio
        assertThat(FileType.detect(ascii("\u0000\u0000\u0000\u0018ftypavif"))).isEmpty();     // AVIF isn't HEIC
        assertThat(FileType.detect(new byte[0])).isEmpty();
        assertThat(FileType.detect(new byte[] {(byte) 0xFF})).isEmpty();
    }

    @Test
    void aSignatureMatchesOnlyItsOwnType() {
        assertThat(FileType.PNG.matches(PNG)).isTrue();
        assertThat(FileType.JPEG.matches(PNG)).isFalse();
        assertThat(FileType.PDF.matches(JPEG)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "image/png, PNG",
            "IMAGE/PNG, PNG",
            "image/jpeg; charset=binary, JPEG",
            "image/jpg, JPEG",
            "image/heif, HEIC",
            "application/pdf, PDF"})
    void readsDeclaredMediaTypes(String declared, FileType expected) {
        assertThat(FileType.fromMediaType(declared)).contains(expected);
    }

    @Test
    void refusesOtherMediaTypes() {
        assertThat(FileType.fromMediaType("image/svg+xml")).isEmpty();
        assertThat(FileType.fromMediaType("application/octet-stream")).isEmpty();
        assertThat(FileType.fromMediaType("multipart/form-data; boundary=x")).isEmpty();
        assertThat(FileType.fromMediaType(null)).isEmpty();
    }
}
