package com.creditagent.offers;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The US state a ZIP code is in, from its first three digits (the USPS sectional center prefix). Used
 * only to decide which regional offers (credit unions, state banks) a user may see. A few prefixes
 * straddle borders; the state the prefix mostly serves is used. Military (APO/FPO: 090–099, 340,
 * 962–966) and unassigned prefixes have no state.
 */
public final class ZipRegions {

    public static final Pattern ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");

    /** Ranges of 3-digit prefixes, inclusive, and their state: {from, to, state}. */
    private static final Object[][] RANGES = {
        {5, 5, "NY"}, {6, 7, "PR"}, {8, 8, "VI"}, {9, 9, "PR"},
        {10, 27, "MA"}, {28, 29, "RI"}, {30, 38, "NH"}, {39, 49, "ME"}, {50, 54, "VT"}, {55, 55, "MA"},
        {56, 59, "VT"}, {60, 69, "CT"}, {70, 89, "NJ"}, {100, 149, "NY"}, {150, 196, "PA"}, {197, 199, "DE"},
        {200, 200, "DC"}, {201, 201, "VA"}, {202, 205, "DC"}, {206, 219, "MD"}, {220, 246, "VA"}, {247, 268, "WV"}, {270, 289, "NC"},
        {290, 299, "SC"}, {300, 319, "GA"}, {320, 339, "FL"}, {341, 349, "FL"}, {350, 369, "AL"}, {370, 385, "TN"},
        {386, 397, "MS"}, {398, 399, "GA"}, {400, 427, "KY"}, {430, 459, "OH"}, {460, 479, "IN"},
        {480, 499, "MI"}, {500, 528, "IA"}, {530, 549, "WI"}, {550, 567, "MN"}, {570, 577, "SD"},
        {580, 588, "ND"}, {590, 599, "MT"}, {600, 629, "IL"}, {630, 658, "MO"}, {660, 679, "KS"},
        {680, 693, "NE"}, {700, 714, "LA"}, {716, 729, "AR"}, {730, 732, "OK"}, {733, 733, "TX"}, {734, 749, "OK"}, {750, 799, "TX"},
        {800, 816, "CO"}, {820, 831, "WY"}, {832, 838, "ID"}, {840, 847, "UT"}, {850, 865, "AZ"},
        {870, 884, "NM"}, {885, 885, "TX"}, {889, 898, "NV"}, {900, 961, "CA"}, {967, 968, "HI"},
        {969, 969, "GU"}, {970, 979, "OR"}, {980, 994, "WA"}, {995, 999, "AK"}};

    private ZipRegions() {}

    /** The state for a valid ZIP code, or empty if the prefix is unassigned or military. */
    public static Optional<String> stateOf(String zip) {
        if (zip == null || !ZIP.matcher(zip).matches()) {
            return Optional.empty();
        }
        int prefix = Integer.parseInt(zip.substring(0, 3));
        for (Object[] range : RANGES) {
            if (prefix >= (int) range[0] && prefix <= (int) range[1]) {
                return Optional.of((String) range[2]);
            }
        }
        return Optional.empty();
    }
}
