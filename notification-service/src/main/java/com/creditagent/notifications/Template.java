package com.creditagent.notifications;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The messages this service sends. It owns their wording: callers name a template and give its params,
 * and never send text of their own. Every param is checked before anything is rendered.
 */
public enum Template {

    /** A one-time sign-in code. Params: {@code code} (6 digits), {@code validSeconds} (1 to 86400). */
    OTP_SIGN_IN(Set.of(Channel.EMAIL, Channel.SMS), Set.of("code", "validSeconds"), Set.of()) {
        @Override
        void check(Map<String, String> params, Channel channel) {
            require(CODE.matcher(params.get("code")).matches(), "code must be 6 digits");
            intParam(params, "validSeconds", 1, MAX_VALID_SECONDS);
        }

        @Override
        Message render(Map<String, String> params, Channel channel) {
            String code = params.get("code");
            String expires = duration(Integer.parseInt(params.get("validSeconds")));
            if (channel == Channel.SMS) {
                return new Message(null, code + " is your CreditAgent sign-in code. It expires in " + expires
                        + ". Never share it; we will never ask for it.");
            }
            return new Message(OTP_SUBJECT, "Your CreditAgent sign-in code is " + code + ".\n\n"
                    + "It expires in " + expires + ". Never share it: CreditAgent will never ask you for it.\n\n"
                    + "If you did not just try to sign in, someone else knows your password. Change it, and do not"
                    + " enter this code anywhere.\n");
        }
    },

    /**
     * New dark-web findings for the user's email. Params: {@code count} (places found, 1 to 100000),
     * {@code firstSeen} (ISO date), optional {@code appUrl} (an http or https address). Plain text with
     * fixed wording and no location names; email only.
     */
    DARK_WEB_ALERT(Set.of(Channel.EMAIL), Set.of("count", "firstSeen"), Set.of("appUrl")) {
        @Override
        void check(Map<String, String> params, Channel channel) {
            intParam(params, "count", 1, 100_000);
            try {
                LocalDate.parse(params.get("firstSeen"));
            } catch (DateTimeParseException e) {
                throw new InvalidNotificationException("firstSeen must be an ISO date (yyyy-mm-dd)");
            }
            String appUrl = params.get("appUrl");
            if (appUrl != null && !appUrl.isEmpty()) {
                require(webAddress(appUrl), "appUrl must be an http or https address");
            }
        }

        @Override
        Message render(Map<String, String> params, Channel channel) {
            int count = Integer.parseInt(params.get("count"));
            String where = count == 1 ? "1 place" : count + " places";
            String appUrl = params.getOrDefault("appUrl", "");
            String signIn = appUrl.isBlank() ? "Sign in to Credit Agent" : "Sign in to Credit Agent at " + appUrl;
            return new Message(ALERT_SUBJECT, """
                    Hello,

                    Your ID Protection found your email address in %s on the dark web (first seen %s). \
                    Someone may try to use it to open accounts in your name or to get into yours.

                    What we suggest:
                    - Watch your credit report for accounts you did not open.
                    - Change the passwords you use with this email address, and turn on two-step sign-in where you can.
                    - %s to see the details.

                    We will never ask for your password or SSN by email.

                    Credit Agent
                    """.formatted(where, LocalDate.parse(params.get("firstSeen")), signIn));
        }
    };

    static final String OTP_SUBJECT = "Your CreditAgent sign-in code";
    static final String ALERT_SUBJECT = "Your email address was found on the dark web";
    static final int MAX_PARAMS = 8;
    static final int MAX_PARAM_NAME_LENGTH = 32;
    static final int MAX_PARAM_VALUE_LENGTH = 512;
    static final int MAX_VALID_SECONDS = 86_400;

    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,9}$");
    private static final Pattern URL_CHARS = Pattern.compile("^[\\x21-\\x7e]+$");

    private final Set<Channel> channels;
    private final Set<String> required;
    private final Set<String> optional;

    Template(Set<Channel> channels, Set<String> required, Set<String> optional) {
        this.channels = channels;
        this.required = required;
        this.optional = optional;
    }

    /**
     * Checks the channel, the param names (none missing, none extra) and every value, then renders.
     *
     * @throws InvalidNotificationException naming what is wrong, never a value
     */
    public Message validateAndRender(Map<String, String> params, Channel channel) {
        require(channels.contains(channel), this + " cannot be sent by " + channel);
        Map<String, String> given = params == null ? Map.of() : params;
        require(given.size() <= MAX_PARAMS, "too many params");
        for (Map.Entry<String, String> param : given.entrySet()) {
            require(param.getKey() != null && param.getKey().length() <= MAX_PARAM_NAME_LENGTH
                    && (required.contains(param.getKey()) || optional.contains(param.getKey())),
                    this + " takes only the params " + new TreeSet<>(union()));
            require(param.getValue() != null && param.getValue().length() <= MAX_PARAM_VALUE_LENGTH,
                    "param " + param.getKey() + " must be a string of at most " + MAX_PARAM_VALUE_LENGTH + " characters");
        }
        for (String name : new TreeSet<>(required)) {
            require(given.containsKey(name), this + " needs the param " + name);
        }
        check(given, channel);
        return render(given, channel);
    }

    abstract void check(Map<String, String> params, Channel channel);

    abstract Message render(Map<String, String> params, Channel channel);

    private Set<String> union() {
        Set<String> all = new TreeSet<>(required);
        all.addAll(optional);
        return all;
    }

    /** {@code 300} reads "5 minutes", {@code 90} "90 seconds": exact, whatever the caller's code lifetime. */
    static String duration(int seconds) {
        if (seconds % 60 == 0) {
            int minutes = seconds / 60;
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        return seconds == 1 ? "1 second" : seconds + " seconds";
    }

    static boolean webAddress(String value) {
        if (!URL_CHARS.matcher(value).matches()) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static void intParam(Map<String, String> params, String name, int min, int max) {
        String value = params.get(name);
        require(DIGITS.matcher(value).matches(), name + " must be a whole number from " + min + " to " + max);
        int n = Integer.parseInt(value);
        require(n >= min && n <= max, name + " must be a whole number from " + min + " to " + max);
    }

    private static void require(boolean ok, String problem) {
        if (!ok) {
            throw new InvalidNotificationException(problem);
        }
    }
}
