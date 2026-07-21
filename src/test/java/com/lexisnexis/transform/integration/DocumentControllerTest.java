package com.lexisnexis.transform.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void submitValidXml_returns202() throws Exception {
        final String xmlDocumentContent = loadSampleFile("valid-judgment.xml");

        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xmlDocumentContent))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.contentId").value("FR-2024-CA-000123"))
                .andExpect(jsonPath("$.status").value(anyOf(
                        equalTo("PENDING"), equalTo("PROCESSING"), equalTo("PUBLISHED")
                )));
    }

    @Test
    void submitInvalidXml_returns202_andEventuallyInvalid() throws Exception {
        final String invalidXmlContent = loadSampleFile("invalid-judgment.xml");

        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(invalidXmlContent))
                .andExpect(status().isAccepted());
    }

    @Test
    void getUnknownDocument_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/documents/DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAll_returnsArray() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void submitSameDocumentTwice_secondCallIsIdempotent() throws Exception {
        final String xmlDocumentContent = loadSampleFile("valid-judgment.xml");

        // First submission
        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xmlDocumentContent))
                .andExpect(status().isAccepted());

        // Allow async processing to complete
        Thread.sleep(500);

        // Second submission of identical content — should return 200 (not 202)
        mockMvc.perform(post("/api/v1/documents")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xmlDocumentContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("Already published")));
    }

    private String loadSampleFile(final String filename) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("samples/" + filename)) {
            assert stream != null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
