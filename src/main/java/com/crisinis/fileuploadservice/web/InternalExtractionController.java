package com.crisinis.fileuploadservice.web;

import java.util.UUID;

import com.crisinis.fileuploadservice.extraction.ExtractionResults;
import com.crisinis.fileuploadservice.model.ExtractionRecord;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extraction results, for internal systems only: under {@code /internal/}, which the public load
 * balancer doesn't route (it routes {@code /api/}) and which isn't in the OpenAPI description. The
 * result holds personal data. README "Policy extraction".
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalExtractionController {

    private final ExtractionResults results;

    public InternalExtractionController(ExtractionResults results) {
        this.results = results;
    }

    /** The file's extraction: its status, and the policy once COMPLETED. 404 if the file wasn't opted in. */
    @GetMapping("/files/{id}/extraction")
    ExtractionRecord extraction(@PathVariable String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new UnknownFileException(id);
            }
        } catch (IllegalArgumentException notAnId) {
            throw new UnknownFileException(id);
        }
        return results.find(id);
    }
}
