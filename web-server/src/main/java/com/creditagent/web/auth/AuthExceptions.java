package com.creditagent.web.auth;

/** Failures the auth API turns into HTTP problems. Messages are safe to show and never echo input. */
final class AuthExceptions {

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

    /** 401: wrong credentials, or a missing, invalid or expired token or session. */
    static final class UnauthenticatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnauthenticatedException(String message) {
            super(message);
        }
    }
}
