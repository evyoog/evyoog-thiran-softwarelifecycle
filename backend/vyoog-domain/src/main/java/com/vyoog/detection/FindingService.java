package com.vyoog.detection;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The human-facing half of a finding's lifecycle: accept or dismiss. */
@Service
public class FindingService {

    private final FindingRepository findings;

    public FindingService(FindingRepository findings) {
        this.findings = findings;
    }

    /** VYB-0153 AC1: dismissal without a reason is refused. */
    @Transactional
    public Finding dismiss(UUID id, String reason, UUID actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to dismiss a finding");
        }
        Finding f = findings.findById(id).orElseThrow(NoSuchElementException::new);
        f.dismiss(reason, actor);
        return findings.save(f);
    }

    @Transactional
    public Finding accept(UUID id, UUID actor) {
        Finding f = findings.findById(id).orElseThrow(NoSuchElementException::new);
        f.accept(actor);
        return findings.save(f);
    }

    /** VYB-0224 AC2. Only meaningful from DISMISSED — reopening an already-OPEN finding is a no-op either way. */
    @Transactional
    public Finding reopen(UUID id, UUID actor) {
        Finding f = findings.findById(id).orElseThrow(NoSuchElementException::new);
        f.reopenManually(actor);
        return findings.save(f);
    }
}
