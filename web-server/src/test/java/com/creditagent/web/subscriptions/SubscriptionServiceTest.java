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
    void aPaymentWhoseTermCannotBeStoredIsStillAuditedForARefund() {
        SubscriptionRepository failing = mock(SubscriptionRepository.class);
        given(failing.findAll("ada")).willReturn(List.of());
        given(failing.activate(any(), anyString())).willThrow(SdkClientException.create("timeout"));
        assertThatThrownBy(() -> service(PaymentGateway.test(), failing).subscribe("ada", Product.ID_PROTECTION))
                .isInstanceOf(SdkClientException.class);
        ArgumentCaptor<Map<String, ?>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("user:ada"), eq("user:ada"), eq("FAILED"), detail.capture());
        assertThat(detail.getValue().get("paymentReference").toString()).startsWith("test_");
    }

    @Test
    void theSamePaymentConfirmedTwiceIsTheActiveTermNotARefund() {
        SubscriptionService subscriptions = service(PaymentGateway.test());
        SubscriptionService.Activation first = subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.ID_PROTECTION, "pay_1");
        assertThat(first.outcome()).isEqualTo(SubscriptionService.ActivationOutcome.ACTIVE);
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2027-11-21T10:00:00Z"));
        SubscriptionService.Activation again = subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.ID_PROTECTION, "pay_1");
        assertThat(again.outcome()).isEqualTo(SubscriptionService.ActivationOutcome.ALREADY_SAME_PAYMENT);
        assertThat(again.term()).isEqualTo(first.term());
        SubscriptionService.Activation other = subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.ID_PROTECTION, "pay_2");
        assertThat(other.outcome()).isEqualTo(SubscriptionService.ActivationOutcome.ALREADY_ACTIVE);
        assertThat(other.expiresAt()).isNull();
        verify(audit, org.mockito.Mockito.times(1)).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("service:payments"), eq("user:ada"),
                eq("OK"), any(Map.class));
        verify(audit, org.mockito.Mockito.times(1)).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("service:payments"), eq("user:ada"),
                eq("ALREADY_ACTIVE"), any(Map.class));
        // The repeat (ALREADY_SAME_PAYMENT) changed nothing and wrote no audit entry.
        verify(audit, org.mockito.Mockito.times(2)).record(any(), any(), any(), any(), any(Map.class));
    }

    @Test
    void anInternalActivationIsAuditedAsThePaymentsServiceAndABrowserPurchaseAsTheUser() {
        SubscriptionService subscriptions = service(PaymentGateway.test());
        subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.ID_PROTECTION, "pay_1");
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("service:payments"), eq("user:ada"), eq("OK"), any(Map.class));
        subscriptions.subscribe("ada", Product.ID_PROTECTION);
        verify(audit, org.mockito.Mockito.never()).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("user:ada"), any(), any(),
                any(Map.class));
        store.eraseAll("ada", "ada@example.com");
        subscriptions.subscribe("ada", Product.ID_PROTECTION);
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("user:ada"), eq("user:ada"), eq("OK"), any(Map.class));
        assertThatThrownBy(() -> subscriptions.activatePaid(null, "ada", Product.ID_PROTECTION, "pay_2"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void activationRefusesUnknownUsersAndProductsNotOffered() {
        SubscriptionService subscriptions = service(PaymentGateway.none());
        given(auth.account("ghost")).willReturn(Optional.empty());
        given(auth.account("noemail")).willReturn(Optional.of(new User("noemail", "$2a$h", null, "h", "6789", "k",
                Instant.EPOCH, ReportStatus.UNLOCKED)));
        assertThatThrownBy(() -> subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ghost", Product.ID_PROTECTION, "pay_1"))
                .isInstanceOf(UnknownSubscriberException.class);
        assertThatThrownBy(() -> subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "noemail", Product.ID_PROTECTION, "pay_1"))
                .isInstanceOf(UnknownSubscriberException.class);
        assertThatThrownBy(() -> subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.CREDIT_MONITORING, "pay_1"))
                .isInstanceOf(ProductNotOfferedException.class);
        assertThatThrownBy(() -> subscriptions.activatePaid(SubscriptionActivationController.ACTOR, "ada", null, "pay_1"))
                .isInstanceOf(ProductNotOfferedException.class);
        assertThat(store.findAll("ada")).isEmpty();
    }

    @Test
    void anActivationWhoseTermCannotBeStoredIsAuditedAsFailed() {
        SubscriptionRepository failing = mock(SubscriptionRepository.class);
        given(failing.activate(any(), anyString())).willThrow(SdkClientException.create("timeout"));
        assertThatThrownBy(() -> service(PaymentGateway.none(), failing).activatePaid(SubscriptionActivationController.ACTOR, "ada", Product.ID_PROTECTION, "pay_9"))
                .isInstanceOf(SdkClientException.class);
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("service:payments"), eq("user:ada"), eq("FAILED"), any(Map.class));
    }

    @Test
    void anActivationIsCheckedForATerm() {
        Subscription term = new Subscription("ada", Product.ID_PROTECTION, NOW, NOW.plusSeconds(1), "p");
        assertThatThrownBy(() -> new SubscriptionService.Activation(SubscriptionService.ActivationOutcome.ACTIVE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionService.Activation(SubscriptionService.ActivationOutcome.ALREADY_ACTIVE, term))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionService.Activation(null, term)).isInstanceOf(NullPointerException.class);
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
        ArgumentCaptor<Map<String, ?>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTIONS_ERASED), eq("user:ada"), eq("user:ada"), eq("OK"), detail.capture());
        assertThat(detail.getValue().get("ID_PROTECTION").toString()).startsWith("test_").contains("until 2027-11-21");
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
        assertThatThrownBy(() -> new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "usd", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String key : new String[] {"", "has space", "x".repeat(129), "café"}) {
            assertThatThrownBy(() -> new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", key))
                    .as(key).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "x".repeat(128)).returnUrl()).isNull();
        for (String url : new String[] {"/relative", "ftp://host/x", "javascript:alert(1)", "https:///nohost"}) {
            assertThatThrownBy(() -> new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "k", URI.create(url)))
                    .as(url).isInstanceOf(IllegalArgumentException.class);
        }
        URI back = URI.create("https://app.example.com/subscriptions/done");
        assertThat(new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "k", back).returnUrl()).isEqualTo(back);
        assertThat(new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "k", URI.create("http://localhost:8080/x"))
                .returnUrl()).isNotNull();
    }

    @Test
    void theIdempotencyKeyReachesTheGatewayUnchanged() {
        PaymentGateway hosted = mock(PaymentGateway.class);
        given(hosted.available()).willReturn(true);
        given(hosted.checkout(any())).willReturn(new PaymentGateway.Paid("pay_k"));
        new CachingPaymentGateway(hosted, java.time.Duration.ofSeconds(30), clock)
                .checkout(new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 1, "USD", "sub:ada:ID_PROTECTION:first"));
        ArgumentCaptor<PaymentGateway.CheckoutRequest> sent = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(hosted).checkout(sent.capture());
        assertThat(sent.getValue().idempotencyKey()).isEqualTo("sub:ada:ID_PROTECTION:first");
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

    @Test
    void activationAnswersMapToTheirStatuses() {
        SubscriptionActivationController controller = new SubscriptionActivationController(service(PaymentGateway.none()));
        var request = new SubscriptionActivationController.ActivationRequest("ada", "id_protection", "pay_1");
        var first = controller.activate(request);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().outcome()).isEqualTo(SubscriptionService.ActivationOutcome.ACTIVE);
        assertThat(first.getBody().expiresAt()).isEqualTo(Instant.parse("2027-11-21T10:00:00Z"));
        var repeat = controller.activate(request);
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getBody()).isEqualTo(new SubscriptionActivationController.ActivationResponse(
                SubscriptionService.ActivationOutcome.ALREADY_SAME_PAYMENT, first.getBody().expiresAt()));
        var other = controller.activate(new SubscriptionActivationController.ActivationRequest("ada", "ID_PROTECTION", "pay_2"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody()).isEqualTo(new SubscriptionActivationController.ActivationResponse(
                SubscriptionService.ActivationOutcome.ALREADY_ACTIVE, null));
        assertThatThrownBy(() -> controller.activate(new SubscriptionActivationController.ActivationRequest("ada", "gold", "p")))
                .isInstanceOf(ProductNotOfferedException.class);

        assertThat(controller.notOffered(new ProductNotOfferedException()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unknown = controller.unknownUser(new UnknownSubscriberException());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().getType()).hasToString("urn:creditagent:problem:unknown-user");
        verify(audit).record(eq(AuditLog.Event.SUBSCRIPTION_STARTED), eq("service:payments"), eq("user:ada"), eq("OK"), any(Map.class));
        var down = controller.storeUnavailable(SdkClientException.create("down"));
        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    }
}
