package com.lexisnexis.transform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Typed configuration for all application settings, bound from {@code application.yml}
 * under the {@code app} prefix.
 *
 * <p>Every property can be overridden at runtime via environment variables using
 * Spring Boot's relaxed binding rules — for example, {@code APP_OUTPUT_DIR} maps to
 * {@code app.output-dir}.</p>
 *
 * <p>See {@code src/main/resources/application.yml} for defaults and inline docs.</p>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Root directory where {@code {contentId}/judgment.json} and {@code judgment.txt} are written. */
    private String outputDir = "./output";

    /** Thread-pool settings for the async document processing pipeline. */
    private Concurrency concurrency = new Concurrency();

    /** XSLT stylesheet location — can point to a classpath resource or an external file URI. */
    private Xslt xslt = new Xslt();

    /** XSD schema location — can point to a classpath resource or an external file URI. */
    private Schema schema = new Schema();

    /** Controls the bounded thread pool used by {@link com.lexisnexis.transform.config.ExecutorConfig}. */
    @Getter
    @Setter
    public static class Concurrency {

        /** Number of documents that can be validated and transformed simultaneously. */
        private int poolSize = 4;

        /** Maximum documents that can wait in the queue before back-pressure is applied. */
        private int queueCapacity = 100;
    }

    @Getter
    @Setter
    public static class Xslt {
        private String path = "classpath:xslt/judgment-to-json.xslt";
    }

    @Getter
    @Setter
    public static class Schema {
        private String path = "classpath:schemas/judgment.xsd";
    }
}
