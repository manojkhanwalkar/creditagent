package com.creditagent.web.oauth;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The OAuth server's self-contained artefacts. Nothing is stored for them, so the server stays
 * stateless; the grant row written at token exchange is the one piece of state (see {@link OAuthService}).
 *
 * <ul>
 *   <li><b>Client ids</b> (dynamic client registration) are HS256-signed JWTs holding the client's
 *       redirect URIs and name: tamper-proof, but readable, since nothing in them is secret.
 *   <li><b>Authorization codes and access tokens</b> are encrypted JWTs (JWE, {@code dir} +
 *       {@code A256GCM}). They carry the user's SSN, which the connector needs to fetch the user's
 *       report, so they must be unreadable to the client (Claude) holding them.
 * </ul>
 *
 * <p>The signing and encryption keys are derived separately from {@code OAUTH_TOKEN_KEY}
 * (HMAC-SHA256 over a label), so no key is used for two purposes. Every artefact names its use
 * ({@code use}) and the issuer, so a code can never be presented as an access token, nor the
 * reverse.
 */
public final class OAuthTokens {

    static final String USE = "use";
    static final String CLIENT = "client";
    static final String CODE = "code";
    static final String ACCESS = "access";

    /** A registered client, as its client id records it. */
    public record Client(List<String> redirectUris, String clientName) {
        public Client {
            redirectUris = List.copyOf(redirectUris);
        }
    }

    /** What an authorization code stands for. {@code grantId} is the single-use key of the exchange. */
    public record Code(
            String clientId, String redirectUri, String codeChallenge, String resource, String scope,
            String userId, String ssnDigits, String grantId, Instant expiresAt) {
        @Override
        public String toString() {
            return "Code[userId=" + userId + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** What an access token stands for. */
    public record Access(
            String userId, String grantId, String ssnDigits, String resource, String scope, Instant expiresAt) {
        @Override
        public String toString() {
            return "Access[userId=" + userId + ", expiresAt=" + expiresAt + "]";
        }
    }

    private static final JWEHeader JWE_HEADER = new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM);

    private final byte[] signingKey;
    private final byte[] encryptionKey;
    private final String issuer;
    private final Clock clock;

    public OAuthTokens(String secret, String issuer, Clock clock) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length() < OAuthProperties.MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("OAuth token key must be at least " + OAuthProperties.MIN_KEY_LENGTH + " characters");
        }
        byte[] master = secret.getBytes(StandardCharsets.UTF_8);
        this.signingKey = derive(master, "credit-agent oauth client-id signing");
        this.encryptionKey = derive(master, "credit-agent oauth token encryption");
        this.issuer = Objects.requireNonNull(issuer);
        this.clock = Objects.requireNonNull(clock);
    }

    static byte[] derive(byte[] master, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    // ------------------------------------------------------------------ client ids

    public String issueClientId(Client client) {
        JWTClaimsSet claims = base(CLIENT)
                .claim("redirect_uris", client.redirectUris())
                .claim("client_name", client.clientName())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the client id", e);
        }
        return jwt.serialize();
    }

    /** The client a client id describes, or empty if it was not issued here (or was altered). */
    public Optional<Client> client(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(clientId);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(signingKey))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ours(claims, CLIENT)) {
                return Optional.empty();
            }
            List<String> uris = claims.getStringListClaim("redirect_uris");
            if (uris == null || uris.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Client(uris, claims.getStringClaim("client_name")));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ codes and tokens

    public String issueCode(Code code) {
        return encrypt(base(CODE)
                .claim("client_id", code.clientId())
                .claim("redirect_uri", code.redirectUri())
                .claim("code_challenge", code.codeChallenge())
                .claim("resource", code.resource())
                .claim("scope", code.scope())
                .subject(code.userId())
                .claim("ssn", code.ssnDigits())
                .jwtID(code.grantId())
                .expirationTime(Date.from(code.expiresAt()))
                .build());
    }

    /** The code's content, or empty if it is not a live code issued here. */
    public Optional<Code> code(String code) {
        return decrypt(code, CODE).map(c -> new Code(
                text(c, "client_id"), text(c, "redirect_uri"), text(c, "code_challenge"), text(c, "resource"),
                text(c, "scope"), c.getSubject(), text(c, "ssn"), c.getJWTID(), c.getExpirationTime().toInstant()));
    }

    public String issueAccess(Access access) {
        return encrypt(base(ACCESS)
                .subject(access.userId())
                .jwtID(access.grantId())
                .claim("ssn", access.ssnDigits())
                .audience(access.resource())
                .claim("scope", access.scope())
                .expirationTime(Date.from(access.expiresAt()))
                .build());
    }

    /** The token's content, or empty if it is not a live access token issued here. */
    public Optional<Access> access(String token) {
        return decrypt(token, ACCESS).flatMap(c -> c.getAudience().size() != 1
                ? Optional.empty()
                : Optional.of(new Access(c.getSubject(), c.getJWTID(), text(c, "ssn"), c.getAudience().getFirst(),
                        text(c, "scope"), c.getExpirationTime().toInstant())));
    }

    private JWTClaimsSet.Builder base(String use) {
        return new JWTClaimsSet.Builder().issuer(issuer).issueTime(Date.from(clock.instant())).claim(USE, use);
    }

    private boolean ours(JWTClaimsSet claims, String use) {
        return issuer.equals(claims.getIssuer()) && use.equals(claims.getClaim(USE));
    }

    private String encrypt(JWTClaimsSet claims) {
        EncryptedJWT jwt = new EncryptedJWT(JWE_HEADER, claims);
        try {
            jwt.encrypt(new DirectEncrypter(encryptionKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not encrypt the token", e);
        }
        return jwt.serialize();
    }

    /**
     * Decrypts and checks a code or access token: algorithm, issuer, use, expiry, and the claims every
     * one of them must have.
     */
    private Optional<JWTClaimsSet> decrypt(String token, String use) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            EncryptedJWT jwt = EncryptedJWT.parse(token);
            if (!JWEAlgorithm.DIR.equals(jwt.getHeader().getAlgorithm())
                    || !EncryptionMethod.A256GCM.equals(jwt.getHeader().getEncryptionMethod())) {
                return Optional.empty();
            }
            jwt.decrypt(new DirectDecrypter(encryptionKey));
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expires = claims.getExpirationTime();
            if (!ours(claims, use)
                    || expires == null
                    || !clock.instant().isBefore(expires.toInstant())
                    || claims.getSubject() == null
                    || claims.getJWTID() == null
                    || text(claims, "ssn") == null) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ParseException | JOSEException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static String text(JWTClaimsSet claims, String name) {
        return claims.getClaim(name) instanceof String value ? value : null;
    }
}
