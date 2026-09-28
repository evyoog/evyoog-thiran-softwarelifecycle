package com.vyoog.api.config;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * VYB-0784: "a slow endpoint is attributable to a query from the trace" — this is
 * that literal capability, with no external Zipkin/Jaeger collector (none exists in
 * this sandbox, and none is needed): {@code micrometer-tracing-bridge-brave}
 * (see pom.xml) gives every request a real trace/span id, which Spring Boot's own
 * tracing autoconfiguration automatically decorates into the SLF4J MDC for every log
 * line emitted on that request's thread — including Hibernate's own SQL logging
 * (already {@code org.hibernate.SQL: INFO} in application.yml). Grep the log for one
 * trace id and both the request and every SQL statement it ran are right there,
 * already correlated, without adding a single log statement to any existing service.
 *
 * <p>This {@link SpanHandler} is the other half: it logs each completed span itself
 * (name, duration, trace/span id) — real span timing visible without shipping
 * anything to a collector that doesn't exist here.
 */
@Configuration
public class LoggingSpanHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger("com.vyoog.tracing");

    @Bean
    public SpanHandler loggingSpanHandler() {
        return new SpanHandler() {
            @Override
            public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                log.info("[span] traceId={} spanId={} name={} durationMs={} tags={}",
                    context.traceIdString(), context.spanIdString(), span.name(),
                    span.finishTimestamp() > 0 && span.startTimestamp() > 0
                        ? (span.finishTimestamp() - span.startTimestamp()) / 1000 : -1,
                    span.tags());
                return true; // let it continue to any other registered handler
            }
        };
    }
}
