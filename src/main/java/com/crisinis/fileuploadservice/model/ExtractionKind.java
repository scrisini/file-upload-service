package com.crisinis.fileuploadservice.model;

import java.util.Locale;
import java.util.Optional;

/** What a file is extracted as, when its uploader opts in. Only insurance policies for now. */
public enum ExtractionKind {

    INSURANCE_POLICY;

    /** The kind an upload request names ({@code INSURANCE_POLICY}, {@code insurance-policy}); empty if unknown. */
    public static Optional<ExtractionKind> of(String requested) {
        if (requested == null) {
            return Optional.empty();
        }
        String name = requested.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ExtractionKind kind : values()) {
            if (kind.name().equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
