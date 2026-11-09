package com.creditagent.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.web.util.UrlPathHelper;

/**
 * The request path as Spring MVC routes it: percent-decoded, {@code ;params} removed and repeated
 * slashes collapsed. Security filters must look at this, not at {@link
 * HttpServletRequest#getRequestURI()}: {@code /%61pi/...} is {@code /api/...} to the router but not
 * to a raw prefix check, which would let the request skip the filter.
 */
public final class RequestPaths {

    /** The OAuth sign-in and consent form (see {@code com.creditagent.web.oauth.OAuthController}). */
    public static final String OAUTH_AUTHORIZE = "/oauth/authorize";

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setAlwaysUseFullPath(true);
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private RequestPaths() {}

    public static String path(HttpServletRequest request) {
        return PATHS.getLookupPathForRequest(request);
    }

    /** True for anything under {@code /api/}, compared without case so the check fails closed. */
    public static boolean isApi(HttpServletRequest request) {
        return lowerCasePath(request).startsWith("/api/");
    }

    /** True for anything under {@code /oauth/}, compared without case so the check fails closed. */
    public static boolean isOAuth(HttpServletRequest request) {
        return lowerCasePath(request).startsWith("/oauth/");
    }

    /** True for the sign-in and consent form, where a person types a password. */
    public static boolean isOAuthSignIn(HttpServletRequest request) {
        String path = lowerCasePath(request);
        return path.equals(OAUTH_AUTHORIZE) || path.startsWith(OAUTH_AUTHORIZE + "/");
    }

    private static String lowerCasePath(HttpServletRequest request) {
        return path(request).toLowerCase(Locale.ROOT);
    }
}
