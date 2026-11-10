package com.creditagent.web.oauth;

import com.creditagent.web.oauth.OAuthService.AuthorizeException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * The pages of the OAuth flow. Neither holds any request data: the consent page is a fixed file
 * (filled in the browser by {@code oauth-consent.js}, as text), and the error page shows a fixed text
 * chosen by {@link Reason}. So nothing a request carries can become markup.
 */
final class AuthorizePage {

    private static final String CONSENT = load("oauth/consent.html");

    private AuthorizePage() {}

    /** The sign-in and consent form. */
    static String consent() {
        return CONSENT;
    }

    /** A request that cannot go back to its client: say why, and redirect nowhere. */
    static String error(Reason reason) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Cannot connect</title><link rel=\"stylesheet\" href=\"/css/oauth.css\"></head>"
                + "<body><main class=\"card\"><h1>Cannot connect</h1><p class=\"error\" role=\"alert\">"
                + reason.text()
                + "</p><p class=\"muted\">Start again from the application you were connecting.</p></main></body></html>";
    }

    /**
     * The consent page's CSP: scripts and styles from this site only, and forms may also lead to the
     * client's redirect origin. Browsers apply {@code form-action} to the redirect that follows the
     * form post, so without it the hand-back to the client would be blocked.
     */
    static String csp(String redirectUri) {
        return String.join("; ",
                "default-src 'none'",
                "script-src 'self'",
                "style-src 'self'",
                "img-src 'self'",
                "base-uri 'none'",
                "form-action 'self' " + origin(redirectUri),
                "frame-ancestors 'none'");
    }

    static String origin(String uri) {
        URI parsed = URI.create(uri);
        return parsed.getScheme() + "://" + parsed.getRawAuthority();
    }

    private static String load(String resource) {
        try (InputStream in = AuthorizePage.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
