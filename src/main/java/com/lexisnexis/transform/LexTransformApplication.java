package com.lexisnexis.transform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the lex-transform-service.
 *
 * Starting the application triggers all {@link jakarta.annotation.PostConstruct} hooks:
 * XSD schema compilation ({@link com.lexisnexis.transform.domain.service.DocumentValidationService}),
 * XSLT compilation ({@link com.lexisnexis.transform.domain.service.DocumentTransformationService}),
 * and output directory creation ({@link com.lexisnexis.transform.domain.service.DocumentPublishingService}).
 */
@SpringBootApplication
public class LexTransformApplication {

    /**
     * Starts the embedded Tomcat server and initialises the Spring application context.
     *
     * @param args command-line arguments passed through to Spring Boot (e.g. {@code --server.port=9090})
     */
    public static void main(final String[] args) {
        SpringApplication.run(LexTransformApplication.class, args);
    }
}
