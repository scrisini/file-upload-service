package com.crisinis.fileuploadservice.extraction;

import com.crisinis.fileuploadservice.model.ExtractionRecord;
import com.crisinis.fileuploadservice.model.ExtractionStatus;
import com.crisinis.fileuploadservice.model.PolicyExtraction;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.StoredExtraction;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Extractions as internal callers read them. */
@Service
public class ExtractionResults {

    private final FileRepository files;
    private final JsonMapper json;

    public ExtractionResults(FileRepository files, JsonMapper json) {
        this.files = files;
        this.json = json;
    }

    /**
     * The file's extraction.
     *
     * @throws UnknownFileException if the file doesn't exist, or its uploader didn't opt in
     */
    public ExtractionRecord find(String id) {
        StoredExtraction stored = files.findExtraction(id).orElseThrow(() -> new UnknownFileException(id));
        PolicyExtraction policy = stored.policyJson() == null ? null : json.readValue(stored.policyJson(), PolicyExtraction.class);
        return new ExtractionRecord(id, stored.kind(), status(stored), stored.requestedAt(), stored.completedAt(),
                stored.attempts(), stored.modelId(), policy, stored.failure());
    }

    /** Before the file is AVAILABLE the extraction has no status of its own: it follows the file's. */
    private static ExtractionStatus status(StoredExtraction stored) {
        if (stored.status() != null) {
            return stored.status();
        }
        return stored.fileStatus().isFinal() ? ExtractionStatus.NOT_EXTRACTED : ExtractionStatus.AWAITING_FILE;
    }
}
