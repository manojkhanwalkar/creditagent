package com.creditagent.web.auth;

/** Failures the auth API turns into HTTP problems. Messages are safe to show and never echo input. */
public final class AuthExceptions {

    private AuthExceptions() {}

    /** 400: the registration request breaks a rule. */
    static final class InvalidRegistrationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidRegistrationException(String message) {
            super(message);
        }
    }

    /** 409: the user id is taken. */
    static final class UserAlreadyExistsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UserAlreadyExistsException() {
            super("That user id is already registered.");
        }
    }

    /** 409: the SSN already belongs to an account. */
    static final class SsnAlreadyRegisteredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SsnAlreadyRegisteredException() {
            super("An account already exists for that SSN.");
        }
    }

    /** 409: the email already belongs to an account. */
    static final class EmailAlreadyRegisteredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EmailAlreadyRegisteredException() {
            super("An account already exists for that email address.");
        }
    }

    /** 401: wrong credentials, or a missing, invalid or expired token or session. */
    public static final class UnauthenticatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnauthenticatedException(String message) {
            super(message);
        }
    }

    /** 400: a sign-in code request that cannot be acted on (unknown channel, malformed code, no code sent yet). */
    public static final class InvalidOtpRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidOtpRequestException(String message) {
            super(message);
        }
    }

    /**
     * 401: the code step failed. {@code restart}: the sign-in is over (expired, used up or unknown) and
     * the user must enter their password again; otherwise they may try again.
     */
    public static final class OtpFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean restart;
        private final Integer attemptsLeft;

        OtpFailedException(String message, boolean restart, Integer attemptsLeft) {
            super(message);
            this.restart = restart;
            this.attemptsLeft = attemptsLeft;
        }

        static OtpFailedException restart(String message) {
            return new OtpFailedException(message, true, null);
        }

        public boolean restart() {
            return restart;
        }

        public Integer attemptsLeft() {
            return attemptsLeft;
        }
    }

    /** 429: no more codes for now; {@code restart} when this sign-in can send no more at all. */
    public static final class TooManyCodesException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final java.time.Duration retryAfter;
        private final boolean restart;

        TooManyCodesException(String message, java.time.Duration retryAfter, boolean restart) {
            super(message);
            this.retryAfter = retryAfter;
            this.restart = restart;
        }

        public java.time.Duration retryAfter() {
            return retryAfter;
        }

        public boolean restart() {
            return restart;
        }
    }
}
