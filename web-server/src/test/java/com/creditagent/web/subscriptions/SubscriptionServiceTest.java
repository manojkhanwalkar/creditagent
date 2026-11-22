package com.creditagent.web.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.creditagent.common.AuditLog;
import com.creditagent.subscriptions.Product;
import com.creditagent.subscriptions.Subscription;
import com.creditagent.subscriptions.SubscriptionRepository;
import com.creditagent.web.auth.AuthProperties;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.ReportStatus;
import com.creditagent.web.auth.User;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.SdkClientException;

class SubscriptionServiceTest {

    static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    static final User ADA = new User("ada", "$2a$h", "ada@example.com", "h", "6789", "k", Instant.EPOCH, ReportStatus.UNLOCKED);

    final AuthService auth = mock(AuthService.class);
    final InMemorySubscriptions store = new InMemorySubscriptions();
    final AuditLog audit = mock(AuditLog.class);
    final java.time.Clock clock = java.time.Clock.fixed(NOW, ZoneOffset.UTC);

    SubscriptionService service(PaymentGateway gateway, SubscriptionRepository repository) {
        given(auth.account("ada")).willReturn(Optional.of(ADA));
        return new SubscriptionService(auth, repository, gateway, new SubscriptionProperties(null, null, "usd", null), audit, clock);
    }

    SubscriptionService service(PaymentGateway gateway) {
        return service(gateway, store);
    }

