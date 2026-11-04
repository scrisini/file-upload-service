package com.crisinis.fileuploadservice.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.crisinis.fileuploadservice.model.ExtractionKind;
import com.crisinis.fileuploadservice.model.ExtractionStatus;
import com.crisinis.fileuploadservice.model.FileStatus;
import com.crisinis.fileuploadservice.model.Money;
import com.crisinis.fileuploadservice.persistence.FileRepository;
import com.crisinis.fileuploadservice.persistence.FileRepository.StoredExtraction;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.support.SamplePolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExtractionResultsTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";
    static final Instant AT = Instant.parse("2026-11-04T10:00:00Z");

    private final FileRepository files = mock(FileRepository.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExtractionResults results = new ExtractionResults(files, json);

    static StoredExtraction stored(FileStatus file, ExtractionStatus status, String policy) {
        return new StoredExtraction(ID, file, ExtractionKind.INSURANCE_POLICY, status, AT, AT, 1, "model-1", policy, null);
    }

    @Test
    void aStoredPolicyReadsBackAsWritten() {
        var policy = new PolicyParser(json).parse(SamplePolicy.JSON);
        when(files.findExtraction(ID)).thenReturn(Optional.of(stored(FileStatus.AVAILABLE, ExtractionStatus.COMPLETED,
                json.writeValueAsString(policy))));

        var record = results.find(ID);

        assertThat(record.status()).isEqualTo(ExtractionStatus.COMPLETED);
        assertThat(record.policy()).isEqualTo(policy);
        assertThat(record.policy().premium().value()).isEqualTo(new Money(184260, "AUD", 2));
    }

    @Test
    void beforeTheFileIsAvailableTheStatusFollowsTheFile() {
        when(files.findExtraction(ID)).thenReturn(Optional.of(stored(FileStatus.SCANNING, null, null)));
        assertThat(results.find(ID).status()).isEqualTo(ExtractionStatus.AWAITING_FILE);

        when(files.findExtraction(ID)).thenReturn(Optional.of(stored(FileStatus.INFECTED, null, null)));
        assertThat(results.find(ID).status()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
    }

    @Test
    void aFileNotOptedInHasNoExtraction() {
        when(files.findExtraction(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> results.find(ID)).isInstanceOf(UnknownFileException.class);
    }
}
