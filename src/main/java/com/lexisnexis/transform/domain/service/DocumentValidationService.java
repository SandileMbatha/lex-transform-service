package com.lexisnexis.transform.domain.service;

import com.lexisnexis.transform.config.AppProperties;
import com.lexisnexis.transform.domain.exception.DocumentValidationException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates XML document content against the legal judgment XSD schema.
 *
 * What is XSD validation?
 *   An XSD (XML Schema Definition) is like a form template — it defines
 *   which fields are required, which are optional, and what data types
 *   are expected (e.g. decision_date must be a real calendar date).
 *   Validation checks the submitted XML against these rules.
 *
 * The schema is compiled once at startup (expensive) and reused for
 * each validation call (cheap). The Validator itself is created fresh
 * per call because it is not thread-safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentValidationService {

    private static final String LOG_PREFIX = "[Document Validation] - ";

    private final AppProperties appProperties;
    private final ResourceLoader resourceLoader;

    private Schema compiledXsdSchema;

    /**
     * Loads the XSD schema from the configured classpath resource and compiles it into a reusable
     * {@link Schema} object. Called once automatically by Spring after dependency injection.
     *
     * <p>External DTD and schema access are blocked to prevent XXE (XML External Entity) injection.</p>
     *
     * @throws SAXException if the XSD file contains syntax errors
     * @throws IOException  if the XSD resource cannot be read
     */
    @PostConstruct
    void loadAndCompileXsdSchema() throws SAXException, IOException {
        final SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        final var xsdResource = resourceLoader.getResource(appProperties.getSchema().getPath());
        try (final var xsdInputStream = xsdResource.getInputStream()) {
            compiledXsdSchema = schemaFactory.newSchema(new StreamSource(xsdInputStream));
        }
        log.info("{}XSD schema compiled from: {}", LOG_PREFIX, appProperties.getSchema().getPath());
    }

    /**
     * Validates the provided XML string against the judgment XSD schema.
     *
     * All errors are collected first so the caller receives the full list
     * rather than discovering issues one at a time.
     *
     * @param xmlDocumentContent the raw XML string to validate
     * @throws DocumentValidationException if any validation errors are found
     */
    public void validateXmlAgainstSchema(final String xmlDocumentContent) {
        log.debug("{}Starting XSD validation", LOG_PREFIX);

        final CollectingXsdErrorHandler errorHandler = new CollectingXsdErrorHandler();
        final Validator schemaValidator = compiledXsdSchema.newValidator();
        schemaValidator.setErrorHandler(errorHandler);

        try {
            schemaValidator.validate(new StreamSource(new StringReader(xmlDocumentContent)));
        } catch (SAXParseException fatalParseException) {
            // Fatal error is already captured by errorHandler.fatalError() — stop here
        } catch (SAXException | IOException unexpectedException) {
            errorHandler.addError("Validation processing error: " + unexpectedException.getMessage());
        }

        if (!errorHandler.getErrors().isEmpty()) {
            log.warn("{}Validation failed with {} error(s)", LOG_PREFIX, errorHandler.getErrors().size());
            throw new DocumentValidationException(errorHandler.getErrors());
        }

        log.debug("{}Validation passed", LOG_PREFIX);
    }

    /**
     * SAX {@link ErrorHandler} that collects all validation problems into a list rather than
     * stopping at the first error. This ensures the caller receives the complete set of
     * violations in a single {@link DocumentValidationException}.
     */
    private static final class CollectingXsdErrorHandler implements ErrorHandler {

        private final List<String> errors = new ArrayList<>();

        /**
         * Adds a pre-formatted error message directly (used for non-SAX errors such as I/O failures).
         *
         * @param errorMessage the message to append to the error list
         */
        void addError(final String errorMessage) {
            errors.add(errorMessage);
        }

        /**
         * Returns all accumulated validation messages.
         *
         * @return immutable snapshot of the error list
         */
        List<String> getErrors() {
            return errors;
        }

        /**
         * Records a non-fatal warning with its source location.
         *
         * @param exception the SAX parse exception describing the warning
         */
        @Override
        public void warning(final SAXParseException exception) {
            errors.add("WARNING [line %d, col %d]: %s"
                    .formatted(exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
        }

        /**
         * Records a recoverable schema violation with its source location.
         *
         * @param exception the SAX parse exception describing the violation
         */
        @Override
        public void error(final SAXParseException exception) {
            errors.add("ERROR [line %d, col %d]: %s"
                    .formatted(exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
        }

        /**
         * Records an unrecoverable parse error and re-throws to stop further parsing.
         *
         * @param exception the SAX parse exception describing the fatal error
         * @throws SAXException always, to signal the validator that parsing should stop
         */
        @Override
        public void fatalError(final SAXParseException exception) throws SAXException {
            errors.add("FATAL [line %d, col %d]: %s"
                    .formatted(exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
            throw exception;
        }
    }
}
