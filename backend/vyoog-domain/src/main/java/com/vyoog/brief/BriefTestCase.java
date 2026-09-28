package com.vyoog.brief;

/**
 * VYB-0831: one of a requirement's own test cases, as {@link BriefContentGenerator}
 * renders it — not {@code com.vyoog.evidence.TestCase} itself, same reasoning as {@link
 * BriefRequirementView}: a pure, generator-facing view, easy to test without a database.
 */
public record BriefTestCase(String key, String title, String description, String category) {}
