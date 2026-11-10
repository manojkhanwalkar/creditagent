package com.creditagent.web.oauth;

import com.creditagent.common.InvalidSsnException;
import com.creditagent.common.Ssn;
import com.creditagent.web.RequestPaths;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.oauth.OAuthService.AuthorizationRequest;
import com.creditagent.web.oauth.OAuthService.AuthorizeException;
import com.creditagent.web.oauth.OAuthService.OAuthException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The OAuth 2.1 endpoints a Claude connector uses to act for a CreditAgent user. See
 * {@link OAuthService} for the flow. Every endpoint answers 404 while OAuth is off (no
 * {@code OAUTH_TOKEN_KEY}).
 */
@RestController
class OAuthController {

    static final String METADATA = "/.well-known/oauth-authorization-server";
    static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    private final OAuthService oauth;
    private final AuthService auth;

    OAuthController(OAuthService oauth, AuthService auth) {
        this.oauth = oauth;
        this.auth = auth;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegistrationRequest(
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("client_name") String clientName) {}

    @GetMapping(path = METADATA, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> metadata() {
        requireEnabled();
        return oauth.metadata();
    }

    @PostMapping(path = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
        requireEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(oauth.register(request.redirectUris(), request.clientName()));
    }

    /** Checks the request, then serves the fixed consent page (filled in by the browser from the URL). */
    @GetMapping(path = RequestPaths.OAUTH_AUTHORIZE)
    ResponseEntity<String> authorize(@RequestParam MultiValueMap<String, String> params, HttpServletResponse response) {
        requireEnabled();
        try {
            AuthorizationRequest request = oauth.authorizationRequest(single(params));
            // Replaces the site-wide CSP that SecurityHeadersFilter set: see AuthorizePage#csp.
            response.setHeader("Content-Security-Policy", AuthorizePage.csp(request.redirectUri()));
            return ResponseEntity.ok().contentType(HTML).body(AuthorizePage.consent());
        } catch (AuthorizeException e) {
            return failure(e);
        }
    }

    @PostMapping(path = RequestPaths.OAUTH_AUTHORIZE, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<String> decide(@RequestParam MultiValueMap<String, String> params) {
        requireEnabled();
        AuthorizationRequest request;
        Map<String, String> form;
        try {
            form = single(params);
            request = oauth.authorizationRequest(form);
        } catch (AuthorizeException e) {
            return failure(e);
        }
        if (!"allow".equals(form.get("decision"))) {
            return redirect(oauth.deny(request));
        }
        Ssn ssn;
        try {
            ssn = Ssn.parse(form.get("ssn"));
        } catch (InvalidSsnException e) {
            return signInFailed(request);
        }
        Optional<AuthService.VerifiedOwner> owner = auth.verifyOwner(form.get("userId"), form.get("password"), ssn);
        if (owner.isEmpty()) {
            return signInFailed(request);
        }
        return redirect(oauth.approve(request, owner.get(), ssn));
    }

    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> params) {
        requireEnabled();
        Map<String, String> form;
        try {
            form = single(params);
        } catch (AuthorizeException e) {
            throw new OAuthException(400, "invalid_request", "Each parameter may be sent once");
        }
        OAuthService.TokenResponse token = oauth.exchange(form);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", token.expiresIn());
        body.put("scope", token.scope());
        return ResponseEntity.ok().header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    @PostMapping(path = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> revoke(@RequestParam(name = "token", required = false) String token) {
        requireEnabled();
        oauth.revoke(token);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, String>> oauthError(OAuthException e) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
        if (e.status() == 401) {
            answer.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return answer.body(Map.of("error", e.error(), "error_description", e.getMessage()));
    }

    private void requireEnabled() {
        if (!oauth.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** OAuth parameters must not repeat (RFC 6749 §3.1); a repeated one fails the request. */
    static Map<String, String> single(MultiValueMap<String, String> params) {
        Map<String, String> single = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (entry.getValue().size() != 1) {
                throw new AuthorizeException(AuthorizeException.Reason.REPEATED_PARAMETER, null);
            }
            single.put(entry.getKey(), entry.getValue().getFirst());
        }
        return single;
    }

    /**
     * Back to the form (303, so the browser GETs it), with the checked request and {@code retry=1}
     * so the page says the sign-in failed. The password and SSN are not carried back.
     */
    private static ResponseEntity<String> signInFailed(AuthorizationRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", request.clientId());
        query.put("redirect_uri", request.redirectUri());
        query.put("code_challenge", request.codeChallenge());
        query.put("code_challenge_method", "S256");
        query.put("resource", request.resource());
        query.put("scope", request.scope());
        if (request.state() != null) {
            query.put("state", request.state());
        }
        query.put("retry", "1");
        String encoded = String.join("&", query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .toList());
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(RequestPaths.OAUTH_AUTHORIZE + "?" + encoded)).build();
    }

    private static ResponseEntity<String> failure(AuthorizeException e) {
        return e.redirect()
                .map(OAuthController::redirect)
                .orElseGet(() -> ResponseEntity.badRequest().contentType(HTML).body(AuthorizePage.error(e.reason())));
    }

    private static ResponseEntity<String> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }
}
