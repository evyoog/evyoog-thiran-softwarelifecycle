package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * VYB-0909 (F33-F35): the container images and the nginx config are plain files that nothing else
 * exercises in CI, so a regression (a dropped {@code USER}, a removed limit) would not be noticed
 * until a deployment. These assert the lines that carry the hardening. They do not build an image.
 */
class DeploymentHardeningTest {

    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static String read(String relative) throws IOException {
        Path p = ROOT.resolve(relative);
        assertThat(p).as("expected %s relative to the repository root %s", relative, ROOT).exists();
        return Files.readString(p);
    }

    /** The text after the last FROM, i.e. the stage that becomes the image. */
    private static String finalStage(String dockerfile) {
        return dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));
    }

    @Test
    void VYB0909_AC5_theBackendImageRunsAsANonRootUserWithAFixedId() throws IOException {
        String runtime = finalStage(read("deployment/docker/backend.Dockerfile"));
        assertThat(runtime).contains("adduser").contains("-u 10001");
        assertThat(runtime).containsPattern("(?m)^USER vyoog(:vyoog)?$");
        assertThat(runtime.indexOf("USER vyoog")).as("USER comes before the entrypoint").isLessThan(runtime.indexOf("ENTRYPOINT"));
    }

    @Test
    void VYB0909_AC5_theBackendImageHasAHealthcheckOnTheLivenessProbe() throws IOException {
        String runtime = finalStage(read("deployment/docker/backend.Dockerfile"));
        assertThat(runtime).contains("HEALTHCHECK").contains("/actuator/health/liveness");
    }

    @Test
    void VYB0909_AC5_theFrontendImageIsTheUnprivilegedNginxOn8080WithAHealthcheck() throws IOException {
        String runtime = finalStage(read("deployment/docker/frontend.Dockerfile"));
        assertThat(runtime).contains("FROM nginxinc/nginx-unprivileged");
        assertThat(runtime).contains("EXPOSE 8080").contains("HEALTHCHECK").contains("/healthz");
        assertThat(runtime).doesNotContain("EXPOSE 80\n");
    }

    @Test
    void VYB0909_AC4_nginxListensOnTheUnprivilegedPortAndAnswersTheHealthcheck() throws IOException {
        String conf = read("deployment/nginx/frontend.conf");
        assertThat(conf).containsPattern("(?m)^\\s*listen 8080;");
        assertThat(conf).contains("location = /healthz");
    }

    @Test
    void VYB0909_AC4_nginxLimitsRequestRateConnectionsBodySizeAndTimeouts() throws IOException {
        String conf = read("deployment/nginx/frontend.conf");
        assertThat(conf).contains("limit_req_zone $binary_remote_addr zone=vyoog_api");
        assertThat(conf).contains("zone=vyoog_auth");
        assertThat(conf).contains("limit_conn_zone").contains("limit_conn vyoog_conn");
        assertThat(conf).contains("limit_req_status  429");
        assertThat(conf).containsPattern("client_max_body_size\\s+12m;");
        assertThat(conf).contains("client_body_timeout").contains("proxy_read_timeout");
    }

    @Test
    void VYB0909_AC4_theBodyLimitMatchesTheApisOwnMultipartCap() throws IOException {
        // nginx must refuse nothing the API would accept, and accept nothing the API will refuse late.
        assertThat(read("backend/vyoog-api/src/main/resources/application.yml")).contains("max-request-size: 12MB");
        assertThat(read("deployment/nginx/frontend.conf")).containsPattern("client_max_body_size\\s+12m;");
    }

    @Test
    void VYB0909_AC4_theNotificationStreamIsNotBufferedOrCutByTheNormalReadTimeout() throws IOException {
        String conf = read("deployment/nginx/frontend.conf");
        String stream = conf.substring(conf.indexOf("location = /api/v1/notifications/stream"));
        stream = stream.substring(0, stream.indexOf("\n    }"));
        assertThat(stream).contains("proxy_buffering off").contains("proxy_read_timeout    1h");
    }
}
