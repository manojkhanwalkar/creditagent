package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

    final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10);

    @Test
    void rejectsDeclaredOversizedBodyWith413WithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scores");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(called.get()).isFalse();
    }

    @Test
    void passesBodiesWithinTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scores");
        request.setContent("0123456789".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> body = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> body.set(req.getInputStream().readAllBytes()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body.get()).hasSize(10);
    }

    @Test
    void cutsOffBodiesWithoutAContentLengthOnceTheyPassTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scores") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked
            }
        };
        request.setContent(new byte[50]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds 10 bytes");
    }

    @Test
    void refusesANonPositiveLimit() {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theReaderIsCutOffToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scores") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked
            }
        };
        request.setContent("x".repeat(50).getBytes());
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set((HttpServletRequest) req));

        assertThat(seen.get().getReader()).isSameAs(seen.get().getReader());
        assertThatThrownBy(() -> seen.get().getReader().lines().count())
                .hasRootCauseInstanceOf(IOException.class).hasRootCauseMessage("Request body exceeds 10 bytes");
    }

    @Test
    void theReaderReadsBodiesWithinTheLimitInTheirEncoding() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scores");
        request.setCharacterEncoding("ISO-8859-1");
        request.setContent(new byte[] {(byte) 0xE9});
        AtomicReference<String> body = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> body.set(req.getReader().readLine()));
        assertThat(body.get()).isEqualTo("\u00e9");
    }
}
