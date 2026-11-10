package com.creditagent.web.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Lets MVC tests in other packages include the (package-private) login and logout endpoints. */
@TestConfiguration
@Import({AuthController.class, AuthErrorHandling.class})
public class AuthControllerConfig {}
