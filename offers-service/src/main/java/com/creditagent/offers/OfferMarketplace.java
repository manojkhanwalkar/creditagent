package com.creditagent.offers;

import com.creditagent.offers.OfferModel.CardProduct;
import com.creditagent.offers.OfferModel.Category;
import com.creditagent.offers.OfferModel.Fees;
import com.creditagent.offers.OfferModel.IntroRate;
import java.util.List;
import java.util.Set;

/**
 * Where card products come from. Later this is a marketplace where many issuers present their
 * offers; for now {@link #placeholder()} is a fixed catalogue of fictional issuers.
 */
@FunctionalInterface
public interface OfferMarketplace {

    /** Every product currently on offer, before eligibility or ranking. */
    List<CardProduct> products();

    /**
     * Fictional products modelled on the market: typical score floors per card type (secured: none;
     * student and fair-credit: about 580; cash back and low interest: about 670; balance transfer and
     * travel: 690–740), and regional credit unions open only in their states.
     */
    static OfferMarketplace placeholder() {
        List<CardProduct> catalogue = List.of(
                product("nw-sapphire", "Northwind Bank", "Northwind Voyager Reserve", "Visa", Category.PREMIUM_TRAVEL, 740,
                        Set.of(), "points", 3.0, "60,000 bonus points after $4,000 in 3 months; 3x on travel and dining",
                        395, null, null, 21.49, 28.49, 5, 0, null, BANK),
                product("ct-explorer", "Contoso Card", "Contoso Explorer", "Mastercard", Category.TRAVEL_REWARDS, 700,
                        Set.of(), "miles", 2.0, "2x miles on everything; 20,000 bonus miles after $1,000 in 3 months",
                        0, new IntroRate(0, 12), null, 19.99, 27.99, 3, 0, null, BANK),
                product("ws-double", "Woodgrove Bank", "Woodgrove Double Cash", "Visa", Category.CASH_BACK, 690,
                        Set.of(), "cash back", 2.0, "2% on every purchase", 0, null, new IntroRate(0, 18),
                        19.24, 29.24, 3, 3, null, BANK),
                product("tb-quarters", "Tailspin Bank", "Tailspin Quarters", "Discover", Category.ROTATING_CASH_BACK, 670,
                        Set.of(), "cash back", 1.0, "5% in categories that change each quarter (up to $1,500)", 0,
                        new IntroRate(0, 15), new IntroRate(0, 15), 18.24, 27.24, 3, 0, null, BANK),
                product("lw-transfer", "Litware Financial", "Litware Balance Transfer", "Visa", Category.BALANCE_TRANSFER, 690,
                        Set.of(), "none", 0, "0% on balance transfers for 21 months", 0, new IntroRate(0, 12),
                        new IntroRate(0, 21), 18.24, 28.99, 5, 3, null, BANK),
                product("ws-lowrate", "Woodgrove Bank", "Woodgrove Low Rate", "Mastercard", Category.LOW_INTEREST, 670,
                        Set.of(), "none", 0, "0% on purchases for 15 months, then a low variable rate", 0,
                        new IntroRate(0, 15), new IntroRate(0, 15), 16.49, 25.49, 3, 0, null, BANK),
                product("ct-everyday", "Contoso Card", "Contoso Everyday Cash", "Mastercard", Category.CASH_BACK, 670,
                        Set.of(), "cash back", 1.5, "1.5% on everything, 3% at grocery stores", 0, null, null,
                        20.24, 29.24, 3, 3, null, BANK),
                product("tb-fair", "Tailspin Bank", "Tailspin Platinum Fair", "Mastercard", Category.FAIR_CREDIT, 580,
                        Set.of(), "cash back", 1.0, "1% back; credit line review after 6 on-time months", 0, null, null,
                        26.24, 29.99, 4, 3, null, BANK),
                product("nw-student", "Northwind Bank", "Northwind Student Cash", "Visa", Category.STUDENT, 580,
                        Set.of(), "cash back", 1.0, "1% back, plus 1% more for each year of on-time payments", 0, null,
                        null, 18.99, 27.99, 3, 0, null, BANK),
                product("lw-builder", "Litware Financial", "Litware Credit Builder", "Mastercard", Category.CREDIT_BUILDER, 550,
                        Set.of(), "none", 0, "No deposit; reports to all three bureaus every month", 39, null, null,
                        29.99, 29.99, 0, 3, null, STARTER),
                product("tb-secured", "Tailspin Bank", "Tailspin Secured", "Visa", Category.SECURED, 300,
                        Set.of(), "cash back", 1.0, "Deposit from $200 sets the limit; upgrade review after 7 months", 0,
                        null, null, 28.24, 28.24, 3, 0, 200, STARTER),
                product("nw-secured", "Northwind Bank", "Northwind Secured Starter", "Mastercard", Category.SECURED, 300,
                        Set.of(), "none", 0, "Deposit from $49 for a $200 limit", 0, null, null, 29.99, 29.99, 0, 3, 49, STARTER),
                product("fcu-rewards", "Fabrikam Federal Credit Union", "Fabrikam Member Rewards", "Visa",
                        Category.CASH_BACK, 650, Set.of("WA", "OR", "ID"), "cash back", 1.5,
                        "1.5% back; membership open to residents", 0, null, new IntroRate(1.99, 12), 14.99, 18.00, 2, 0, null, FEDERAL_CREDIT_UNION),
                product("pcu-plat", "Pacific Coast Credit Union", "Pacific Platinum", "Visa", Category.LOW_INTEREST, 640,
                        Set.of("CA", "NV", "AZ"), "none", 0, "Low fixed-range APR for members", 0, new IntroRate(0, 9),
                        new IntroRate(0, 9), 13.49, 17.99, 3, 0, null, STATE_CREDIT_UNION_FIXED),
                product("lscu-cash", "Lone Star Credit Union", "Lone Star Cash Back", "Mastercard", Category.CASH_BACK, 660,
                        Set.of("TX", "OK", "NM"), "cash back", 2.0, "2% on gas and groceries, 1% elsewhere", 0, null, null,
                        15.99, 18.00, 2, 1, null, STATE_CREDIT_UNION),
                product("esb-travel", "Empire Savings Bank", "Empire Commuter Rewards", "Visa", Category.TRAVEL_REWARDS, 700,
                        Set.of("NY", "NJ", "CT", "PA"), "points", 2.0, "3x on transit and rideshare, 1x elsewhere", 0, null,
                        null, 17.99, 26.99, 3, 0, null, BANK),
                product("glcu-start", "Great Lakes Credit Union", "Great Lakes Fresh Start", "Visa", Category.CREDIT_BUILDER, 500,
                        Set.of("MI", "OH", "WI", "IL", "IN", "MN"), "none", 0, "Small limit, no annual fee, free credit coaching",
                        0, null, null, 17.99, 18.00, 0, 0, null, STATE_CREDIT_UNION),
                product("tb-elite", "Tailspin Bank", "Tailspin Elite Cash", "Discover", Category.CASH_BACK, 760,
                        Set.of(), "cash back", 2.5, "2.5% on everything for members with excellent credit", 95, null,
                        new IntroRate(0, 12), 17.24, 24.24, 3, 0, null, BANK));
        return () -> catalogue;
    }

