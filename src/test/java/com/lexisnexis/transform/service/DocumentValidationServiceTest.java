package com.lexisnexis.transform.domain.service;

import com.lexisnexis.transform.domain.exception.DocumentValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class DocumentValidationServiceTest {

    @Autowired
    DocumentValidationService documentValidationService;

    @Test
    void validDocument_doesNotThrow() throws IOException {
        final String xmlDocumentContent = loadSampleFile("valid-judgment.xml");
        assertThatNoException()
                .isThrownBy(() -> documentValidationService.validateXmlAgainstSchema(xmlDocumentContent));
    }

    @Test
    void invalidDocument_throwsDocumentValidationExceptionWithErrors() throws IOException {
        final String invalidXmlContent = loadSampleFile("invalid-judgment.xml");
        assertThatThrownBy(() -> documentValidationService.validateXmlAgainstSchema(invalidXmlContent))
                .isInstanceOf(DocumentValidationException.class)
                .satisfies(e -> assertThat(((DocumentValidationException) e).getValidationErrors()).isNotEmpty());
    }

    @Test
    void malformedXml_throwsDocumentValidationException() {
        final String malformedXmlContent = "<judgment xmlns='urn:lex:content:1'><unclosed>";
        assertThatThrownBy(() -> documentValidationService.validateXmlAgainstSchema(malformedXmlContent))
                .isInstanceOf(DocumentValidationException.class);
    }

    private String loadSampleFile(final String filename) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("samples/" + filename)) {
            assert stream != null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
