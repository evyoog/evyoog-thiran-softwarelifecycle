package com.vyoog.api.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vyoog.platform.SchedulerLock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * VYB-0909 (F33-F35): the Prometheus registry is on the classpath and wired in, the scrape endpoint
 * serves it, and it is not public. {@code management.endpoints.web.exposure.include} already listed
 * {@code prometheus}, but without the registry there was nothing behind the name.
 */
// Spring Boot's test support turns metric export off by default (a SimpleMeterRegistry is used instead),
// which is not what runs in production; this puts the real export autoconfiguration back.
@AutoConfigureObservability
@AutoConfigureMockMvc
class PrometheusIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;
    @Autowired SchedulerLock locks;

    @Test
    void VYB0909_AC1_thePrometheusRegistryIsTheOneTheApplicationRecordsInto() {
        assertThat(registry).isInstanceOf(PrometheusMeterRegistry.class);
    }

    @Test
    void VYB0909_AC1_theScrapeEndpointServesJvmAndApplicationMetricsToAnAuthenticatedCaller() throws Exception {
        String job = unique("prom");
        locks.runExclusive(job, Duration.ofMinutes(1), Duration.ZERO, () -> {});

        String body = mvc.perform(get("/actuator/prometheus").with(jwt()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("http_server_requests");
        assertThat(body).contains("vyoog_scheduler_runs_total").contains("job=\"" + job + "\"");
    }

    @Test
    void VYB0909_AC1_theScrapeEndpointIsNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void VYB0909_AC1_theLivenessProbeStaysOpenForTheContainerHealthcheck() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