    @Test
    void aPaidTermRunsOneYearAndIsAudited() {
        SubscriptionService.SubscribeResult result = service(PaymentGateway.test()).subscribe("ada", Product.ID_PROTECTION);
        assertThat(result.outcome()).isEqualTo(SubscriptionService.Outcome.ACTIVE);
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2027-11-21T10:00:00Z"));
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("user:ada"), eq("user:ada"), eq("OK"), any(Map.class));
        assertThat(service(PaymentGateway.test()).isActive("ada", Product.ID_PROTECTION)).isTrue();
        assertThat(service(PaymentGateway.test()).hasAnyActive("ada")).isTrue();
    }

    @Test
    void theViewListsEveryProductWithPricesOnlyForOfferedOnes() {
        SubscriptionService.View view = service(PaymentGateway.none()).view("ada");
        assertThat(view.status()).isEqualTo(SubscriptionService.Status.OK);
        assertThat(view.paymentsAvailable()).isFalse();
        assertThat(view.upsell()).containsExactly(Product.ID_PROTECTION);
        assertThat(view.products()).extracting(SubscriptionService.ProductView::product)
                .containsExactly(Product.ID_PROTECTION, Product.CREDIT_MONITORING);
        assertThat(view.products().get(0).priceCents()).isEqualTo(2999L);
        assertThat(view.products().get(0).currency()).isEqualTo("USD");
        assertThat(view.products().get(1).priceCents()).isNull();
        assertThat(view.products().get(1).offered()).isFalse();
    }

    @Test
    void withoutAProviderNothingCanBeBoughtAndAnActiveTermIsNeverChargedAgain() {
        assertThatThrownBy(() -> service(PaymentGateway.none()).subscribe("ada", Product.ID_PROTECTION))
                .isInstanceOf(PaymentUnavailableException.class);

        service(PaymentGateway.test()).subscribe("ada", Product.ID_PROTECTION);
        PaymentGateway never = mock(PaymentGateway.class);
        assertThat(service(never).subscribe("ada", Product.ID_PROTECTION).outcome())
                .isEqualTo(SubscriptionService.Outcome.ALREADY_ACTIVE);
        org.mockito.Mockito.verifyNoInteractions(never);
    }

    @Test
    void productsNotOfferedAndAccountsWithoutAnEmailCannotBuy() {
        SubscriptionService subscriptions = service(PaymentGateway.test());
        assertThatThrownBy(() -> subscriptions.subscribe("ada", Product.CREDIT_MONITORING)).isInstanceOf(ProductNotOfferedException.class);
        assertThatThrownBy(() -> subscriptions.subscribe("ada", null)).isInstanceOf(ProductNotOfferedException.class);
        given(auth.account("nobody")).willReturn(Optional.empty());
        assertThatThrownBy(() -> subscriptions.subscribe("nobody", Product.ID_PROTECTION)).isInstanceOf(ProductNotOfferedException.class);
    }

    @Test
    void aRedirectingProviderStartsNothingUntilItConfirms() {
        PaymentGateway hosted = mock(PaymentGateway.class);
        given(hosted.available()).willReturn(true);
        given(hosted.checkout(any())).willReturn(new PaymentGateway.Redirect(URI.create("https://pay.example/c/1")));
        SubscriptionService.SubscribeResult result = service(hosted).subscribe("ada", Product.ID_PROTECTION);
        assertThat(result.outcome()).isEqualTo(SubscriptionService.Outcome.CHECKOUT);
        assertThat(result.checkoutUrl()).hasToString("https://pay.example/c/1");
        assertThat(store.findAll("ada")).isEmpty();

        ArgumentCaptor<PaymentGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(hosted).checkout(sent.capture());
        assertThat(sent.getValue().amountCents()).isEqualTo(2999L);
        assertThat(sent.getValue().idempotencyKey()).isEqualTo("sub:ada:ID_PROTECTION:first");
    }

    @Test
    void aRenewalAfterExpiryGetsANewIdempotencyKey() {
        store.activate(new Subscription("ada", Product.ID_PROTECTION, NOW.minusSeconds(400 * 86_400L),
                NOW.minusSeconds(35 * 86_400L), "pay_old"), "ada@example.com");
        PaymentGateway hosted = mock(PaymentGateway.class);
        given(hosted.available()).willReturn(true);
        given(hosted.checkout(any())).willReturn(new PaymentGateway.Paid("pay_new"));
        assertThat(service(hosted).subscribe("ada", Product.ID_PROTECTION).outcome()).isEqualTo(SubscriptionService.Outcome.ACTIVE);
        ArgumentCaptor<PaymentGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(hosted).checkout(sent.capture());
        assertThat(sent.getValue().idempotencyKey()).startsWith("sub:ada:ID_PROTECTION:2026-10-17");
    }

    @Test
    void aPaymentThatLostTheRaceStartsNothingAndIsAuditedForARefund() {
        SubscriptionRepository racing = mock(SubscriptionRepository.class);
        given(racing.findAll("ada")).willReturn(List.of());
        given(racing.activate(any(), anyString())).willReturn(false);
        assertThat(service(PaymentGateway.test(), racing).subscribe("ada", Product.ID_PROTECTION).outcome())
                .isEqualTo(SubscriptionService.Outcome.ALREADY_ACTIVE);
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("user:ada"), eq("user:ada"), eq("ALREADY_ACTIVE"), any(Map.class));
    }

    @Test
    void loginAndRegistrationNeverFail() {
        SubscriptionRepository down = mock(SubscriptionRepository.class);
        given(down.findAll(anyString())).willThrow(SdkClientException.create("down"));
        assertThat(service(PaymentGateway.test(), down).atLogin("ada").status()).isEqualTo(SubscriptionService.Status.UNAVAILABLE);

        SubscriptionService.RegistrationOutcome failed = service(PaymentGateway.test(), down).atRegistration("ada");
        assertThat(failed.outcome()).isNull();
        assertThat(failed.message()).isEqualTo(SubscriptionService.NOT_STARTED);

        PaymentGateway declining = mock(PaymentGateway.class);
        given(declining.available()).willReturn(true);
        given(declining.checkout(any())).willThrow(new PaymentDeclinedException());
        assertThat(service(declining).atRegistration("ada").message()).contains("declined").contains("after you sign in");

        assertThat(service(PaymentGateway.test()).atRegistration("ada").outcome()).isEqualTo(SubscriptionService.Outcome.ACTIVE);
    }

    @Test
    void eraseRemovesEveryRecordAndIsAudited() {
        SubscriptionService subscriptions = service(PaymentGateway.test());
        subscriptions.subscribe("ada", Product.ID_PROTECTION);
        assertThat(subscriptions.forExport("ada")).hasSize(1);
        subscriptions.erase("ada", "ada@example.com");
        assertThat(subscriptions.forExport("ada")).isEmpty();
        assertThat(store.activeForEmail("ada@example.com", Product.ID_PROTECTION, NOW)).isFalse();
        verify(audit).record(AuditLog.Event.SUBSCRIPTIONS_ERASED, "user:ada", "user:ada", "OK");
    }

    @Test
    void propertiesHaveDefaultsAndRefuseNonsense() {
        SubscriptionProperties defaults = new SubscriptionProperties(null, " ", " ", null);
        assertThat(defaults.provider()).isEqualTo(SubscriptionProperties.Provider.NONE);
        assertThat(defaults.table()).isEqualTo("credit-agent-subscriptions");
        assertThat(defaults.currency()).isEqualTo("USD");
        assertThatThrownBy(() -> new SubscriptionProperties(null, null, null, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionProperties(null, null, "dollars", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkoutRequestsAndResultsAreChecked() {
        assertThatThrownBy(() -> new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 0, "USD", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentGateway.Paid(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentGateway.Redirect(null)).isInstanceOf(NullPointerException.class);
        assertThat(PaymentGateway.test().checkout(new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "k")))
                .isInstanceOf(PaymentGateway.Paid.class);
    }

    @Test
    void theTestGatewayIsRefusedUnlessDynamoDbIsLocal() {
        SubscriptionProperties test = new SubscriptionProperties(SubscriptionProperties.Provider.TEST, null, null, null);
        assertThat(SubscriptionConfiguration.gateway(test, auth("http://localhost:8000")).available()).isTrue();
        assertThat(SubscriptionConfiguration.gateway(test, auth("http://dynamodb:8000")).available()).isTrue();
        assertThatThrownBy(() -> SubscriptionConfiguration.gateway(test, auth(null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SubscriptionConfiguration.gateway(test, auth("https://dynamodb.us-east-1.amazonaws.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SubscriptionConfiguration.gateway(test, auth("http://local host:8000")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(SubscriptionConfiguration.gateway(new SubscriptionProperties(null, null, null, null), auth(null)).available())
                .isFalse();
    }

    static AuthProperties auth(String endpoint) {
        return new AuthProperties("j".repeat(32), "s".repeat(32), "credit-agent", java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(5), new AuthProperties.DynamoDb(endpoint, "us-east-1", "u", "s", false, null));
    }

    @Test
    void controllerErrorsMapToTheirStatuses() {
        SubscriptionController controller = new SubscriptionController(service(PaymentGateway.none()));
        assertThat(controller.notOffered(new ProductNotOfferedException()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.declined(new PaymentDeclinedException()).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        var unavailable = controller.paymentsUnavailable(new PaymentUnavailableException());
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(controller.storeUnavailable(SdkClientException.create("down")).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
