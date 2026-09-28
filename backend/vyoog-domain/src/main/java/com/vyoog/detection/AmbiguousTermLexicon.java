package com.vyoog.detection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terms that cannot become a pass/fail condition (VYB-0160), each with a suggested
 * replacement. Shared between the "ambig" detector and the live lint endpoint
 * (VYB-0134) — one lexicon, not two copies that drift apart.
 *
 * <p><b>Provenance gap, flagged rather than hidden:</b> VYB-0160 AC1 asks for "at
 * least the 45 terms from the validated prototype" — that prototype's exact list
 * isn't available in this session. The list below ({@link #termCount()} terms) is a
 * standard weak-requirements-language lexicon (the kind IEEE 830 / INCOSE guidance
 * calls out), built to the same intent, but it is not a verified match to the
 * original. Worth reconciling against the real prototype list if it turns up later.
 */
public final class AmbiguousTermLexicon {

    /** Term (lower-case) -> suggested replacement guidance. Order is deterministic (VYB-0150 AC1). */
    private static final Map<String, String> TERMS = new LinkedHashMap<>();
    static {
        // Vague quality adjectives — no way to fail a test against these as written.
        put("user-friendly", "state the specific interaction outcome (e.g. \"completes in 3 clicks\")");
        put("intuitive", "state the specific interaction outcome");
        put("easy to use", "state the specific interaction outcome");
        put("seamless", "state what happens at the boundary this crosses");
        put("robust", "state the specific failure modes it must survive");
        put("flexible", "state the specific variations it must support");
        put("scalable", "state the specific load or volume it must handle");
        put("efficient", "state a specific time or resource budget");
        put("reliable", "state a specific uptime or failure-rate figure");
        put("secure", "name the specific threat or control this satisfies");
        put("modern", "name the specific standard or technology, not an era");
        put("state-of-the-art", "name the specific standard or technology");
        put("simple", "state the specific constraint that makes it simple");
        put("clean", "state the specific constraint (e.g. no duplicate rows)");
        put("clear", "state what makes it unambiguous, specifically");
        put("obvious", "state what makes it discoverable, specifically");
        put("elegant", "state the specific measurable property intended");
        put("appropriate", "state the specific criterion for appropriateness");
        put("adequate", "state the specific threshold that counts as adequate");
        put("reasonable", "state the specific threshold");
        put("sufficient", "state the specific threshold");
        put("acceptable", "state the specific threshold");
        put("satisfactory", "state the specific threshold");
        put("optimal", "state the specific objective being optimised and its target");
        put("significant", "state the specific number or percentage");
        put("minimal", "state the specific number or percentage");
        put("substantial", "state the specific number or percentage");
        // Vague quantifiers — "how many" has no single answer.
        put("several", "state the exact number or a specific range");
        put("various", "list the specific items");
        put("some", "state the exact number, or name what determines which");
        put("many", "state the exact number or threshold");
        put("most", "state the exact percentage or threshold");
        put("few", "state the exact number or threshold");
        put("a lot of", "state the exact number or threshold");
        // Vague timing/frequency — untestable without a number.
        put("quickly", "state a specific time budget");
        put("slowly", "state a specific time budget");
        put("promptly", "state a specific time budget");
        put("in a timely manner", "state a specific time budget");
        put("periodically", "state the specific interval");
        put("regularly", "state the specific interval");
        put("occasionally", "state the specific frequency or trigger condition");
        put("frequently", "state the specific frequency");
        put("usually", "state the specific condition under which it applies, and its inverse");
        put("generally", "state the specific condition under which it applies, and its inverse");
        put("typically", "state the specific condition under which it applies, and its inverse");
        put("normally", "state the specific condition under which it applies, and its inverse");
        // Vague manner adverbs — describe an outcome, not a manner.
        put("properly", "state the specific correct behaviour");
        put("correctly", "state the specific correct behaviour");
        put("effectively", "state the specific measurable outcome");
        put("as needed", "state the specific triggering condition");
        put("as appropriate", "state the specific triggering condition");
        put("if necessary", "state the specific triggering condition");
        put("etc", "list every case explicitly, or state the general rule that covers them");
        put("and/or", "state which — this is almost always a hidden decision, not a detail");
        put("tbd", "this is not a requirement yet — resolve before submitting for review");
        put("support", "state the specific capability, not just that it exists");
        put("handle", "state the specific behaviour for each case being \"handled\"");
        put("manage", "state the specific behaviour for each case being \"managed\"");
        put("process", "state the specific transformation or action, not just that it happens");
    }
    private static void put(String term, String suggestion) { TERMS.put(term, suggestion); }

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    static {
        // VYB-0160 AC2: case-insensitive, word-bounded — \b around a phrase with
        // internal spaces still bounds correctly at the phrase's own start/end.
        TERMS.keySet().forEach(term ->
            PATTERNS.put(term, Pattern.compile("\\b" + Pattern.quote(term) + "\\b", Pattern.CASE_INSENSITIVE)));
    }

    private AmbiguousTermLexicon() {}

    public static int termCount() {
        return TERMS.size();
    }

    /** One match per distinct term found, in lexicon order — not one per occurrence. */
    public static List<Match> findIn(String statement) {
        List<Match> matches = new java.util.ArrayList<>();
        for (var entry : PATTERNS.entrySet()) {
            Matcher m = entry.getValue().matcher(statement);
            if (m.find()) {
                matches.add(new Match(entry.getKey(), entry.getValue().pattern(), TERMS.get(entry.getKey())));
            }
        }
        return matches;
    }

    public record Match(String term, String pattern, String suggestion) {}
}
