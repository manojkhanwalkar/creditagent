package com.creditagent.web.auth;

import com.creditagent.common.SafeLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes sessions that passed their idle timeout without anyone using or logging out of them. */
@Component
class SessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(SessionSweeper.class);

    private final AuthService auth;

    SessionSweeper(AuthService auth) {
        this.auth = auth;
    }

    @Scheduled(fixedDelayString = "${credit.auth.session-sweep-interval:60s}", initialDelayString = "${credit.auth.session-sweep-interval:60s}")
    void sweep() {
        try {
            int removed = auth.cleanUpExpiredSessions();
            if (removed > 0) {
                log.info("Removed {} expired sessions", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Session sweep failed: {}", SafeLogging.describe(e));
        }
    }
}
