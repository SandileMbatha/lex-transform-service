package com.lexisnexis.transform.domain.service;

import com.lexisnexis.transform.config.AppProperties;
import com.lexisnexis.transform.domain.exception.DocumentTransformException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Transforms validated XML documents into JSON and plain-text artifacts
 * using Saxon-HE, an XSLT 3.0 processing engine.
 *
 * What is XSLT?
 *   XSLT is a stylesheet language — think of it as a recipe that reads
 *   XML in and writes something else out. Our recipe (judgment-to-json.xslt)
 *   converts a legal judgment XML document into a normalised JSON record.
 *
 * Saxon-HE object lifecycle:
 *   - Processor:       created once at startup; shared across all threads (thread-safe)
 *   - XsltExecutable:  compiled once at startup from the XSLT file (thread-safe)
 *   - XsltTransformer: created per request; used for one document then discarded (not thread-safe)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTransformationService {

    private static final String LOG_PREFIX = "[Document Transformation] - ";

    private final AppProperties appProperties;
    private final ResourceLoader resourceLoader;

    private Processor saxonProcessor;
    private XsltExecutable compiledXsltExecutable;

    /**
     * Creates the Saxon {@link Processor} and compiles the XSLT stylesheet into a reusable
     * {@link XsltExecutable}. Called once automatically by Spring after dependency injection.
     *
     * <p>Compilation is the expensive step; loading a {@link XsltTransformer} from the
     * compiled executable is cheap and happens per document in {@link #transformXmlDocumentToJson}.</p>
     *
     * @throws SaxonApiException if the XSLT file contains syntax errors
     * @throws IOException       if the XSLT resource cannot be read
     */
    @PostConstruct
    void initialiseSaxonAndCompileXslt() throws SaxonApiException, IOException {
        saxonProcessor = new Processor(false); // false = Saxon-HE (free edition)
        final XsltCompiler xsltCompiler = saxonProcessor.newXsltCompiler();

        final var xsltResource = resourceLoader.getResource(appProperties.getXslt().getPath());
        try (final var xsltInputStream = xsltResource.getInputStream()) {
            compiledXsltExecutable = xsltCompiler.compile(new StreamSource(xsltInputStream));
        }
        log.info("{}XSLT stylesheet compiled from: {}", LOG_PREFIX, appProperties.getXslt().getPath());
    }

    /**
     * Runs the XSLT transformation and returns the normalised JSON string.
     *
     * @param xmlDocumentContent validated XML content
     * @return normalised JSON string
     * @throws DocumentTransformException if Saxon encounters an error during transformation
     */
    public String transformXmlDocumentToJson(final String xmlDocumentContent) {
        log.debug("{}Starting XSLT transformation to JSON", LOG_PREFIX);
        try {
            final XdmNode parsedXmlDocument = saxonProcessor.newDocumentBuilder()
                    .build(new StreamSource(new StringReader(xmlDocumentContent)));

            final XsltTransformer xsltTransformer = compiledXsltExecutable.load();
            final StringWriter jsonOutputWriter = new StringWriter();
            final Serializer jsonSerializer = saxonProcessor.newSerializer(jsonOutputWriter);

            xsltTransformer.setInitialContextNode(parsedXmlDocument);
            xsltTransformer.setDestination(jsonSerializer);
            xsltTransformer.transform();

            log.debug("{}XSLT transformation completed successfully", LOG_PREFIX);
            return jsonOutputWriter.toString();

        } catch (final SaxonApiException saxonException) {
            throw new DocumentTransformException(
                    "XSLT transformation failed: " + saxonException.getMessage(), saxonException);
        }
    }

    /**
     * Extracts all paragraph text from the XML document and joins it into a
     * single plain-text string, suitable for AI/RAG ingestion.
     *
     * @param xmlDocumentContent validated XML content
     * @return concatenated paragraph text
     * @throws DocumentTransformException if the XPath extraction fails
     */
    public String extractPlainTextFromXmlDocument(final String xmlDocumentContent) {
        log.debug("{}Extracting plain text for RAG output", LOG_PREFIX);
        try {
            final XdmNode parsedXmlDocument = saxonProcessor.newDocumentBuilder()
                    .build(new StreamSource(new StringReader(xmlDocumentContent)));

            final var xpathCompiler = saxonProcessor.newXPathCompiler();
            xpathCompiler.declareNamespace("lex", "urn:lex:content:1");

            final var xpathSelector = xpathCompiler
                    .compile("string-join(//lex:body/lex:section/lex:p/normalize-space(string(.)), ' ')")
                    .load();
            xpathSelector.setContextItem(parsedXmlDocument);

            final var xpathResult = xpathSelector.evaluateSingle();
            return xpathResult != null ? xpathResult.getStringValue() : "";

        } catch (final SaxonApiException saxonException) {
            throw new DocumentTransformException(
                    "Plain-text extraction failed: " + saxonException.getMessage(), saxonException);
        }
    }
}
