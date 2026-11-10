package com.creditagent.offers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.offers.OfferModel.ApprovalOdds;
import com.creditagent.offers.OfferModel.Category;
import com.creditagent.offers.OfferModel.Offer;
import com.creditagent.offers.OfferModel.OffersResponse;
import com.creditagent.offers.OfferModel.ScoreTier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfferEngineTest {

    static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    final OfferEngine engine = new OfferEngine(OfferMarketplace.placeholder(), Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(ints = {300, 520, 580, 640, 700, 760, 850})
    void atMostTenOffersRankedBestFirstAndAllEligible(int score) {
        OffersResponse response = engine.offers(score, "98101");
        assertThat(response.offers()).isNotEmpty().hasSizeLessThanOrEqualTo(OfferEngine.MAX_OFFERS);
        List<Double> matches = response.offers().stream().map(Offer::matchScore).toList();
        assertThat(matches).isSortedAccordingTo((a, b) -> Double.compare(b, a));
        for (int i = 0; i < response.offers().size(); i++) {
            Offer offer = response.offers().get(i);
            assertThat(offer.rank()).isEqualTo(i + 1);
            assertThat(offer.typicalMinScore()).isLessThanOrEqualTo(score);
        }
    }

    @Test
    void aLowScoreGetsSecuredAndBuilderCardsOnly() {
        OffersResponse response = engine.offers(520, "10001");
        assertThat(response.scoreTier()).isEqualTo(ScoreTier.POOR);
        assertThat(response.offers()).extracting(Offer::category)
                .allMatch(c -> c == Category.SECURED || c == Category.CREDIT_BUILDER);
        assertThat(response.offers()).extracting(Offer::productName).doesNotContain("Northwind Voyager Reserve");
    }

    @Test
    void anExcellentScoreSeesPremiumCardsNearTheTop() {
        OffersResponse response = engine.offers(820, null);
        assertThat(response.scoreTier()).isEqualTo(ScoreTier.EXCEPTIONAL);
        assertThat(response.offers()).hasSize(10);
        assertThat(response.offers()).extracting(Offer::category)
                .doesNotContain(Category.SECURED, Category.CREDIT_BUILDER);
        assertThat(response.offers().getFirst().approvalOdds()).isEqualTo(ApprovalOdds.HIGH);
    }

    @Test
    void goodCreditSeesCardsForItsTierFirstAndNoStarterCards() {
        OffersResponse response = engine.offers(760, "98101");
        assertThat(response.offers()).extracting(Offer::category)
                .doesNotContain(Category.SECURED, Category.CREDIT_BUILDER);
        // Every likely card for good credit (minimum 640 or more) ranks above every card for fair credit or
        // students. A card the score only just reaches (LOW odds) may rank last: a long shot comes after
        // cards the user is sure to get.
        List<Integer> minimums = response.offers().stream().filter(o -> o.approvalOdds() != ApprovalOdds.LOW)
                .map(Offer::typicalMinScore).toList();
        int lastGood = -1;
        int firstFair = minimums.size();
        for (int i = 0; i < minimums.size(); i++) {
            if (minimums.get(i) >= 640) {
                lastGood = i;
            } else {
                firstFair = Math.min(firstFair, i);
            }
        }
        assertThat(lastGood).as(minimums.toString()).isLessThan(firstFair);
        assertThat(response.offers().getLast().approvalOdds()).isEqualTo(ApprovalOdds.LOW);
        // Below 670, starter cards are still offered.
        assertThat(engine.offers(669, null).offers()).extracting(Offer::category).contains(Category.SECURED);
    }

    @Test
    void fitFallsForCardsMeantForMuchLowerScores() {
        OfferModel.CardProduct secured = OfferMarketplace.placeholder().products().stream()
                .filter(p -> p.productId().equals("tb-secured")).findFirst().orElseThrow();
        assertThat(OfferEngine.fit(secured, 380)).isEqualTo(1.0);
        assertThat(OfferEngine.fit(secured, 580)).isZero();
        assertThat(OfferEngine.fit(secured, 480)).isEqualTo(0.5);
    }

    @Test
    void regionalOffersAreOnlyForTheirStatesAndGetALocalLift() {
        assertThat(engine.offers(720, "98101").state()).isEqualTo("WA");
        assertThat(engine.offers(720, "98101").offers()).extracting(Offer::issuer).contains("Fabrikam Federal Credit Union");
        assertThat(engine.offers(720, "73301").offers()).extracting(Offer::issuer)
                .contains("Lone Star Credit Union").doesNotContain("Fabrikam Federal Credit Union");
        assertThat(engine.offers(720, null).offers()).extracting(Offer::availability).containsOnly("Nationwide");
        assertThat(engine.offers(720, "09001").state()).isNull(); // military prefix: nationwide only
        Offer local = engine.offers(720, "98101").offers().stream()
                .filter(o -> o.issuer().startsWith("Fabrikam")).findFirst().orElseThrow();
        assertThat(local.availability()).isEqualTo("Residents of ID, OR, WA");
    }

    @Test
    void lowerScoresAreQuotedHigherAprsInTheRange() {
        OfferModel.CardProduct product = OfferMarketplace.placeholder().products().stream()
                .filter(p -> p.productId().equals("ws-double")).findFirst().orElseThrow();
        assertThat(OfferEngine.likelyApr(product, 690)).isEqualTo(product.aprMax());
        assertThat(OfferEngine.likelyApr(product, 850)).isEqualTo(product.aprMin());
        assertThat(OfferEngine.likelyApr(product, 770)).isBetween(product.aprMin(), product.aprMax());
    }

    @Test
    void approvalOddsFollowTheMarginOverTheMinimum() {
        assertThat(OfferEngine.odds(1.0)).isEqualTo(ApprovalOdds.HIGH);
        assertThat(OfferEngine.odds(0.5)).isEqualTo(ApprovalOdds.MEDIUM);
        assertThat(OfferEngine.odds(0.1)).isEqualTo(ApprovalOdds.LOW);
    }

    @Test
    void eachOfferCarriesItsSchumerBoxTerms() {
        Offer offer = engine.offers(760, "94105").offers().getFirst();
        assertThat(offer.terms().purchaseApr().yourRate()).isBetween(offer.terms().purchaseApr().min(), offer.terms().purchaseApr().max());
        assertThat(offer.terms().gracePeriodDays()).isEqualTo(25);
        assertThat(offer.terms().cashAdvanceApr()).isPositive();
        assertThat(offer.expiresAt()).isEqualTo(NOW.plus(OfferEngine.OFFER_LIFETIME));
        assertThat(offer.disclosure()).contains("Not a pre-approval");
        assertThat(offer.headline()).isNotBlank();
        OffersResponse response = engine.offers(760, "94105-1234");
        assertThat(response.zip()).isEqualTo("94105");
        assertThat(response.disclaimer()).contains("not firm offers");
        assertThat(response.source()).isEqualTo("placeholder-marketplace");
    }

    @Test
    void securedCardsShowTheirDepositAndNoPenaltyApr() {
        Offer secured = engine.offers(400, null).offers().stream()
                .filter(o -> o.category() == Category.SECURED).findFirst().orElseThrow();
        assertThat(secured.terms().securityDeposit()).isPositive();
        assertThat(secured.terms().penaltyApr()).isNull();
        assertThat(secured.headline()).contains("deposit");
    }

    @Test
    void everyCategoryHasAHeadline() {
        for (OfferModel.CardProduct product : OfferMarketplace.placeholder().products()) {
            assertThat(OfferEngine.headline(product)).as(product.productId()).isNotBlank();
        }
    }

    @Test
    void badInputIsRefused() {
        assertThatThrownBy(() -> engine.offers(299, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.offers(851, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.offers(700, "1234")).isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.offers(700, "  ").zip()).isNull();
    }

    @Test
    void zipPrefixesMapToStates() {
        assertThat(ZipRegions.stateOf("10001")).hasValue("NY");
        assertThat(ZipRegions.stateOf("60601")).hasValue("IL");
        assertThat(ZipRegions.stateOf("99501")).hasValue("AK");
        assertThat(ZipRegions.stateOf("00100")).isEmpty();
        assertThat(ZipRegions.stateOf("abcde")).isEmpty();
        assertThat(ZipRegions.stateOf(null)).isEmpty();
    }
}