    /** Typical published terms for a national bank card. */
    Fees BANK = new Fees(29.99, 29.99, 5.0, 41, 25, true);
    /** Starter cards: no penalty APR. */
    Fees STARTER = new Fees(null, 29.99, 5.0, 25, 25, true);
    /** Federal credit unions may not charge more than 18% APR (NCUA); no penalty rate above it. */
    Fees FEDERAL_CREDIT_UNION = new Fees(18.00, 18.00, 2.0, 25, 25, true);
    /** A state credit union card with a fixed-range rate. */
    Fees STATE_CREDIT_UNION_FIXED = new Fees(17.99, 17.99, 3.0, 30, 25, false);
    Fees STATE_CREDIT_UNION = new Fees(18.00, 18.00, 3.0, 25, 25, true);

    private static CardProduct product(String id, String issuer, String name, String network, Category category,
            int minScore, Set<String> states, String rewardsType, double rewardsRate, String bonus, int annualFee,
            IntroRate introPurchase, IntroRate introBalanceTransfer, double aprMin, double aprMax, double btFee,
            double foreignFee, Integer deposit, Fees fees) {
        return new CardProduct(id, issuer, name, network, category, minScore, states, rewardsType, rewardsRate, bonus,
                annualFee, introPurchase, introBalanceTransfer, aprMin, aprMax, btFee, foreignFee, deposit, fees);
    }
}
