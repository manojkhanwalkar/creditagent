package com.creditagent.web.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.creditagent.web.CreditScoreService;
import com.creditagent.web.auth.AuthService;
import com.creditagent.web.auth.ReportStatus;
import com.creditagent.web.auth.User;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OffersServiceTest {

    final AuthService auth = mock(AuthService.class);
    final OffersClient retrying = mock(OffersClient.class);
    final OffersClient once = mock(OffersClient.class);
    final OffersService offers = new OffersService(auth, retrying, once);

    static User scored() {
        return new User("ada", "h", "e", "p", "s", "6789", "k", Instant.EPOCH, ReportStatus.UNLOCKED, "98101", 720, Instant.EPOCH);
    }

    @Test
    void loginUsesTheSingleTryClientAndGetTheRetryingOne() {
        given(auth.account("ada")).willReturn(Optional.of(scored()));
        given(once.offers(720, "98101")).willReturn(JsonMapper.builder().build().readTree("{\"offers\":[]}"));
        given(retrying.offers(720, "98101")).willReturn(JsonMapper.builder().build().readTree("{\"offers\":[]}"));
        assertThat(offers.offersFor("ada").status()).isEqualTo(OffersService.Status.OK);
        verify(retrying, never()).offers(anyInt(), any());
        assertThat(offers.current("ada").status()).isEqualTo(OffersService.Status.OK);
        verify(retrying).offers(720, "98101");
    }

    @Test
    void anyFailureIsUnavailableNeverAnException() {
        given(auth.account("ada")).willThrow(new IllegalStateException("DynamoDB throttled"));
        assertThat(offers.offersFor("ada").status()).isEqualTo(OffersService.Status.UNAVAILABLE);
        assertThat(offers.current("ada").status()).isEqualTo(OffersService.Status.UNAVAILABLE);
    }

    @Test
    void theProductionScoreRecorderIsRegisteredWithTheAuthService() {
        ScoreRecorder recorder = new OffersConfiguration().scoreRecorder(auth, mock(CreditScoreService.class));
        verify(auth).addRegistrationListener(recorder);
    }
}
