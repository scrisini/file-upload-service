package com.crisinis.fileuploadservice.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI description and Swagger UI, generated from the running application. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocumentationTests {

    static final String STREAM_BODY = "$.paths['/api/v1/files'].post.requestBody.content";

    @Autowired
    MockMvc mvc;

    @Test
    void describesTheApiAndOnlyTheApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("file-upload-service"))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/uploads")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/files/{id}")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/files/{id}/content")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v1/scans")))
                .andExpect(jsonPath("$.paths", not(hasKey("/actuator/health"))));
    }

    @Test
    void theStreamStyleTakesTheRawFileNotAServletRequest() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(STREAM_BODY + "['image/png'].schema.format").value("binary"))
                .andExpect(jsonPath(STREAM_BODY + "['application/pdf'].schema.format").value("binary"))
                .andExpect(jsonPath(STREAM_BODY, not(hasKey("multipart/form-data"))))
                .andExpect(jsonPath("$.paths['/api/v1/files'].post.parameters[*].name").value(
                        org.hamcrest.Matchers.contains("fileName")))
                .andExpect(jsonPath("$.paths['/api/v1/files'].post.responses", hasKey("415")));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
