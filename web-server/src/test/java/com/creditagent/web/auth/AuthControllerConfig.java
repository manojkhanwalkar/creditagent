package com.creditagent.web.auth;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Lets MVC tests in other packages include the (package-private) login and logout endpoints, with the
 * code step's delivery mocked by {@link #OTP}.
 */
@TestConfiguration
@Import({AuthController.class, AuthErrorHandling.class})
public class AuthControllerConfig {

    /** Where the sign-in codes of these tests "arrive". */
    public static final RecordingOtpSender OTP = new RecordingOtpSender();

    @Bean
    OtpLoginService otpLoginService(AuthService auth) {
        return OtpTesting.service(auth, OTP, Clock.systemUTC());
    }
}
