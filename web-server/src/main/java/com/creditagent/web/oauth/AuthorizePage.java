package com.creditagent.web.oauth;

import java.net.URI;
import org.springframework.web.util.HtmlUtils;

/**
 * The sign-in and consent page of the OAuth flow, rendered on the server. Every value from the
 * request is HTML-escaped. No inline script or style, so the strict CSP still applies; only
 * {@code form-action} is widened, to the client's redirect origin (see {@link #csp}).
 */
final class AuthorizePage {

    private AuthorizePage() {}

    /** The form, with the checked request carried in hidden fields; {@code error} may be null. */
    static String form(OAuthService.AuthorizationRequest request, String error) {
        StringBuilder html = new StringBuilder(4096);
        head(html, "Connect CreditAgent");
        html.append("<h1>Connect CreditAgent</h1>")
                .append("<p><strong>").append(escape(request.clientName())).append("</strong> is asking to use your")
                .append(" CreditAgent account: read your credit score, credit report and advice, and lock or unlock")
                .append(" your credit report.</p>")
                .append("<p class=\"muted\">After you allow it, you go back to <code>")
                .append(escape(origin(request.redirectUri()))).append("</code>.</p>");
        if (error != null) {
            html.append("<p class=\"error\" role=\"alert\">").append(escape(error)).append("</p>");
        }
        html.append("<form method=\"post\" action=\"/oauth/authorize\">");
        hidden(html, "response_type", "code");
        hidden(html, "client_id", request.clientId());
        hidden(html, "redirect_uri", request.redirectUri());
        hidden(html, "code_challenge", request.codeChallenge());
        hidden(html, "code_challenge_method", "S256");
        hidden(html, "resource", request.resource());
        hidden(html, "scope", request.scope());
        if (request.state() != null) {
            hidden(html, "state", request.state());
        }
        html.append("<label>User id <input name=\"userId\" autocomplete=\"username\" maxlength=\"64\" required></label>")
                .append("<label>Password <input type=\"password\" name=\"password\" autocomplete=\"current-password\"")
                .append(" maxlength=\"256\" required></label>")
                .append("<label>Your SSN <input name=\"ssn\" inputmode=\"numeric\" autocomplete=\"off\" maxlength=\"11\"")
                .append(" placeholder=\"123-45-6789\" required></label>")
                .append("<p class=\"muted\">Your SSN confirms the account is yours and lets CreditAgent fetch your")
                .append(" report. It is never shown to the application.</p>")
                .append("<div class=\"actions\">")
                .append("<button type=\"submit\" name=\"decision\" value=\"allow\">Allow</button>")
                .append("<button type=\"submit\" name=\"decision\" value=\"deny\" class=\"secondary\" formnovalidate>Deny</button>")
                .append("</div></form>");
        tail(html);
        return html.toString();
    }

    /** A request that cannot go back to its client: say so, and redirect nowhere. */
    static String error(String message) {
        StringBuilder html = new StringBuilder(1024);
        head(html, "Cannot connect");
        html.append("<h1>Cannot connect</h1><p class=\"error\" role=\"alert\">").append(escape(message)).append("</p>")
                .append("<p class=\"muted\">Start again from the application you were connecting.</p>");
        tail(html);
        return html.toString();
    }

    /**
     * The page's CSP: the site's usual policy, but forms may also lead to the client's redirect origin.
     * Browsers apply {@code form-action} to the redirect that follows the form post, so without it
     * the hand-back to the client would be blocked.
     */
    static String csp(String redirectUri) {
        return String.join("; ",
                "default-src 'none'",
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

    private static void head(StringBuilder html, String title) {
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append("<link rel=\"stylesheet\" href=\"/css/oauth.css\"></head><body><main class=\"card\">");
    }

    private static void tail(StringBuilder html) {
        html.append("</main></body></html>");
    }

    private static void hidden(StringBuilder html, String name, String value) {
        html.append("<input type=\"hidden\" name=\"").append(name).append("\" value=\"").append(escape(value)).append("\">");
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
