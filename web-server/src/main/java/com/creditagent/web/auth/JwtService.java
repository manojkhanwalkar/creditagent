package com.creditagent.web.auth;

import com.creditagent.web.auth.AuthExceptions.UnauthenticatedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * Issues and checks HS256 JWTs. A token names the user ({@code sub}) and the session ({@code jti});
 * the session lookup is what makes logout and the idle timeout take effect before {@code exp}.
 */
public class JwtService {

    /** A token the service accepted: whose it is and which session it belongs to. */
    public record Claims(String userId, String sessionId) {}

    private final byte[] secret;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(String secret, String issuer, Duration ttl, Clock clock) {
        this.secret = Objects.requireNonNull(secret).getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        this.issuer = Objects.requireNonNull(issuer);
        this.ttl = Objects.requireNonNull(ttl);
        this.clock = Objects.requireNonNull(clock);
    }

    public String issue(String userId, String sessionId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId)
                .jwtID(sessionId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign JWT", e);
        }
        return jwt.serialize();
    }

    /** @throws UnauthenticatedException if the token is malformed, forged, from another issuer or expired */
    public Claims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            // Only the algorithm we issue; never "none" or a key-confusion swap.
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                throw new UnauthenticatedException("Invalid token.");
            }
            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                throw new UnauthenticatedException("Invalid token.");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expires = claims.getExpirationTime();
            if (!issuer.equals(claims.getIssuer())
                    || expires == null
                    || !clock.instant().isBefore(expires.toInstant())
                    || claims.getSubject() == null
                    || claims.getJWTID() == null) {
                throw new UnauthenticatedException("Invalid or expired token.");
            }
            return new Claims(claims.getSubject(), claims.getJWTID());
        } catch (ParseException | JOSEException e) {
            throw new UnauthenticatedException("Invalid token.");
        }
    }
}
