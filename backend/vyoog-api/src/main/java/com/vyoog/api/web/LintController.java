package com.vyoog.api.web;

import com.vyoog.detection.AmbiguousTermLexicon;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * VYB-0134: lints a statement without persisting anything — same lexicon the "ambig"
 * detector uses (VYB-0160), just called synchronously against text that may not even
 * belong to a saved requirement yet.
 */
@RestController
@RequestMapping("/api/v1/lint")
public class LintController {

    public record LintRequest(@NotBlank String statement) {}
    public record LintFinding(String term, String suggestion) {}
    public record LintResponse(List<LintFinding> findings) {}

    @PostMapping
    public LintResponse lint(@RequestBody LintRequest body) {
        List<LintFinding> matches = AmbiguousTermLexicon.findIn(body.statement()).stream()
            .map(m -> new LintFinding(m.term(), m.suggestion()))
            .toList();
        return new LintResponse(matches);
    }
}
