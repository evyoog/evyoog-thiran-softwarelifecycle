package com.vyoog.requirements;

import com.vyoog.detection.AmbiguousTermLexicon;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * VYB-0202: which gap classes will fire on save, split into avoidable (the author can
 * fix it right now, by editing) and expected (true of every requirement at this point
 * in its life, not a sign something is wrong — AC1's literal example). A pure
 * function, safe on every keystroke (AC2), mirroring the real detectors' own logic
 * ('ambig', 'noac') rather than a second copy of their rules.
 */
@Service
public class GapPreviewService {

    public record GapPreview(String ruleKey, String reason) {}
    public record Preview(List<GapPreview> avoidable, List<GapPreview> expected) {}

    public Preview preview(String statement, int criteriaCount, boolean hasCapability, boolean hasUpstream) {
        List<GapPreview> avoidable = new java.util.ArrayList<>();
        List<GapPreview> expected = new java.util.ArrayList<>();

        var ambiguous = statement == null ? List.<AmbiguousTermLexicon.Match>of() : AmbiguousTermLexicon.findIn(statement);
        if (!ambiguous.isEmpty()) {
            avoidable.add(new GapPreview("ambig",
                "Contains unmeasurable wording: " + ambiguous.stream().map(AmbiguousTermLexicon.Match::term).toList()));
        }
        if (criteriaCount == 0) {
            avoidable.add(new GapPreview("noac", "No acceptance criteria yet — add at least one"));
        }
        if (!hasCapability) {
            avoidable.add(new GapPreview("unplaced", "Not placed under a capability — pick one so it can be traced"));
        }

        // VYB-0202 AC1: these are true of every requirement at this point in its life
        // — nothing built or tested yet is not a defect, it's just where a draft is.
        if (!hasUpstream) {
            expected.add(new GapPreview("orphan", "No upstream link yet — link it once you know what it satisfies"));
        }
        expected.add(new GapPreview("nodesign", "No design node implements this yet"));
        expected.add(new GapPreview("noverify", "No passing test yet"));

        return new Preview(avoidable, expected);
    }
}
