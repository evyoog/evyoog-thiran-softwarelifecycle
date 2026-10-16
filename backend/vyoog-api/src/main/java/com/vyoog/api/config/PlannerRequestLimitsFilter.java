package com.vyoog.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * VYB-0921 (D54): a request to the Planner's push endpoint is refused with 413 before anything reads it if its body is larger than
 * {@link PlannerLimits#maxBodyBytes()}: by its declared length when it has one, and by actually reading up to one byte past the limit when
 * it has none (chunked), so a caller cannot get a large body past the check by not declaring it. A body within the limit is read once
 * here and handed on unchanged.
 */
@Component
public class PlannerRequestLimitsFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/planner/requirements";

    private final PlannerLimits limits;

    public PlannerRequestLimitsFilter(PlannerLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int max = limits.maxBodyBytes();
        if (request.getContentLengthLong() > max) {
            tooLarge(response, max);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            tooLarge(response, max);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static void tooLarge(HttpServletResponse response, int max) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"https://vyoog.dev/problems/payload-too-large\",\"title\":\"Payload too large\",\"status\":413,"
            + "\"detail\":\"The request body is larger than " + max + " bytes, which is the most this endpoint accepts. Nothing was read or changed.\"}");
    }

    /** The body already read, served again to whoever reads it next. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
