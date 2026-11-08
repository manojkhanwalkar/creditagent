package com.creditagent.score;

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
}
