package com.creditagent.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class RetryingInterceptorTest {

    static final RetrySettings FAST = new RetrySettings(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(4), Duration.ZERO);
    static final String URL = "http://svc.test/api/v1/x";

    MockRestServiceServer server;

    RestClient client(RetrySettings settings, boolean idempotent) {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new RetryingInterceptor(settings, idempotent));
        server = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }

    String post(RestClient client) {
        return client.post().uri(URL).contentType(MediaType.APPLICATION_JSON).body("{}").retrieve().body(String.class);
    }

    @Test
    void recoverableStatusesAreRetriedUntilTheyPass() {
        RestClient client = client(FAST, true);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(URL)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(post(client)).isEqualTo("ok");
        server.verify();
    }

    @Test
    void theLastFailureReachesTheCallerAfterMaxAttempts() {
        RestClient client = client(FAST, true);
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        server.verify();
    }

    @Test
    void aRecoverableStatusOnTheRealLastTryReachesTheCallerAsAnHttpError() {
        // Regression: tries that failed with I/O errors were not counted, so this 503 was thrown as
        // an internal RetryableStatusException that the service clients do not catch.
        RestClient client = client(FAST, true);
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("connection reset")));
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("connection reset")));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        server.verify();
    }

    @Test
    void clientErrorsAreNotRetried() {
        RestClient client = client(FAST, true);
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpClientErrorException.BadRequest.class);
        // A 500 is a bug, not a blip: retrying would only repeat it.
        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpServerErrorException.InternalServerError.class);
        server.verify();
    }

    @Test
    void ioFailuresAreRetriedForIdempotentCalls() {
        RestClient client = client(FAST, true);
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(URL)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(post(client)).isEqualTo("ok");
        server.verify();
    }

    @Test
    void nonIdempotentCallsRetryOnlyWhatProvesTheRequestNeverArrived() {
        RestClient client = client(FAST, false);
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        assertThat(post(client)).isEqualTo("ok");
        server.verify();

        // A read timeout may mean the provider did the work: never repeat it.
        // A 502 may also come after the work was done.
        RestClient again = client(FAST, false);
        server.expect(times(1), requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> post(again)).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> post(again)).isInstanceOf(HttpServerErrorException.BadGateway.class);
        server.verify();
    }

    @Test
    void oneAttemptMeansNoRetries() {
        RestClient client = client(RetrySettings.none(), true);
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void waitsGrowExponentially() {
        // No jitter: waits of 40 ms then 80 ms before the 2nd and 3rd tries.
        RetrySettings slow = new RetrySettings(3, Duration.ofMillis(40), 2.0, Duration.ofSeconds(1), Duration.ZERO);
        AtomicInteger calls = new AtomicInteger();
        long start = System.nanoTime();
        assertThatThrownBy(() -> Retries.call(slow, t -> true, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        })).isInstanceOf(IllegalStateException.class);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(calls).hasValue(3);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(120);
    }

    @Test
    void jitterKeepsWaitsWithinItsBounds() {
        // 50 ms ± 40 ms: one retry, so the wait lies between 10 ms and 90 ms.
        RetrySettings jittery = new RetrySettings(2, Duration.ofMillis(50), 2.0, Duration.ofSeconds(1), Duration.ofMillis(40));
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> Retries.call(jittery, t -> true, () -> {
                throw new IllegalStateException("down");
            })).isInstanceOf(IllegalStateException.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).isBetween(9L, 400L);
        }
    }

    @Test
    void settingsAreValidatedAndDefaulted() {
        assertThat(RetrySettings.DEFAULTS.maxAttempts()).isEqualTo(3);
        assertThat(RetrySettings.DEFAULTS.initialDelay()).isEqualTo(Duration.ofMillis(100));
        assertThatThrownBy(() -> new RetrySettings(0, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySettings(3, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoverableFailuresAreClassifiedThroughTheCauseChain() {
        RuntimeException wrapped = new RuntimeException(new java.io.UncheckedIOException(new ConnectException("x")));
        assertThat(Retries.transientFailure(wrapped)).isTrue();
        assertThat(Retries.notDelivered(wrapped)).isTrue();
        assertThat(Retries.notDelivered(new RuntimeException(new SocketTimeoutException()))).isFalse();
        assertThat(Retries.transientFailure(new IllegalArgumentException("bug"))).isFalse();
        assertThat(Retries.notDelivered(new Retries.RetryableStatusException(503))).isTrue();
        assertThat(Retries.notDelivered(new Retries.RetryableStatusException(504))).isFalse();
    }

    @Test
    void retryAfterIsWaitedOutBeforeTheNextTry() {
        RetrySettings patient = new RetrySettings(3, Duration.ofMillis(1), 2.0, Duration.ofSeconds(2), Duration.ZERO);
        RestClient client = client(patient, true);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "1"));
        server.expect(requestTo(URL)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        long start = System.nanoTime();
        assertThat(post(client)).isEqualTo("ok");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(990));
        server.verify();
    }

    @Test
    void aRetryAfterLongerThanTheMaxDelayStopsRetryingAtOnce() {
        RestClient client = client(FAST, true); // max delay 4 ms
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "30"));

        long start = System.nanoTime();
        assertThatThrownBy(() -> post(client)).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        server.verify();
    }

    @Test
    void retryAfterIsParsedAsSecondsOnly() {
        assertThat(Retries.retryAfter("2")).isEqualTo(Duration.ofSeconds(2));
        assertThat(Retries.retryAfter(" 0 ")).isEqualTo(Duration.ZERO);
        assertThat(Retries.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(Retries.retryAfter("-1")).isNull();
        assertThat(Retries.retryAfter(null)).isNull();
    }

    @Test
    void worstCaseCountsEveryTryAndEveryWait() {
        // 3 × 5 s + (100 + 50) ms + (200 + 50) ms
        assertThat(RetrySettings.DEFAULTS.worstCase(Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(15_400));
        assertThat(RetrySettings.none().worstCase(Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(5));
    }
}
