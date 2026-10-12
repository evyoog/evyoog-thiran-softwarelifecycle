package com.vyoog.risk;

/**
 * VYB-0949 (F41, D40): how a risk is scored. Likelihood and impact are each 1 to 5; the rating is their product (1 to 25); the band
 * is read from the rating. Qualitative only: nothing here is a cost, an effort or a per-person figure (rule 7). Derived on every
 * read, never stored, so changing a band here changes every risk at once.
 */
public final class RiskScale {

    private RiskScale() {}

    public static final int MIN = 1;
    public static final int MAX = 5;

    /** Low 1-4, Medium 5-9, High 10-14, Critical 15-25. */
    public static String band(int rating) {
        if (rating >= 15) return "CRITICAL";
        if (rating >= 10) return "HIGH";
        if (rating >= 5) return "MEDIUM";
        return "LOW";
    }

    public static int rating(int likelihood, int impact) {
        return likelihood * impact;
    }

    public static boolean valid(int level) {
        return level >= MIN && level <= MAX;
    }
}
