package com.creditagent.offers;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The offers message format. Each offer carries the terms a US card solicitation must disclose in its
 * Schumer box (Regulation Z, 12 CFR 1026.60): purchase, balance transfer, cash advance and penalty
 * APRs, the annual fee, transaction fees and the grace period. Rates are percentages, fees whole US
 * dollars or a percentage of the amount, as the field name says.
 */
public final class OfferModel {

    private OfferModel() {}

    /** What a card is for; drives ranking and the headline. */
    public enum Category {
        PREMIUM_TRAVEL, TRAVEL_REWARDS, CASH_BACK, ROTATING_CASH_BACK, BALANCE_TRANSFER, LOW_INTEREST,
        FAIR_CREDIT, STUDENT, CREDIT_BUILDER, SECURED
    }

    /** How likely approval is, from how far the score clears the card's typical minimum. */
    public enum ApprovalOdds { HIGH, MEDIUM, LOW }

    /** FICO-style bands (300–850). */
    public enum ScoreTier {
        EXCEPTIONAL(800), VERY_GOOD(740), GOOD(670), FAIR(580), POOR(300);

        final int floor;

        ScoreTier(int floor) {
            this.floor = floor;
        }

        static ScoreTier of(int score) {
            for (ScoreTier tier : values()) {
                if (score >= tier.floor) {
                    return tier;
                }
            }
            return POOR;
        }
    }

    /** An introductory rate: {@code rate} percent for {@code months} months. */
    public record IntroRate(double rate, int months) {}

    /**
     * An APR: an optional intro rate, then a variable range; {@code yourRate} is where this score is
     * likely to land in that range (lower scores, higher rates).
     */
    public record Apr(IntroRate intro, double min, double max, double yourRate, boolean variable) {}

    /** The Schumer box, as the issuer publishes it; a null term is unknown ("see issuer terms"). */
    public record Terms(
            Apr purchaseApr,
            Apr balanceTransferApr,
            Double cashAdvanceApr,
            Double penaltyApr,
            int annualFee,
            double balanceTransferFeePercent,
            Double cashAdvanceFeePercent,
            double foreignTransactionFeePercent,
            Integer latePaymentFeeMax,
            Integer gracePeriodDays,
            Integer securityDeposit) {}

    /** What the card earns. {@code baseRate} is percent cash back or points per dollar. */
    public record Rewards(String type, double baseRate, String bonus, double estimatedAnnualValue) {}

    /**
     * The cost terms a product's issuer publishes, beyond its purchase APR range. Never computed here:
     * an unknown term is null and is shown as "see issuer terms".
     *
     * @param penaltyApr null when the card has none (or the issuer does not say)
     * @param variableRate whether the purchase APR is variable (follows the prime rate)
     */
    public record Fees(
            Double penaltyApr,
            Double cashAdvanceApr,
            Double cashAdvanceFeePercent,
            Integer latePaymentFeeMax,
            Integer gracePeriodDays,
            boolean variableRate) {}

    /** One product as the marketplace lists it, before it is fitted to a score. */
    public record CardProduct(
            String productId,
            String issuer,
            String name,
            String network,
            Category category,
            int minScore,
            Set<String> states,
            String rewardsType,
            double rewardsRate,
            String rewardsBonus,
            int annualFee,
            IntroRate introPurchase,
            IntroRate introBalanceTransfer,
            double aprMin,
            double aprMax,
            double balanceTransferFeePercent,
            double foreignTransactionFeePercent,
            Integer securityDeposit,
            Fees fees) {

        public boolean nationwide() {
            return states.isEmpty();
        }
    }

    /** One offer, fitted to the score and ranked. */
    public record Offer(
            String offerId,
            int rank,
            String issuer,
            String productName,
            String network,
            Category category,
            String headline,
            Rewards rewards,
            Terms terms,
            ApprovalOdds approvalOdds,
            int typicalMinScore,
            String availability,
            double matchScore,
            String applyUrl,
            Instant expiresAt,
            String disclosure) {}

    /** The answer to one request. */
    public record OffersResponse(
            int score,
            ScoreTier scoreTier,
            String zip,
            String state,
            Instant generatedAt,
            String source,
            List<Offer> offers,
            String disclaimer) {

        public OffersResponse {
            offers = List.copyOf(offers);
        }
    }
}
