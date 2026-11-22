package com.creditagent.web.subscriptions;

import com.creditagent.common.AuditLog;
import com.creditagent.common.SafeLogging;
import com.creditagent.subscriptions.Product;
import com.creditagent.subscriptions.Subscription;
import com.creditagent.subscriptions.SubscriptionRepository;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.AccountSubscriptions;
import com.creditagent.web.auth.User;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paid subscriptions: what the user has, what they can buy, and buying it. A term lasts one year from
 * the payment. Only {@link Product#ID_PROTECTION} is offered so far; Credit Monitoring is listed (the
 * table tracks it) but cannot be bought until the monitoring itself exists.
 */
public class SubscriptionService implements AccountSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** Products that can be bought now. */
    static final Set<Product> OFFERED = Set.of(Product.ID_PROTECTION);

    public enum Status { OK, UNAVAILABLE }

    /**
     * One product as the page shows it.
     *
     * @param offered it can be bought now
     * @param priceCents the price of a year, when offered
     */
    public record ProductView(Product product, boolean active, Instant startedAt, Instant expiresAt, boolean offered,
            Long priceCents, String currency) {}

    /**
     * @param paymentsAvailable a payment provider is configured; when false the page shows the products
     *     but no buy button
     * @param upsell the products to offer this user at login: offered, and not active
     */
    public record View(Status status, boolean paymentsAvailable, List<ProductView> products, List<Product> upsell) {
        static View unavailable() {
            return new View(Status.UNAVAILABLE, false, List.of(), List.of());
        }
    }

    public enum Outcome {
        /** The payment was taken and the term started. */
        ACTIVE,
        /** The user already has an active term; nothing was charged. */
        ALREADY_ACTIVE,
        /** Send the user to {@code checkoutUrl}; the term starts when the provider confirms the payment. */
        CHECKOUT
    }

    /** What a confirmed payment did ({@link #activatePaid}, the internal activation API). */
    public enum ActivationOutcome {
        /** A term was started by this payment. */
        ACTIVE,
        /** This payment already bought the active term (a repeated confirmation): nothing changed. */
        ALREADY_SAME_PAYMENT,
        /** Another payment's term is active: this payment started nothing and must be refunded. */
        ALREADY_ACTIVE
    }

    /** @param term the active term this payment bought; null for {@link ActivationOutcome#ALREADY_ACTIVE} */
    public record Activation(ActivationOutcome outcome, Subscription term) {
        public Activation {
            Objects.requireNonNull(outcome, "outcome");
            if ((term == null) != (outcome == ActivationOutcome.ALREADY_ACTIVE)) {
                throw new IllegalArgumentException("Only ALREADY_ACTIVE has no term");
            }
        }

        public Instant expiresAt() {
            return term == null ? null : term.expiresAt();
        }
    }

    /** What can be bought, for the registration form (no sign-in). */
    public record Plan(Product product, long priceCents, String currency) {}

    public record Plans(boolean paymentsAvailable, List<Plan> plans) {}

    public Plans plans() {
        List<Plan> plans = new ArrayList<>();
        for (Product product : Product.values()) {
            if (OFFERED.contains(product)) {
                plans.add(new Plan(product, price(product), properties.currency()));
            }
        }
        return new Plans(payments.available(), List.copyOf(plans));
    }

    public record SubscribeResult(Outcome outcome, Product product, Instant expiresAt, URI checkoutUrl) {}

    private final AuthService auth;
    private final SubscriptionRepository store;
    private final PaymentGateway payments;
    private final SubscriptionProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    public SubscriptionService(AuthService auth, SubscriptionRepository store, PaymentGateway payments,
            SubscriptionProperties properties, AuditLog audit, Clock clock) {
        this.auth = Objects.requireNonNull(auth);
        this.store = Objects.requireNonNull(store);
        this.payments = Objects.requireNonNull(payments);
        this.properties = Objects.requireNonNull(properties);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** At login: never throws, so a DynamoDB problem cannot fail a login. */
    @Override
    public View atLogin(String userId) {
        try {
            return view(userId);
        } catch (RuntimeException e) {
            log.warn("Subscriptions unavailable: {}", SafeLogging.describe(e));
            return View.unavailable();
        }
    }

    /**
     * What registration answers about the ID Protection the user asked for.
     *
     * @param outcome null when nothing could be bought ({@code message} says why)
     */
    public record RegistrationOutcome(Outcome outcome, URI checkoutUrl, String message) {}

    static final String NOT_STARTED = "Your account is ready, but ID Protection could not be started. You can"
            + " subscribe after you sign in.";

    /** At registration: never throws; the account exists whatever happens to the payment. */
    @Override
    public RegistrationOutcome atRegistration(String userId) {
        try {
            SubscribeResult result = subscribe(userId, Product.ID_PROTECTION);
            return new RegistrationOutcome(result.outcome(), result.checkoutUrl(), null);
        } catch (PaymentDeclinedException e) {
            return new RegistrationOutcome(null, null, e.getMessage() + " You can subscribe after you sign in.");
        } catch (RuntimeException e) {
            log.warn("ID Protection not started at registration: {}", SafeLogging.describe(e));
            return new RegistrationOutcome(null, null, NOT_STARTED);
        }
    }

    /** The user's subscriptions and offers. @throws RuntimeException if the table cannot be read */
    public View view(String userId) {
        Instant now = clock.instant();
        Map<Product, Subscription> held = new java.util.EnumMap<>(Product.class);
        for (Subscription s : store.findAll(userId)) {
            held.put(s.product(), s);
        }
        List<ProductView> products = new ArrayList<>();
        List<Product> upsell = new ArrayList<>();
        for (Product product : Product.values()) {
            Subscription s = held.get(product);
            boolean active = s != null && s.activeAt(now);
            boolean offered = OFFERED.contains(product);
            products.add(new ProductView(product, active, s == null ? null : s.startedAt(), s == null ? null : s.expiresAt(),
                    offered, offered ? price(product) : null, offered ? properties.currency() : null));
            if (offered && !active) {
                upsell.add(product);
            }
        }
        return new View(Status.OK, payments.available(), List.copyOf(products), List.copyOf(upsell));
    }

    /** @return true if the user has an active term for the product. @throws RuntimeException if the table cannot be read */
    public boolean isActive(String userId, Product product) {
        Instant now = clock.instant();
        return store.findAll(userId).stream().anyMatch(s -> s.product() == product && s.activeAt(now));
    }

    /** @return true if the user has an active term for any product (the retention job keeps such accounts) */
    public boolean hasAnyActive(String userId) {
        Instant now = clock.instant();
        return store.findAll(userId).stream().anyMatch(s -> s.activeAt(now));
    }

    /**
     * Buys a year of the product. An active term is never charged again.
     *
     * @throws ProductNotOfferedException if the product cannot be bought
     * @throws PaymentUnavailableException if no payment can be taken now
     * @throws PaymentDeclinedException if the provider refused the payment
     */
    public SubscribeResult subscribe(String userId, Product product) {
        if (product == null || !OFFERED.contains(product)) {
            throw new ProductNotOfferedException();
        }
        User account = auth.account(userId).filter(u -> u.email() != null).orElseThrow(ProductNotOfferedException::new);
        Instant now = clock.instant();
        Optional<Subscription> current = store.findAll(userId).stream().filter(s -> s.product() == product).findFirst();
        if (current.isPresent() && current.get().activeAt(now)) {
            return new SubscribeResult(Outcome.ALREADY_ACTIVE, product, current.get().expiresAt(), null);
        }
        if (!payments.available()) {
            throw new PaymentUnavailableException();
        }
        // One key per term bought: repeats of this purchase share it, the next renewal gets a new one.
        String idempotencyKey = "sub:" + userId + ":" + product.name() + ":"
                + current.map(s -> s.expiresAt().toString()).orElse("first");
        PaymentGateway.Checkout checkout = payments.checkout(
                new PaymentGateway.CheckoutRequest(userId, product, price(product), properties.currency(), idempotencyKey));
        return switch (checkout) {
            case PaymentGateway.Redirect redirect -> new SubscribeResult(Outcome.CHECKOUT, product, null, redirect.url());
            case PaymentGateway.Paid paid -> {
                Activation activation = activate(account, product, paid.paymentReference());
                yield activation.outcome() == ActivationOutcome.ALREADY_ACTIVE
                        ? new SubscribeResult(Outcome.ALREADY_ACTIVE, product, null, null)
                        : new SubscribeResult(Outcome.ACTIVE, product, activation.expiresAt(), null);
            }
        };
    }

    /**
     * Starts a term for a payment confirmed elsewhere (the payments-service, through
     * {@code POST /internal/v1/subscriptions/activations}). Safe to repeat: the same payment answers
     * {@link ActivationOutcome#ALREADY_SAME_PAYMENT}.
     *
     * @throws ProductNotOfferedException if the product cannot be bought
     * @throws UnknownSubscriberException if there is no such account, or it has no email to hold a term
     * @throws RuntimeException if DynamoDB fails (audited as {@code FAILED})
     */
    public Activation activatePaid(String userId, Product product, String paymentReference) {
        if (product == null || !OFFERED.contains(product)) {
            throw new ProductNotOfferedException();
        }
        Objects.requireNonNull(paymentReference, "paymentReference");
        User account = auth.account(userId).filter(u -> u.email() != null).orElseThrow(UnknownSubscriberException::new);
        return activate(account, product, paymentReference);
    }

    /**
     * Starts a one-year term for a payment the provider confirmed (here, or from the payments-service
     * later). If a term started meanwhile (two payments raced), this payment starts nothing; the audit
     * record says so, for a refund. The store's conditional write decides; a repeat of the payment that
     * bought the active term is recognised by its {@code paymentReference}.
     */
    Activation activate(User account, Product product, String paymentReference) {
        Instant now = clock.instant();
        Instant end = now.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        Subscription term = new Subscription(account.userId(), product, now, end, paymentReference);
        Map<String, String> detail = Map.of("product", product.name(), "paymentReference", paymentReference,
                "expiresAt", end.toString());
        boolean started;
        try {
            started = store.activate(term, account.email());
        } catch (RuntimeException e) {
            // The payment was taken but no term was stored: record it, so it can be refunded or applied.
            audit.record(AuditLog.Event.SUBSCRIPTION_STARTED, "user:" + account.userId(), "user:" + account.userId(),
                    "FAILED", detail);
            log.warn("Payment {} for {} was taken but its term could not be stored: {}", paymentReference, product,
                    SafeLogging.describe(e));
            throw e;
        }
        if (!started) {
            // The same payment confirmed twice (a repeated webhook) bought the term that is active: not a refund.
            Optional<Subscription> same = store.findAll(account.userId()).stream()
                    .filter(s -> s.product() == product && paymentReference.equals(s.paymentReference())).findFirst();
            if (same.isPresent() && same.get().activeAt(now)) {
                return new Activation(ActivationOutcome.ALREADY_SAME_PAYMENT, same.get());
            }
        }
        audit.record(AuditLog.Event.SUBSCRIPTION_STARTED, "user:" + account.userId(), "user:" + account.userId(),
                started ? "OK" : "ALREADY_ACTIVE", detail);
        if (!started) {
            log.warn("Payment {} for {} started nothing: already subscribed; refund it", paymentReference, product);
            return new Activation(ActivationOutcome.ALREADY_ACTIVE, null);
        }
        return new Activation(ActivationOutcome.ACTIVE, term);
    }

    /**
     * The latest term per product (a renewal replaces the earlier one; the audit log keeps every payment),
     * for a data export. @throws RuntimeException if the table cannot be read
     */
    public List<Subscription> forExport(String userId) {
        return store.findAll(userId);
    }

    /** Deletes the user's subscription records (account deletion). @throws RuntimeException if DynamoDB fails */
    public void erase(String userId, String email) {
        List<Subscription> held = store.findAll(userId);
        store.eraseAll(userId, email);
        // What was erased stays in the audit log: the products and the payments that bought them.
        Map<String, String> detail = new java.util.LinkedHashMap<>();
        for (Subscription s : held) {
            detail.put(s.product().name(), s.paymentReference() + " until " + s.expiresAt());
        }
        audit.record(AuditLog.Event.SUBSCRIPTIONS_ERASED, "user:" + userId, "user:" + userId, "OK", detail);
    }

    private long price(Product product) {
        return switch (product) {
            case ID_PROTECTION -> properties.idProtectionPriceCents();
            case CREDIT_MONITORING -> throw new ProductNotOfferedException();
        };
    }
}
