package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final String SECRET = TestSecrets.of("jwt");
    final InMemoryRepositories.MutableClock clock = new InMemoryRepositories.MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
    final JwtService jwt = new JwtService(SECRET, "credit-agent", Duration.ofHours(1), clock);

    @Test
    void roundTrips() {
        String token = jwt.issue("avery", "session-1");
        assertThat(jwt.verify(token)).isEqualTo(new JwtService.Claims("avery", "session-1"));
    }

    @Test
    void expires() {
        String token = jwt.issue("avery", "session-1");
        clock.advance(Duration.ofHours(1));
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void rejectsAnotherKeyOrIssuer() {
        String foreign = new JwtService(TestSecrets.of("other"), "credit-agent", Duration.ofHours(1), clock)
                .issue("avery", "s");
        assertThatThrownBy(() -> jwt.verify(foreign)).isInstanceOf(UnauthenticatedException.class);

        String otherIssuer = new JwtService(SECRET, "someone-else", Duration.ofHours(1), clock).issue("avery", "s");
        assertThatThrownBy(() -> jwt.verify(otherIssuer)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void rejectsAlgNoneAndTampering() {
        String token = jwt.issue("avery", "session-1");
        String[] parts = token.split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> jwt.verify(noneHeader + "." + parts[1] + ".")).isInstanceOf(UnauthenticatedException.class);

        String otherBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"credit-agent\",\"sub\":\"admin\",\"jti\":\"x\",\"exp\":" + (clock.instant().getEpochSecond() + 60) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> jwt.verify(parts[0] + "." + otherBody + "." + parts[2]))
                .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> jwt.verify("garbage")).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void refusesAShortSecret() {
        assertThatThrownBy(() -> new JwtService("short", "i", Duration.ofHours(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
