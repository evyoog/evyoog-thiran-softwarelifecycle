package com.vyoog.api;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication(scanBasePackages = "com.vyoog")
@EntityScan(basePackages = "com.vyoog")
@EnableJpaRepositories(basePackages = "com.vyoog")
@EnableScheduling // the triggers live in com.vyoog.api.scheduling.ScheduledJobs
@EnableAsync // RequirementEnrichmentService — post-commit detection + embedding for a new requirement
public class VyoogApplication {
    public static void main(String[] args) {
        SpringApplication.run(VyoogApplication.class, args);
    }

    /**
     * Bounded on purpose. Every task submitted here is a wait on an external AI provider,
     * not CPU work — {@code SimpleAsyncTaskExecutor}, Spring's default with no bean of
     * this name present, spawns one thread per task with no ceiling, which is exactly
     * what a batch import of hundreds of rows should not be allowed to do to the JVM.
     * {@code CallerRunsPolicy} means a queue this deep filling up degrades to synchronous
     * (the caller's own thread does the work) rather than dropping the enrichment
     * silently — slower than async, never lost.
     */
    @Bean
    public Executor requirementEnrichmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("req-enrich-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
