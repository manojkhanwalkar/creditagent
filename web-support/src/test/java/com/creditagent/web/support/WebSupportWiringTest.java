package com.creditagent.web.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebSupportWiringTest {

    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebSupportAutoConfiguration.class));

    @Test
    void everyWebServiceGetsTheInboundLimitFilter() {
        runner.run(context -> {
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(InboundLimitFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
            assertThat(registration.getOrder()).isEqualTo(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER);
        });
    }

    @Test
    void theLimitsAndPathsComeFromConfiguration() {
        runner.withPropertyValues("credit.inbound.url-patterns=/api/*,/internal/*", "credit.inbound.max-concurrent=4")
                .run(context -> {
                    assertThat(context.getBean(FilterRegistrationBean.class).getUrlPatterns())
                            .containsExactly("/api/*", "/internal/*");
                    assertThat(context.getBean(InboundLimitProperties.class).maxConcurrent()).isEqualTo(4);
                });
    }

    @Test
    void theFilterCanBeTurnedOff() {
        runner.withPropertyValues("credit.inbound.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void theSizeLimitRegistrationRunsRightAfterTheInboundLimits() {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = RequestSizeLimitFilter.registration(1024, "/api/*", "/mcp");
        assertThat(registration.getUrlPatterns()).containsExactly("/api/*", "/mcp");
        assertThat(registration.getOrder()).isEqualTo(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER + 1);
    }

    @Test
    void theLimitedStreamCountsSingleByteReadsAndDelegatesTheRest() throws Exception {
        // A chunked body: no declared length, so the limit is enforced while reading.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("abcd".getBytes());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new RequestSizeLimitFilter(3).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var in = req.getInputStream();
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            try {
                for (int i = 0; i < 3; i++) {
                    read.write(in.read());
                }
                assertThat(read.toString()).isEqualTo("abc");
                assertThat(in.isReady()).isTrue();
                assertThat(in.isFinished()).isFalse();
                in.read(); // the fourth byte passes the limit
            } catch (IOException e) {
                failure.set(e);
            }
            assertThatThrownBy(() -> in.setReadListener(new ReadListener() {
                public void onDataAvailable() {}
                public void onAllDataRead() {}
                public void onError(Throwable t) {}
            })).isInstanceOf(UnsupportedOperationException.class); // delegated: the mock stream has no async support
        });
        assertThat(failure.get()).hasMessageContaining("exceeds 3 bytes");
    }
}
