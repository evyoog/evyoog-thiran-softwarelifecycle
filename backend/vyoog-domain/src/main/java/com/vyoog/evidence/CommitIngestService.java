package com.vyoog.evidence;

import com.vyoog.detection.DetectionSweepService;
import com.vyoog.platform.audit.AuditService;
import com.vyoog.requirements.RequirementRepository;
import com.vyoog.trace.TraceLink;
import com.vyoog.trace.TraceLinkRepository;
import com.vyoog.trace.TraceLinkType;
import com.vyoog.trace.TraceObjectType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VYB-0316: parses {@code Requirement: KEY} trailers out of an ingested commit
 * message and turns each into a CODE&rarr;REQUIREMENT IMPLEMENTS link — the evidence
 * {@code DeveloperTask}-derivation (VYB-0344/0345) reads. VYB-0315's detector reads
 * {@link IngestedCommit#isHasTrailer} on whatever this leaves behind.
 */
@Service
public class CommitIngestService {

    private static final Logger log = LoggerFactory.getLogger(CommitIngestService.class);

    // Deliberately generic ([A-Z]+-\d+), not literally "VYB-nnnn" — VYB-nnnn is the
    // *specification's own* numbering for its 323 requirements, not the key format
    // this running system allocates (RequirementKeyAllocator, driven by app_config's
    // configurable prefix, currently "VY-nnnn"). A commit trailer names a requirement
    // in *this* system, so it has to match what this system actually generates.
    private static final Pattern TRAILER =
        Pattern.compile("(?im)^Requirement:\\s*([A-Z]+-\\d+)\\s*$");

    private final IngestedCommitRepository commits;
    private final RequirementRepository requirements;
    private final TraceLinkRepository links;
    private final AuditService audit;
    private final DetectionSweepService detection;

    public CommitIngestService(IngestedCommitRepository commits, RequirementRepository requirements,
                                TraceLinkRepository links, AuditService audit, DetectionSweepService detection) {
        this.commits = commits;
        this.requirements = requirements;
        this.links = links;
        this.audit = audit;
        this.detection = detection;
    }

    public record IngestResult(boolean alreadyIngested, List<String> linkedKeys, List<String> unknownKeys) {}

    @Transactional
    public IngestResult ingest(String sha, String message, String authorEmail) {
        // VYB-0316 AC3: idempotent — re-ingesting the same SHA changes nothing.
        var existing = commits.findBySha(sha);
        if (existing.isPresent()) {
            return new IngestResult(true, List.of(), List.of());
        }

        List<String> matchedKeys = new ArrayList<>();
        Matcher m = TRAILER.matcher(message == null ? "" : message);
        while (m.find()) {
            matchedKeys.add(m.group(1));
        }

        IngestedCommit commit = commits.save(new IngestedCommit(sha, message, authorEmail, !matchedKeys.isEmpty()));

        List<String> linked = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String key : matchedKeys) {
            requirements.findByKey(key).ifPresentOrElse(
                r -> {
                    boolean exists = links.existsByFromTypeAndFromIdAndToTypeAndToIdAndLinkType(
                        TraceObjectType.CODE, commit.getId(), TraceObjectType.REQUIREMENT, r.getId(),
                        TraceLinkType.IMPLEMENTS);
                    if (!exists) {
                        // reviewedAtRevision doubles here as "the revision this commit was
                        // written against" (VYB-0345 needs to compare it to the current
                        // revision) — the same nullable column TraceLink already carries for
                        // requirement-to-requirement links, reused rather than duplicated.
                        TraceLink link = new TraceLink(TraceObjectType.CODE, commit.getId(),
                            TraceObjectType.REQUIREMENT, r.getId(), TraceLinkType.IMPLEMENTS, null);
                        link.markReviewedAt(r.getRevision());
                        links.save(link);
                    }
                    linked.add(key);
                },
                () -> unknown.add(key)); // AC2: an unknown key is reported, not created
        }

        audit.recordService("commit.ingested", "COMMIT", commit.getId(),
            Map.of("sha", sha, "linkedKeys", linked, "unknownKeys", unknown));
        try {
            commits.flush();
            detection.rescanObject(commit.getId());
        } catch (Exception e) {
            // Same rationale as RequirementService#rescan: detection is secondary —
            // a write that already committed must not appear to have failed.
            log.warn("[detection] bounded rescan failed for commit {}: {}", commit.getId(), e.getMessage());
        }
        return new IngestResult(false, linked, unknown);
    }
}
