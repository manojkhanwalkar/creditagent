package com.creditagent.web.auth;

import org.springframework.core.annotation.Order;

/** Test helper: reads the package-private filter's order. */
public final class AuthenticationFilterOrder {

    private AuthenticationFilterOrder() {}

    public static int value() {
        return AuthenticationFilter.class.getAnnotation(Order.class).value();
    }
}
