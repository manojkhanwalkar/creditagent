package com.creditagent.offers;

import com.creditagent.offers.OfferModel.Apr;
import com.creditagent.offers.OfferModel.ApprovalOdds;
import com.creditagent.offers.OfferModel.CardProduct;
import com.creditagent.offers.OfferModel.Category;
import com.creditagent.offers.OfferModel.Offer;
import com.creditagent.offers.OfferModel.OffersResponse;
import com.creditagent.offers.OfferModel.Rewards;
import com.creditagent.offers.OfferModel.ScoreTier;
import com.creditagent.offers.OfferModel.Terms;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a credit score and a ZIP code into at most {@link #MAX_OFFERS} ranked card offers.
 *
 * <ol>
 *   <li><b>Eligible:</b> the score reaches the product's typical minimum, and a regional product is
 *       open in the ZIP code's state (without a usable ZIP, only nationwide products).
 *   <li><b>Priced:</b> the APR quoted for this score sits in the product's range, higher for lower
 *       scores, as issuers price risk.
 *   <li><b>Suitable:</b> cards for building or rebuilding credit (secured, credit builder, student,
 *       fair credit) are offered only below {@link #GOOD_CREDIT} (670), where mainstream cards are
 *       not yet in reach. A student card also needs a student, which a score cannot tell.
 *   <li><b>Valid:</b> a product whose data fails {@link #valid} (ids, lengths, printable text) is
 *       skipped, so marketplace text cannot carry markup or instructions into agents' context.
 *   <li><b>Ranked</b> by a match score from 0 to 1:
 *       <ul>
 *         <li>35% approval likelihood: how far the score clears the minimum, full at 80 points above;
 *         <li>20% fit: a card meant for scores far below this one (more than 80 points) is marked
 *             down, so a 760 sees cards for good credit before cards for fair credit;
 *         <li>25% value: estimated yearly rewards on $15,000 of spending, less the annual fee;
 *         <li>15% cost: a lower likely APR and longer 0% periods score higher;
 *         <li>5% local: regional products in the user's state get a small lift.
 *       </ul>
 * </ol>
 */
public class OfferEngine {

    public static final int MAX_OFFERS = 10;
    /** The "good" credit floor: at or above it, starter cards are no longer offered. */
    static final int GOOD_CREDIT = 670;
    static final int YEARLY_SPEND = 15_000;
    static final Duration OFFER_LIFETIME = Duration.ofDays(30);
    static final String DISCLAIMER = "These are not firm offers of credit. Approval, credit limit and APR depend on the"
            + " issuer's full review of your application. Rates are variable and shown as of the generated date.";
    static final String OFFER_DISCLOSURE = "Matched on your credit score and ZIP code only. Not a pre-approval. See the"
            + " issuer's full terms before applying.";

    private final OfferMarketplace marketplace;
    private final Clock clock;

    public OfferEngine(OfferMarketplace marketplace, Clock clock) {
        this.marketplace = Objects.requireNonNull(marketplace);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param score 300 to 850
     * @param zip a 5-digit ZIP code (ZIP+4 accepted), or null for nationwide offers only
     * @throws IllegalArgumentException if the score is out of range or the ZIP code malformed
     */
    public OffersResponse offers(int score, String zip) {
        if (score < 300 || score > 850) {
            throw new IllegalArgumentException("score must be between 300 and 850");
        }
        String cleanZip = zip == null || zip.isBlank() ? null : zip.strip();
        if (cleanZip != null && !ZipRegions.ZIP.matcher(cleanZip).matches()) {
            throw new IllegalArgumentException("zip must be a 5-digit US ZIP code");
        }
        String zip5 = cleanZip == null ? null : cleanZip.substring(0, 5);
        Optional<String> state = ZipRegions.stateOf(zip5);
        Instant now = clock.instant();

        List<Scored> eligible = new ArrayList<>();
        List<CardProduct> products = marketplace.products();
        for (CardProduct product : products.subList(0, Math.min(products.size(), MAX_PRODUCTS))) {
            if (!valid(product)) {
                continue;
            }
            boolean inArea = product.nationwide() || state.map(product.states()::contains).orElse(false);
            if (score >= product.minScore() && inArea && suitable(product, score)) {
                eligible.add(new Scored(product, matchScore(product, score, state.orElse(null))));
            }
        }
        eligible.sort(Comparator.comparingDouble((Scored s) -> s.match()).reversed()
                .thenComparing((Scored s) -> s.product().productId()));

        List<Offer> offers = new ArrayList<>();
        for (Scored scored : eligible.subList(0, Math.min(MAX_OFFERS, eligible.size()))) {
            offers.add(offer(scored, offers.size() + 1, score, now));
        }
        return new OffersResponse(score, ScoreTier.of(score), zip5, state.orElse(null), now, "placeholder-marketplace",
                offers, DISCLAIMER);
    }

    private record Scored(CardProduct product, double match) {}

    static final java.util.Set<Category> BUILDING_CREDIT =
            java.util.EnumSet.of(Category.SECURED, Category.CREDIT_BUILDER, Category.STUDENT, Category.FAIR_CREDIT);

    static boolean suitable(CardProduct product, int score) {
        return !BUILDING_CREDIT.contains(product.category()) || score < GOOD_CREDIT;
    }

    static final java.util.regex.Pattern PRODUCT_ID = java.util.regex.Pattern.compile("^[a-z0-9-]{1,40}$");
    /** Printable text: letters, digits, spaces and ordinary punctuation; no markup, control or invisible characters. */
    static final java.util.regex.Pattern SAFE_TEXT = java.util.regex.Pattern.compile("^[\\p{L}\\p{N} .,;:'%$&()/+\\-]*$");
    static final int MAX_PRODUCTS = 500;

    /** Whether a marketplace product is safe to rank and pass on to people and agents. */
    static boolean valid(CardProduct p) {
        return p != null && PRODUCT_ID.matcher(p.productId()).matches()
                && text(p.issuer(), 80) && text(p.name(), 80) && text(p.network(), 20) && text(p.rewardsType(), 20)
                && text(p.rewardsBonus(), 200) && p.category() != null && p.states() != null && p.fees() != null
                && p.minScore() >= 300 && p.minScore() <= 850 && p.aprMin() >= 0 && p.aprMax() >= p.aprMin()
                && p.aprMax() <= 36;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && SAFE_TEXT.matcher(value).matches();
    }

    /** 1 while the card's minimum is within 80 points of the score, falling to 0 at 280 below. */
    static double fit(CardProduct product, int score) {
        double gap = score - product.minScore() - 80;
        return 1 - Math.min(1.0, Math.max(0.0, gap / 200.0));
    }

    /** A card with no minimum (300, the bottom of the scale) is always within reach. */
    static double approval(CardProduct product, int score) {
        if (product.minScore() <= 300) {
            return 1.0;
        }
        return Math.min(1.0, Math.max(0.0, (score - product.minScore()) / 80.0));
    }

    static ApprovalOdds odds(double approval) {
        if (approval >= 0.75) {
            return ApprovalOdds.HIGH;
        }
        return approval >= 0.35 ? ApprovalOdds.MEDIUM : ApprovalOdds.LOW;
    }

    /** Where in the APR range this score lands: the top of the range at the minimum, the bottom at 850. */
    static double likelyApr(CardProduct product, int score) {
        double span = Math.max(1, 850 - product.minScore());
        double position = Math.min(1.0, Math.max(0.0, (score - product.minScore()) / span));
        double rate = product.aprMax() - position * (product.aprMax() - product.aprMin());
        return Math.round(rate * 100) / 100.0;
    }

    static double yearlyValue(CardProduct product) {
        double rewards = product.rewardsType().equals("none") ? 0 : YEARLY_SPEND * product.rewardsRate() / 100.0;
        return rewards - product.annualFee();
    }

    static double matchScore(CardProduct product, int score, String state) {
        double approval = approval(product, score);
        // $0 to $450 of yearly value maps to 0..1; a fee bigger than the rewards scores 0.
        double value = Math.min(1.0, Math.max(0.0, yearlyValue(product) / 450.0));
        int introMonths = Math.max(
                product.introPurchase() == null ? 0 : product.introPurchase().months(),
                product.introBalanceTransfer() == null ? 0 : product.introBalanceTransfer().months());
        double cost = 0.6 * (1 - Math.min(1.0, likelyApr(product, score) / 30.0)) + 0.4 * Math.min(1.0, introMonths / 21.0);
        double local = state != null && !product.nationwide() && product.states().contains(state) ? 1.0 : 0.0;
        double match = 0.35 * approval + 0.20 * fit(product, score) + 0.25 * value + 0.15 * cost + 0.05 * local;
        return Math.round(match * 1000) / 1000.0;
    }

    private Offer offer(Scored scored, int rank, int score, Instant now) {
        CardProduct p = scored.product();
        double apr = likelyApr(p, score);
        boolean variable = p.fees().variableRate();
        Apr purchase = new Apr(p.introPurchase(), p.aprMin(), p.aprMax(), apr, variable);
        Apr transfer = new Apr(p.introBalanceTransfer(), p.aprMin(), p.aprMax(), apr, variable);
        // Every term comes from the product as published; nothing is filled in here.
        Terms terms = new Terms(purchase, transfer, p.fees().cashAdvanceApr(), p.fees().penaltyApr(), p.annualFee(),
                p.balanceTransferFeePercent(), p.fees().cashAdvanceFeePercent(), p.foreignTransactionFeePercent(),
                p.fees().latePaymentFeeMax(), p.fees().gracePeriodDays(), p.securityDeposit());
        Rewards rewards = new Rewards(p.rewardsType(), p.rewardsRate(), p.rewardsBonus(),
                Math.round(yearlyValue(p) * 100) / 100.0);
        return new Offer(
                p.productId() + "-" + score,
                rank,
                p.issuer(),
                p.name(),
                p.network(),
                p.category(),
                headline(p),
                rewards,
                terms,
                odds(approval(p, score)),
                p.minScore(),
                p.nationwide() ? "Nationwide" : "Residents of " + String.join(", ", p.states().stream().sorted().toList()),
                scored.match(),
                "https://offers.example.invalid/apply/" + p.productId(),
                now.plus(OFFER_LIFETIME),
                OFFER_DISCLOSURE);
    }

    static String headline(CardProduct p) {
        String fee = p.annualFee() == 0 ? "no annual fee" : String.format(Locale.ROOT, "$%d annual fee", p.annualFee());
        return switch (p.category()) {
            // The issuer's own description of the rewards, never a rate reworded here.
            case PREMIUM_TRAVEL, TRAVEL_REWARDS, CASH_BACK, ROTATING_CASH_BACK -> p.rewardsBonus() + "; " + fee;
            case BALANCE_TRANSFER -> String.format(Locale.ROOT, "0%% intro APR on balance transfers for %d months, %s",
                    p.introBalanceTransfer().months(), fee);
            case LOW_INTEREST -> String.format(Locale.ROOT, "Low variable APR from %s%%, %s", trim(p.aprMin()), fee);
            case FAIR_CREDIT -> "For fair credit: rewards while you build, " + fee;
            case STUDENT -> "For students building credit, " + fee;
            case CREDIT_BUILDER -> "Builds credit with on-time payments, " + fee;
            case SECURED -> String.format(Locale.ROOT, "Secured card: a $%d deposit sets your limit, %s",
                    p.securityDeposit(), fee);
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
