package com.creditagent.web.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.InMemoryRepositories.MutableClock;
import com.creditagent.web.auth.TestSecrets;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OAuthTokensTest {

    static final String ISSUER = "https://credit.example";
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T12:00:00Z"));
    final OAuthTokens tokens = new OAuthTokens(TestSecrets.of("oauth"), ISSUER, clock);

    OAuthTokens.Code code() {
        return new OAuthTokens.Code("client", "https://claude.ai/api/mcp/auth_callback", "challenge", "https://c/mcp",
                "credit", "avery", "123456789", "grant-1", clock.instant().plusSeconds(60));
    }

    OAuthTokens.Access access() {
        return new OAuthTokens.Access("avery", "grant-1", "123456789", "https://c/mcp", "credit", clock.instant().plusSeconds(3600));
    }

    @Test
    void clientIdsRoundTripAndCannotBeAltered() {
        OAuthTokens.Client client = new OAuthTokens.Client(List.of("https://claude.ai/api/mcp/auth_callback"), "Claude");
        String id = tokens.issueClientId(client);
        assertThat(tokens.client(id)).contains(client);

        String[] parts = id.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];
        assertThat(tokens.client(forged)).isEmpty();
        assertThat(tokens.client("not-a-jwt")).isEmpty();
        assertThat(tokens.client("")).isEmpty();
        assertThat(tokens.client(null)).isEmpty();
        // Signed by another server's key.
        assertThat(new OAuthTokens(TestSecrets.of("other"), ISSUER, clock).client(id)).isEmpty();
        // The right key but another issuer.
        assertThat(new OAuthTokens(TestSecrets.of("oauth"), "https://elsewhere", clock).client(id)).isEmpty();
    }

    @Test
    void aClientIdWithoutRedirectUrisOrWithAnotherAlgorithmIsRefused() throws Exception {
        byte[] key = OAuthTokens.derive(TestSecrets.of("oauth").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "credit-agent oauth client-id signing");
        JWTClaimsSet noUris = new JWTClaimsSet.Builder().issuer(ISSUER).claim("use", "client").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), noUris);
        jwt.sign(new MACSigner(key));
        assertThat(tokens.client(jwt.serialize())).isEmpty();

        JWTClaimsSet withUris = new JWTClaimsSet.Builder().issuer(ISSUER).claim("use", "client")
                .claim("redirect_uris", List.of("https://claude.ai/api/mcp/auth_callback")).build();
        SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), withUris);
        hs512.sign(new MACSigner(java.util.Arrays.copyOf(key, 64)));
        assertThat(tokens.client(hs512.serialize())).isEmpty();
    }

    @Test
    void codesAndAccessTokensRoundTripAndHideTheSsn() {
        String code = tokens.issueCode(code());
        assertThat(code.split("\\.")).hasSize(5);
        assertThat(code).doesNotContain("123456789");
        assertThat(tokens.code(code)).contains(code());

        String token = tokens.issueAccess(access());
        assertThat(tokens.access(token)).contains(access());
        assertThat(token).doesNotContain("123456789");
    }

    @Test
    void aCodeIsNotAnAccessTokenNorTheReverse() {
        assertThat(tokens.access(tokens.issueCode(code()))).isEmpty();
        assertThat(tokens.code(tokens.issueAccess(access()))).isEmpty();
        assertThat(tokens.code(tokens.issueClientId(new OAuthTokens.Client(List.of("http://localhost/cb"), "x")))).isEmpty();
    }

    @Test
    void expiredOrForeignTokensAreRefused() {
        String token = tokens.issueAccess(access());
        assertThat(new OAuthTokens(TestSecrets.of("other"), ISSUER, clock).access(token)).isEmpty();
        assertThat(new OAuthTokens(TestSecrets.of("oauth"), "https://elsewhere", clock).access(token)).isEmpty();
        assertThat(tokens.access("a.b.c.d.e")).isEmpty();
        assertThat(tokens.access(null)).isEmpty();
        assertThat(tokens.access(" ")).isEmpty();

        clock.advance(Duration.ofHours(1));
        assertThat(tokens.access(token)).isEmpty();
    }

    @Test
    void anAccessTokenWithoutExactlyOneAudienceIsRefused() {
        OAuthTokens.Access noAudience = new OAuthTokens.Access("avery", "g", "123456789", null, "credit",
                clock.instant().plusSeconds(60));
        assertThat(tokens.access(tokens.issueAccess(noAudience))).isEmpty();
    }

    @Test
    void redactsWhatItPrints() {
        assertThat(code().toString()).doesNotContain("123456789").contains("avery");
        assertThat(access().toString()).doesNotContain("123456789");
    }

    @Test
    void refusesAShortKey() {
        assertThatThrownBy(() -> new OAuthTokens("short", ISSUER, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
