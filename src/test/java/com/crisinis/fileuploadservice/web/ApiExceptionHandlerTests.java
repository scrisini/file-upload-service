package com.crisinis.fileuploadservice.web;

import com.crisinis.fileuploadservice.logging.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Only this test's controller: the service's own controllers need beans this slice doesn't create
@WebMvcTest(ApiExceptionHandlerTests.GetOnly.class)
@Import({ApiExceptionHandler.class, RequestIdFilter.class, ApiExceptionHandlerTests.GetOnly.class})
class ApiExceptionHandlerTests {

    @RestController
    static class GetOnly {
        @GetMapping("/test/get-only")
        String get() {
            return "ok";
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void anUnknownPathIsAProblemWithTheRequestId() throws Exception {
        mvc.perform(get("/api/v1/nothing-here").header(RequestIds.HEADER, "ticket-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(RequestIds.HEADER, "ticket-404"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.requestId").value("ticket-404"));
    }

    @Test
    void aWrongMethodIsAProblemWithTheRequestId() throws Exception {
        mvc.perform(post("/test/get-only").header(RequestIds.HEADER, "ticket-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.requestId").value("ticket-405"));
    }
}
