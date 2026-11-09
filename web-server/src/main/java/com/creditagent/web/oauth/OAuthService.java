package com.creditagent.web.oauth;

import com.creditagent.common.Ssn;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AuthenticatedUser;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.1 authorization code flow with PKCE, for public clients registered dynamically (RFC 7591)
 * such as Claude's connectors:
 *
 * <ol>
 *   <li>{@link #register}: the client sends its redirect URIs and gets a client id (no secret).
 *   <li>{@link #authorizationRequest} + {@link #approve}: the user signs in on this server's form,
 *       with their password <em>and</em> their SSN, and consents. The client gets a code.
 *   <li>{@link #exchange}: the client trades the code and its PKCE verifier for an access token,
 *       bound to the connector's resource URL (RFC 8707). The exchange first marks the code used
 *       (a conditional write that lasts as long as the code), so each code works once, even after
 *       its grant is revoked; then it opens the grant row the token is checked against.
 *   <li>{@link #introspect}: the connector (an internal caller) turns the token back into the user
 *       and their SSN, as long as the grant is open.
 * </ol>
 *
 * <p>There are no refresh tokens: when the access token expires the user reconnects. {@link #revoke}
 * ends a grant at once.
 */
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    public static final String SCOPE = "credit";
    static final Pattern CODE_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    static final Pattern CODE_VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
    static final int MAX_REDIRECT_URIS = 5;
    static final int MAX_URI_LENGTH = 512;
    static final int MAX_CLIENT_NAME_LENGTH = 100;
    static final int MAX_STATE_LENGTH = 1024;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** An OAuth error answered as JSON {@code {"error": …, "error_description": …}}. */
    public static class OAuthException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String error;

        OAuthException(int status, String error, String description) {
            super(description, null, false, false);
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    /**
     * A bad authorization request. If the client and redirect URI could be trusted, the error goes back
     * to the client ({@link #redirect()} is set); otherwise it is shown to the user and nothing is
     * redirected, so the server is never an open redirector.
     */
    public static class AuthorizeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient URI redirect;

        AuthorizeException(String message, URI redirect) {
            super(message, null, false, false);
            this.redirect = redirect;
        }

        public Optional<URI> redirect() {
            return Optional.ofNullable(redirect);
        }
    }

    /** A checked authorization request, carried through the sign-in form. */
    public record AuthorizationRequest(
            String clientId, String clientName, String redirectUri, String state, String codeChallenge,
            String resource, String scope) {}

    /** What the token endpoint answers. */
    public record TokenResponse(String accessToken, long expiresIn, String scope) {}

    /** An active access token, for the connector. The SSN never leaves the internal network. */
    public record Introspection(AuthenticatedUser user, Ssn ssn, String resource, String scope, Instant expiresAt) {
        @Override
        public String toString() {
            return "Introspection[userId=" + user.userId() + "]";
        }
    }

    private final OAuthProperties properties;
    private final OAuthTokens tokens;
    private final AuthService auth;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthService(OAuthProperties properties, AuthService auth, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.auth = Objects.requireNonNull(auth);
        this.clock = Objects.requireNonNull(clock);
        this.tokens = properties.enabled() ? new OAuthTokens(properties.tokenKey(), properties.issuer(), clock) : null;
    }

    public boolean enabled() {
        return tokens != null;
    }

    public String issuer() {
        return properties.issuer();
    }

    /** RFC 8414 authorization server metadata. */
    public Map<String, Object> metadata() {
        String base = properties.issuer();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", base);
        metadata.put("authorization_endpoint", base + "/oauth/authorize");
        metadata.put("token_endpoint", base + "/oauth/token");
        metadata.put("registration_endpoint", base + "/oauth/register");
        metadata.put("revocation_endpoint", base + "/oauth/revoke");
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("scopes_supported", List.of(SCOPE));
        metadata.put("authorization_response_iss_parameter_supported", true);
        return metadata;
    }

    // ------------------------------------------------------------------ registration

    /**
     * Registers a public client. The requested auth method and grant types are not negotiated: the
     * answer states what this server supports ({@code none}, {@code authorization_code}).
     */
    public Map<String, Object> register(List<String> redirectUris, String clientName) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthException(400, "invalid_redirect_uri", "Register between 1 and " + MAX_REDIRECT_URIS + " redirect URIs.");
        }
        for (String uri : redirectUris) {
            if (!redirectAllowed(uri)) {
                throw new OAuthException(400, "invalid_redirect_uri", "This redirect URI is not allowed.");
            }
        }
        String name = clientName == null || clientName.isBlank() ? "An application" : clientName.strip();
        if (name.length() > MAX_CLIENT_NAME_LENGTH) {
            throw new OAuthException(400, "invalid_client_metadata", "client_name is too long.");
        }
        List<String> uris = List.copyOf(redirectUris);
        String clientId = tokens.issueClientId(new OAuthTokens.Client(uris, name));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("client_id", clientId);
        answer.put("client_id_issued_at", clock.instant().getEpochSecond());
        answer.put("client_name", name);
        answer.put("redirect_uris", uris);
        answer.put("token_endpoint_auth_method", "none");
        answer.put("grant_types", List.of("authorization_code"));
        answer.put("response_types", List.of("code"));
        answer.put("scope", SCOPE);
        return answer;
    }

    /**
     * An exactly registered Claude callback, or (if allowed) a loopback URI as desktop clients use.
     * Never a fragment, user info or a non-HTTPS remote host.
     */
    boolean redirectAllowed(String uri) {
        if (uri == null || uri.length() > MAX_URI_LENGTH) {
            return false;
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            return false;
        }
        if (!parsed.isAbsolute() || parsed.getRawFragment() != null || parsed.getRawUserInfo() != null) {
            return false;
        }
        if (properties.allowedRedirectUris().contains(uri)) {
            return true;
        }
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        return properties.allowLoopbackRedirects()
                && "http".equals(parsed.getScheme())
                && LOOPBACK_HOSTS.contains(host);
    }

    // ------------------------------------------------------------------ authorization

    /**
     * Checks an authorization request (from the query of the GET, or the hidden fields of the POST).
     *
     * @throws AuthorizeException if it cannot be served
     */
    public AuthorizationRequest authorizationRequest(Map<String, String> params) {
        String clientId = params.get("client_id");
        OAuthTokens.Client client = tokens.client(clientId)
                .orElseThrow(() -> new AuthorizeException("This application is not registered with CreditAgent.", null));
        String redirectUri = params.get("redirect_uri");
        if (redirectUri == null && client.redirectUris().size() == 1) {
            redirectUri = client.redirectUris().getFirst();
        }
        if (redirectUri == null || !client.redirectUris().contains(redirectUri) || !redirectAllowed(redirectUri)) {
            throw new AuthorizeException("The application's return address is not one it registered.", null);
        }
        // From here on, errors go back to the client.
        String state = params.get("state");
        if (state != null && state.length() > MAX_STATE_LENGTH) {
            throw redirectError(redirectUri, null, "invalid_request", "state is too long");
        }
        if (!"code".equals(params.get("response_type"))) {
            throw redirectError(redirectUri, state, "unsupported_response_type", "Only response_type=code is supported");
        }
        String challenge = params.get("code_challenge");
        if (challenge == null || !CODE_CHALLENGE.matcher(challenge).matches()
                || !"S256".equals(params.get("code_challenge_method"))) {
            throw redirectError(redirectUri, state, "invalid_request", "PKCE with code_challenge_method=S256 is required");
        }
        String resource = params.get("resource");
        resource = resource == null || resource.isBlank() ? properties.resource() : OAuthProperties.withoutTrailingSlash(resource);
        if (!resource.equals(properties.resource())) {
            throw redirectError(redirectUri, state, "invalid_target", "Unknown resource");
        }
        String scope = params.get("scope");
        if (scope != null && !scope.isBlank()
                && !Arrays.stream(scope.strip().split("\\s+")).allMatch(SCOPE::equals)) {
            throw redirectError(redirectUri, state, "invalid_scope", "The only scope is " + SCOPE);
        }
        return new AuthorizationRequest(clientId, client.clientName(), redirectUri, state, challenge, resource, SCOPE);
    }

    /** The user signed in and allowed access: a redirect carrying a single-use code. */
    public URI approve(AuthorizationRequest request, AuthService.VerifiedOwner owner, Ssn ssn) {
        Instant expires = clock.instant().plus(properties.codeTtl());
        String code = tokens.issueCode(new OAuthTokens.Code(
                request.clientId(), request.redirectUri(), request.codeChallenge(), request.resource(),
                request.scope(), owner.userId(), ssn.digits(), randomId(), expires));
        log.info("User {} approved a connector", owner.userId());
        return redirect(request.redirectUri(), request.state(), Map.of("code", code));
    }

    /** The user said no. */
    public URI deny(AuthorizationRequest request) {
        return redirect(request.redirectUri(), request.state(),
                Map.of("error", "access_denied", "error_description", "The user denied access"));
    }

    private AuthorizeException redirectError(String redirectUri, String state, String error, String description) {
        return new AuthorizeException(description,
                redirect(redirectUri, state, Map.of("error", error, "error_description", description)));
    }

    private URI redirect(String redirectUri, String state, Map<String, String> params) {
        StringBuilder uri = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?');
        Map<String, String> all = new LinkedHashMap<>(params);
        if (state != null) {
            all.put("state", state);
        }
        all.put("iss", properties.issuer()); // RFC 9207: tells the client which server answered
        String query = String.join("&", all.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .toList());
        return URI.create(uri.append(query).toString());
    }

    // ------------------------------------------------------------------ token

    /** The token endpoint: {@code grant_type=authorization_code} only. */
    public TokenResponse exchange(Map<String, String> params) {
        if (!"authorization_code".equals(params.get("grant_type"))) {
            throw new OAuthException(400, "unsupported_grant_type", "Only authorization_code is supported");
        }
        String clientId = params.get("client_id");
        if (clientId == null || clientId.isBlank()) {
            throw new OAuthException(401, "invalid_client", "client_id is required");
        }
        OAuthTokens.Code code = tokens.code(params.get("code"))
                .orElseThrow(() -> new OAuthException(400, "invalid_grant", "The code is invalid or has expired"));
        if (!code.clientId().equals(clientId) || !code.redirectUri().equals(params.get("redirect_uri"))) {
            throw new OAuthException(400, "invalid_grant", "The code was issued to another client or redirect URI");
        }
        if (!pkceMatches(params.get("code_verifier"), code.codeChallenge())) {
            throw new OAuthException(400, "invalid_grant", "code_verifier does not match");
        }
        String resource = params.get("resource");
        if (resource != null && !resource.isBlank()
                && !OAuthProperties.withoutTrailingSlash(resource).equals(code.resource())) {
            throw new OAuthException(400, "invalid_target", "The code was issued for another resource");
        }
        Ssn ssn = Ssn.parse(code.ssnDigits());
        Instant expires = clock.instant().plus(properties.accessTokenTtl());
        if (!auth.openGrant(code.grantId(), code.userId(), ssn, code.expiresAt(), expires)) {
            // A code used twice may have been stolen: end the grant the first use opened (RFC 9700).
            auth.revokeGrant(code.grantId());
            log.warn("Authorization code reused; its grant was revoked");
            throw new OAuthException(400, "invalid_grant", "The code has already been used");
        }
        String token = tokens.issueAccess(new OAuthTokens.Access(
                code.userId(), code.grantId(), code.ssnDigits(), code.resource(), code.scope(), expires));
        return new TokenResponse(token, properties.accessTokenTtl().toSeconds(), code.scope());
    }

    static boolean pkceMatches(String verifier, String challenge) {
        if (verifier == null || !CODE_VERIFIER.matcher(verifier).matches()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            byte[] expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ------------------------------------------------------------------ tokens in use

    /** RFC 7009: ends the token's grant. Unknown or expired tokens are ignored, as the RFC asks. */
    public void revoke(String token) {
        tokens.access(token).ifPresent(access -> auth.revokeGrant(access.grantId()));
    }

    /** The token's user and SSN, if it is a live access token whose grant is still open. */
    public Optional<Introspection> introspect(String token) {
        return tokens.access(token).flatMap(access -> auth.activeGrant(access.grantId(), access.userId())
                .map(user -> new Introspection(
                        user, Ssn.parse(access.ssnDigits()), access.resource(), access.scope(), access.expiresAt())));
    }

    private String randomId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
