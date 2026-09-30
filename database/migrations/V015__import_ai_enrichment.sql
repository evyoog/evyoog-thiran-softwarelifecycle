-- =====================================================================
-- VYB-0630 AI enrichment (2026-08-12): AI-proposed acceptance criteria and
-- trace links for an import candidate, alongside the existing AI-proposed
-- type (V006/V012). Proposals themselves live in the existing
-- import_candidate.flags jsonb (proposedAcceptanceCriteria/proposedTraceLinks)
-- — same column the capability/duplicate/type proposals already use, per
-- ImportCandidate's own javadoc: "whatever the lint/duplicate/capability
-- proposal step found... its shape varies candidate to candidate". These two
-- new columns hold only the human-confirmed final list, never written until
-- a person confirms — mirroring statement/original_text (edited copy vs.
-- fixed original) rather than adding yet another *_confirmed boolean pair.
-- =====================================================================

ALTER TABLE import_candidate
  ADD COLUMN accepted_criteria    JSONB,
  ADD COLUMN accepted_trace_links JSONB;
