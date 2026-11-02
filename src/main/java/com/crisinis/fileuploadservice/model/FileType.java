package com.crisinis.fileuploadservice.model;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The file types the service accepts, each recognised two ways: by the media type the caller
 * declares, and by the signature ("magic bytes") at the start of the content. A file is accepted
 * only when both agree, so a renamed executable can't pass as an image.
 */
public enum FileType {

    JPEG("image/jpeg", List.of("image/jpg", "image/pjpeg")),
    PNG("image/png", List.of()),
    GIF("image/gif", List.of()),
    WEBP("image/webp", List.of()),
    HEIC("image/heic", List.of("image/heif", "image/heic-sequence", "image/heif-sequence")),
    TIFF("image/tiff", List.of()),
    PDF("application/pdf", List.of());

    /** Enough of the start of a file to recognise every type here. */
    public static final int SIGNATURE_BYTES = 16;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final List<String> HEIF_BRANDS =
            List.of("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1");

    private final String mediaType;
    private final List<String> aliases;

    FileType(String mediaType, List<String> aliases) {
        this.mediaType = mediaType;
        this.aliases = aliases;
    }

    /** The canonical media type, used when storing and serving the file. */
    public String mediaType() {
        return mediaType;
    }

    /** The type for a declared media type ({@code image/png}, {@code IMAGE/JPG; charset=x}); empty if unsupported. */
    public static Optional<FileType> fromMediaType(String declared) {
        if (declared == null) {
            return Optional.empty();
        }
        String bare = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        for (FileType type : values()) {
            if (type.mediaType.equals(bare) || type.aliases.contains(bare)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** The type the content's first bytes show; empty if they match none of these types. */
    public static Optional<FileType> detect(byte[] head) {
        for (FileType type : values()) {
            if (type.matches(head)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Whether the content's first bytes carry this type's signature. */
    public boolean matches(byte[] head) {
        return switch (this) {
            case JPEG -> startsWith(head, 0, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
            case PNG -> startsWith(head, 0, PNG_SIGNATURE);
            case GIF -> startsWith(head, 0, ascii("GIF87a")) || startsWith(head, 0, ascii("GIF89a"));
            case WEBP -> startsWith(head, 0, ascii("RIFF")) && startsWith(head, 8, ascii("WEBP"));
            case HEIC -> startsWith(head, 4, ascii("ftyp")) && head.length >= 12
                    && HEIF_BRANDS.contains(new String(head, 8, 4, StandardCharsets.US_ASCII));
            case TIFF -> startsWith(head, 0, (byte) 'I', (byte) 'I', (byte) 0x2A, (byte) 0x00)
                    || startsWith(head, 0, (byte) 'M', (byte) 'M', (byte) 0x00, (byte) 0x2A);
            case PDF -> startsWith(head, 0, ascii("%PDF-"));
        };
    }

    private static boolean startsWith(byte[] head, int offset, byte... signature) {
        if (head.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (head[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
