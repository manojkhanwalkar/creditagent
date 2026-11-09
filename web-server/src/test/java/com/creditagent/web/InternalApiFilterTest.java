package com.creditagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalApiFilterTest {

    static final String TOKEN = "internal-test-token-".repeat(2);

    static MockHttpServletResponse call(InternalApiFilter filter, String uri, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void withoutAConfiguredTokenTheInternalApiIsOff() throws Exception {
        assertThat(call(new InternalApiFilter(""), "/internal/v1/report-locks/lookup", "Bearer anything").getStatus())
                .isEqualTo(404);
    }

    @Test
    void theRightTokenIsNeeded() throws Exception {
        InternalApiFilter filter = new InternalApiFilter(TOKEN);
        assertThat(call(filter, "/internal/v1/report-locks/lookup", "Bearer " + TOKEN).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/internal/v1/report-locks/lookup", "bearer " + TOKEN).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/internal/v1/report-locks/lookup", null).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/internal/v1/report-locks/lookup", "Bearer wrong").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/internal/v1/report-locks/lookup", "Basic " + TOKEN).getStatus()).isEqualTo(401);
    }

    @Test
    void encodedOrMixedCasePathsCannotSkipTheCheck() throws Exception {
        InternalApiFilter filter = new InternalApiFilter(TOKEN);
        for (String path : new String[] {"/INTERNAL/v1/report-locks/lookup", "/%69nternal/v1/report-locks/lookup",
                "/internal;x=1/v1/report-locks/lookup"}) {
            assertThat(call(filter, path, null).getStatus()).as(path).isEqualTo(401);
        }
        // Other paths are not this filter's business.
        assertThat(call(filter, "/api/v1/credit-score", null).getStatus()).isEqualTo(200);
    }

    @Test
    void aShortTokenIsRefusedAtStartUp() {
        assertThatThrownBy(() -> new InternalApiFilter("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
