package com.creditagent.web.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.creditagent.subscriptions.Product;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CachingPaymentGatewayTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    final PaymentGateway delegate = mock(PaymentGateway.class);
    final CachingPaymentGateway gateway = new CachingPaymentGateway(delegate, Duration.ofSeconds(30), clock);
    final PaymentGateway.CheckoutRequest request =
            new PaymentGateway.CheckoutRequest("ada", Product.ID_PROTECTION, 2999, "USD", "sub:ada:ID_PROTECTION:first");

    @Test
    void availabilityIsAskedOnceWithinTheWindowAndAgainAfterIt() {
        given(delegate.available()).willReturn(true, false);
        for (int i = 0; i < 5; i++) {
            assertThat(gateway.available()).isTrue();
        }
        now.set(now.get().plusSeconds(29));
        assertThat(gateway.available()).isTrue();
        verify(delegate, times(1)).available();

        now.set(now.get().plusSeconds(1));
        assertThat(gateway.available()).isFalse();
        verify(delegate, times(2)).available();
    }

    @Test
    void checkoutsAlwaysReachTheGatewayAndAnUnavailableProviderIsAskedAgain() {
        given(delegate.available()).willReturn(true, false);
        given(delegate.checkout(any())).willReturn(new PaymentGateway.Paid("pay_1")).willThrow(new PaymentUnavailableException());
        assertThat(gateway.available()).isTrue();
        assertThat(gateway.checkout(request)).isEqualTo(new PaymentGateway.Paid("pay_1"));
        assertThat(gateway.available()).isTrue();
        assertThatThrownBy(() -> gateway.checkout(request)).isInstanceOf(PaymentUnavailableException.class);
        assertThat(gateway.available()).isFalse();
        verify(delegate, times(2)).available();
        verify(delegate, times(2)).checkout(request);
    }

    @Test
    void aDeclineDoesNotForgetTheAnswer() {
        given(delegate.available()).willReturn(true);
        given(delegate.checkout(any())).willThrow(new PaymentDeclinedException());
        gateway.available();
        assertThatThrownBy(() -> gateway.checkout(request)).isInstanceOf(PaymentDeclinedException.class);
        gateway.available();
        verify(delegate, times(1)).available();
    }

    @Test
    void theWindowMustBePositive() {
        assertThatThrownBy(() -> new CachingPaymentGateway(delegate, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingPaymentGateway(delegate, Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
