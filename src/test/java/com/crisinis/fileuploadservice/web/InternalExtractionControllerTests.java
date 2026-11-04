package com.crisinis.fileuploadservice.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.crisinis.fileuploadservice.extraction.ExtractionResults;
import com.crisinis.fileuploadservice.extraction.PolicyParser;
import com.crisinis.fileuploadservice.model.ExtractionKind;
import com.crisinis.fileuploadservice.model.ExtractionRecord;
import com.crisinis.fileuploadservice.model.ExtractionStatus;
import com.crisinis.fileuploadservice.service.UnknownFileException;
import com.crisinis.fileuploadservice.support.SamplePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(InternalExtractionController.class)
@Import({ApiExceptionHandler.class, RequestIdFilter.class})
class InternalExtractionControllerTests {

    static final String ID = "0b8e2d4a-55a3-4d0e-9f53-3e1b5f0a7c11";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExtractionResults results;

    @Test
    void showsACompletedExtractionWithMoneyInMinorUnits() throws Exception {
        var policy = new PolicyParser(JsonMapper.builder().build()).parse(SamplePolicy.JSON);
        when(results.find(ID)).thenReturn(new ExtractionRecord(ID, ExtractionKind.INSURANCE_POLICY, ExtractionStatus.COMPLETED,
                Instant.parse("2026-11-04T10:00:00Z"), Instant.parse("2026-11-04T10:00:07Z"), 1, "model-1", policy, null));

        mvc.perform(get("/internal/v1/files/" + ID + "/extraction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.kind").value("INSURANCE_POLICY"))
                .andExpect(jsonPath("$.policy.policyNumber.value").value("HPC-0042-7781"))
                .andExpect(jsonPath("$.policy.policyNumber.confidence").value(0.97))
                .andExpect(jsonPath("$.policy.premium.value.amountMinor").value(184260))
                .andExpect(jsonPath("$.policy.premium.value.currency").value("AUD"))
                .andExpect(jsonPath("$.policy.premium.value.scale").value(2))
                .andExpect(jsonPath("$.policy.endDate.value").value("2027-06-30"));
    }

    @Test
    void unknownFilesAndFilesNotOptedInAreNotFound() throws Exception {
        when(results.find(ID)).thenThrow(new UnknownFileException(ID));

        mvc.perform(get("/internal/v1/files/" + ID + "/extraction")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/files/not-an-id/extraction")).andExpect(status().isNotFound());
    }
}
