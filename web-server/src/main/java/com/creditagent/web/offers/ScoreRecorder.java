package com.creditagent.web.offers;

import com.creditagent.common.SafeLogging;
import com.creditagent.common.Ssn;
import com.creditagent.web.CreditScoreService;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.RegistrationListener;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a first credit score for a new account, in the background, so offers are ready at the first
 * login. Registration is the one moment the server has the SSN without the user typing it (the KYC
 * check read it from the card); the SSN is used for this one look-up and not kept. A failure only
 * means the user sees "look up your score first" until they do.
 */
public class ScoreRecorder implements RegistrationListener {

    private static final Logger log = LoggerFactory.getLogger(ScoreRecorder.class);

    private final AuthService auth;
    private final CreditScoreService scores;
    private final Executor executor;

    public ScoreRecorder(AuthService auth, CreditScoreService scores, Executor executor) {
        this.auth = Objects.requireNonNull(auth);
        this.scores = Objects.requireNonNull(scores);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void registered(String userId, Ssn ssn) {
        executor.execute(() -> {
            try {
                auth.recordScore(userId, scores.scoreFor(ssn).value());
            } catch (RuntimeException e) {
                log.info("No first score for {}: {}", userId, SafeLogging.describe(e));
            }
        });
    }
}
