package com.vyoog.brief;

import java.util.List;

/** What {@link BriefContentGenerator} needs about one in-scope requirement. Deliberately not the entity itself — the generator is a pure function over plain data, easy to test without a database. */
public record BriefRequirementView(
    String key, String title, String statement, String rationale, String type, int revision,
    boolean hasTest, List<String> criteria, Short qualityScore,
    /** D10: when this is due, as Planning resolved it. Null when nothing has dated it. */
    String dueOn,
    /**
     * D12: the level this requirement sits at. An application- or product-level rule is
     * in the brief because it constrains the work, not because somebody wrote it for this
     * capability — and it says so rather than reading as though they had.
     */
    com.vyoog.requirements.PlacementLevel placementLevel,
    /**
     * VYB-0817: an explicitly-requested, developer-facing expansion of the statement
     * above — never a replacement for it, never present unless a human asked for it on
     * this generation. Null when not requested, not attempted, or the provider was
     * unavailable; {@link BriefContentGenerator} renders nothing when this is null
     * rather than claiming an elaboration exists.
     */
    String aiElaboration,
    /**
     * VYB-0831: this requirement's own test cases — the reason it's in the brief at all
     * now, since only requirements with at least one are ever in scope. Listed, not just
     * counted, so the developer this brief is written for can see what already checks
     * this requirement before writing the code that satisfies it.
     */
    List<BriefTestCase> testCases) {

    public BriefRequirementView(String key, String title, String statement, String rationale, String type,
                                int revision, boolean hasTest, List<String> criteria, Short qualityScore,
                                String dueOn, com.vyoog.requirements.PlacementLevel placementLevel,
                                String aiElaboration) {
        this(key, title, statement, rationale, type, revision, hasTest, criteria, qualityScore, dueOn,
            placementLevel, aiElaboration, List.of());
    }

    public BriefRequirementView(String key, String title, String statement, String rationale, String type,
                                int revision, boolean hasTest, List<String> criteria, Short qualityScore,
                                String dueOn) {
        this(key, title, statement, rationale, type, revision, hasTest, criteria, qualityScore, dueOn,
            com.vyoog.requirements.PlacementLevel.CAPABILITY, null);
    }

    public BriefRequirementView(String key, String title, String statement, String rationale, String type,
                                int revision, boolean hasTest, List<String> criteria, Short qualityScore) {
        this(key, title, statement, rationale, type, revision, hasTest, criteria, qualityScore, null);
    }

    /** A copy with the AI elaboration filled in — the rest of the view is unchanged. */
    public BriefRequirementView withAiElaboration(String elaboration) {
        return new BriefRequirementView(key, title, statement, rationale, type, revision, hasTest, criteria,
            qualityScore, dueOn, placementLevel, elaboration, testCases);
    }

    /** A copy with this requirement's test cases filled in — the rest of the view is unchanged. */
    public BriefRequirementView withTestCases(List<BriefTestCase> testCases) {
        return new BriefRequirementView(key, title, statement, rationale, type, revision, hasTest, criteria,
            qualityScore, dueOn, placementLevel, aiElaboration, testCases);
    }

    /** True when this rule came from above the capability being briefed. */
    public boolean isInherited() {
        return placementLevel != com.vyoog.requirements.PlacementLevel.CAPABILITY;
    }

    /** "the whole application" / "the whole product", for stating where an inherited rule comes from. */
    public String scopeLabel() {
        return switch (placementLevel) {
            case PRODUCT -> "the whole product";
            case APPLICATION -> "the whole application";
            case CAPABILITY, UNPLACED -> null;
        };
    }

    /** Pre-quality-appendix callers; a brief with no score simply omits that row. */
    public BriefRequirementView(String key, String title, String statement, String rationale, String type,
                                int revision, boolean hasTest, List<String> criteria) {
        this(key, title, statement, rationale, type, revision, hasTest, criteria, null);
    }

    /** VYB-0453/0454: the four buckets the brief actually groups and counts by — not the eight raw types. */
    public String category() {
        return switch (type) {
            case "FUNCTIONAL" -> "Functional";
            case "NON_FUNCTIONAL" -> "Non-functional";
            case "BUSINESS_RULE" -> "Business rule";
            default -> "Other";
        };
    }
}
