package com.creditagent.web.subscriptions;

import com.creditagent.common.AuditLog;
import com.creditagent.subscriptions.Product;
import com.creditagent.subscriptions.Subscription;
import com.creditagent.subscriptions.SubscriptionKeys;
import com.creditagent.subscriptions.SubscriptionRepository;
import com.creditagent.web.auth.AuthService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The Subscriptions table in memory, with the same conditional activation as DynamoDB. */
public class InMemorySubscriptions implements SubscriptionRepository {

    final Map<String, Subscription> byUser = new ConcurrentHashMap<>();
    final Map<String, Instant> byEmail = new ConcurrentHashMap<>();

    /** A service over this store with the test gateway, for tests outside this package. */
    public SubscriptionService service(AuthService auth, Clock clock) {
        return new SubscriptionService(auth, this, PaymentGateway.test(),
                new SubscriptionProperties(null, null, null, null), new AuditLog(), clock);
    }

    @Override
    public List<Subscription> findAll(String userId) {
        List<Subscription> found = new ArrayList<>();
        for (Product product : Product.values()) {
            Subscription s = byUser.get(userId + "/" + product);
            if (s != null) {
                found.add(s);
            }
        }
        return found;
    }

    @Override
    public synchronized boolean activate(Subscription subscription, String email) {
        String key = subscription.userId() + "/" + subscription.product();
        Subscription current = byUser.get(key);
        if (current != null && current.activeAt(subscription.startedAt())) {
            return false;
        }
        byUser.put(key, subscription);
        byEmail.put(SubscriptionKeys.emailKey(email) + "/" + subscription.product(), subscription.expiresAt());
        return true;
    }

    @Override
    public boolean activeForEmail(String email, Product product, Instant now) {
        Instant end = byEmail.get(SubscriptionKeys.emailKey(email) + "/" + product);
        return end != null && now.isBefore(end);
    }

    @Override
    public void eraseAll(String userId, String email) {
        for (Product product : Product.values()) {
            byUser.remove(userId + "/" + product);
            if (email != null) {
                byEmail.remove(SubscriptionKeys.emailKey(email) + "/" + product);
            }
        }
    }
}
