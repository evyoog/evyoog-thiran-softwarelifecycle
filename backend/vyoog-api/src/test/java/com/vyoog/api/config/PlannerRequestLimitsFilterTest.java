package com.vyoog.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** VYB-0921 (D54): the body-size limit on the Planner's push endpoint, without a server. */
class PlannerRequestLimitsFilterTest {

    private static final int MAX = 2048;
    private final PlannerLimits limits = new PlannerLimits(MAX, 600, 15);
    private final PlannerRequestLimitsFilter filter = new PlannerRequestLimitsFilter(limits);

    /** A POST whose body has a declared length, or (chunked) none: the mock always knows its content's length, so the unknown case overrides it. */
    private static MockHttpServletRequest post(String path, byte[] body, boolean declareLength) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path) {
            @Override public long getContentLengthLong() { return declareLength ? super.getContentLengthLong() : -1; }
            @Override public int getContentLength() { return declareLength ? super.getContentLength() : -1; }
        };
        r.setRequestURI(path);
        r.setContent(body);
        return r;
    }

    private static byte[] bytes(int n) {
        return "x".repeat(n).getBytes(StandardCharsets.UTF_8);
    }

    private record Outcome(MockHttpServletResponse response, boolean passedOn, byte[] seenByHandler) {}

    private Outcome run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req.getInputStream().readAllBytes());
        filter.doFilter(request, response, chain);
        return new Outcome(response, seen.get() != null, seen.get());
    }

    @Test
    void VYB0921_AC5_aBodyWithinTheLimitReachesTheHandlerUnchangedIncludingExactlyTheLimit() throws Exception {
        byte[] exactly = bytes(MAX);
        Outcome o = run(post(PlannerRequestLimitsFilter.PATH, exactly, true));
        assertThat(o.passedOn()).isTrue();
        assertThat(o.seenByHandler()).isEqualTo(exactly);
        assertThat(o.response().getStatus()).isEqualTo(200);

        byte[] small = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(run(post(PlannerRequestLimitsFilter.PATH, small, false)).seenByHandler()).isEqualTo(small);
    }

    @Test
    void VYB0921_AC5_aBodyOverTheLimitIsRefusedAndNeverReachesTheHandlerWhetherOrNotItDeclaresItsLength() throws Exception {
        for (boolean declared : new boolean[] {true, false}) {
            Outcome o = run(post(PlannerRequestLimitsFilter.PATH, bytes(MAX + 1), declared));
            assertThat(o.passedOn()).as("declared length: " + declared).isFalse();
            assertThat(o.response().getStatus()).isEqualTo(413);
            assertThat(o.response().getContentType()).isEqualTo("application/problem+json");
            assertThat(o.response().getContentAsString()).contains("payload-too-large").contains(String.valueOf(MAX)).contains("Nothing was read or changed");
        }
    }

    @Test
    void VYB0921_AC5_aDeclaredLengthIsEnoughToRefuseWithoutReadingTheBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PlannerRequestLimitsFilter.PATH) {
            @Override public long getContentLengthLong() { return MAX + 1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                throw new AssertionError("the body must not be read when its declared length is already too large");
            }
        };
        request.setRequestURI(PlannerRequestLimitsFilter.PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> { throw new AssertionError("not passed on"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void VYB0921_AC5_onlyAPostToThePushEndpointIsLimited() throws Exception {
        assertThat(run(post("/api/v1/requirements", bytes(MAX * 10), true)).passedOn()).as("another path").isTrue();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", PlannerRequestLimitsFilter.PATH);
        get.setRequestURI(PlannerRequestLimitsFilter.PATH);
        java.util.concurrent.atomic.AtomicBoolean passed = new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(get, new MockHttpServletResponse(), (a, b) -> passed.set(true));
        assertThat(passed).as("a GET is not limited").isTrue();
    }

    @Test
    void VYB0921_AC5_theLimitIsReadOnEveryRequestSoItCanBeChanged() throws Exception {
        PlannerLimits changing = new PlannerLimits(1024, 600, 15);
        PlannerRequestLimitsFilter f = new PlannerRequestLimitsFilter(changing);
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(post(PlannerRequestLimitsFilter.PATH, bytes(1025), true), response, (a, b) -> { });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void VYB0921_AC5_nonsenseLimitsAreRefusedAtStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PlannerLimits(10, 600, 15)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PlannerLimits(2048, 0, 15)).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PlannerLimits(2048, 600, -1)).isInstanceOf(IllegalStateException.class);
        assertThat(new PlannerLimits(2048, 600, 0).maxTokenAgeMinutes()).as("0 turns the age check off").isZero();
    }
}
