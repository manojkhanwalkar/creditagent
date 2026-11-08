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

    /** 409: the SSN already belongs to an account. */
    static final class SsnAlreadyRegisteredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SsnAlreadyRegisteredException() {
            super("An account already exists for that SSN.");
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
