package com.creditagent.web.subscriptions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Remembers {@link PaymentGateway#available()} for a short time, because every login view asks it and a
 * remote gateway (P3) would otherwise make one call per login. The cache is one value, so it is bounded.
 * A checkout the provider could not take forgets it, so the next view asks again. Concurrent misses may
 * each ask the gateway once; that is cheaper than a lock on the login path. Checkouts always go through.
 */
final class CachingPaymentGateway implements PaymentGateway {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private record Answer(boolean available, Instant until) {}

    private final PaymentGateway delegate;
    private final Duration ttl;
    private final Clock clock;
    private volatile Answer answer;

    CachingPaymentGateway(PaymentGateway delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.ttl = Objects.requireNonNull(ttl);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("The availability cache needs a positive time to live");
        }
    }

    @Override
    public boolean available() {
        Instant now = clock.instant();
        Answer cached = answer;
        if (cached != null && now.isBefore(cached.until())) {
            return cached.available();
        }
        boolean available = delegate.available();
        answer = new Answer(available, now.plus(ttl));
        return available;
    }

    @Override
    public Checkout checkout(CheckoutRequest request) {
        try {
            return delegate.checkout(request);
        } catch (PaymentUnavailableException e) {
            answer = null;
            throw e;
        }
    }
}
