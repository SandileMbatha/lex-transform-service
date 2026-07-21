package com.lexisnexis.transform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures the named executor used for asynchronous document processing.
 *
 * <p>The pool is bounded — {@code poolSize} documents run concurrently and up to
 * {@code queueCapacity} documents can wait before the caller receives back-pressure.
 * On shutdown the executor waits up to 30 seconds for in-flight documents to
 * complete before forcibly terminating threads.</p>
 *
 * @see AppProperties.Concurrency
 * @see com.lexisnexis.transform.domain.service.DocumentOrchestratorService
 */
@Configuration
public class ExecutorConfig {

    /**
     * Creates the executor bean injected into {@link com.lexisnexis.transform.domain.service.DocumentOrchestratorService}.
     *
     * @param appProperties configuration source for pool size and queue capacity
     * @return a fully initialised, bounded {@link ThreadPoolTaskExecutor}
     */
    @Bean(name = "documentProcessingExecutor")
    public Executor documentProcessingExecutor(final AppProperties appProperties) {
        final ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(appProperties.getConcurrency().getPoolSize());
        taskExecutor.setMaxPoolSize(appProperties.getConcurrency().getPoolSize());
        taskExecutor.setQueueCapacity(appProperties.getConcurrency().getQueueCapacity());
        taskExecutor.setThreadNamePrefix("document-processor-");
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(30);
        taskExecutor.initialize();
        return taskExecutor;
    }
}
