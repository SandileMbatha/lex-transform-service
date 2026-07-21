package com.lexisnexis.transform.domain.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DocumentTransformationServiceTest {

    @Autowired
    DocumentTransformationService documentTransformationService;

    @Test
    void validXml_producesExpectedJsonFields() throws Exception {
        final String xmlDocumentContent = loadSampleFile("valid-judgment.xml");
        final String jsonResult = documentTransformationService.transformXmlDocumentToJson(xmlDocumentContent);

        assertThat(jsonResult).contains("\"content_id\"");
        assertThat(jsonResult).contains("FR-2024-CA-000123");
        assertThat(jsonResult).contains("\"court\"");
        assertThat(jsonResult).contains("Cour d'appel de Paris");
        assertThat(jsonResult).contains("\"paragraphs\"");
        assertThat(jsonResult).contains("\"full_text\"");
        assertThat(jsonResult).contains("ECLI:FR:CA12345");
        assertThat(jsonResult).contains("Société ABC");
    }

    @Test
    void extractPlainText_concatenatesParagraphs() throws Exception {
        final String xmlDocumentContent = loadSampleFile("valid-judgment.xml");
        final String plainTextResult = documentTransformationService.extractPlainTextFromXmlDocument(xmlDocumentContent);

        assertThat(plainTextResult).contains("Le litige porte sur");
        assertThat(plainTextResult).contains("Considérant que");
        assertThat(plainTextResult).contains("Par ces motifs");
    }

    private String loadSampleFile(final String filename) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("samples/" + filename)) {
            assert stream != null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
