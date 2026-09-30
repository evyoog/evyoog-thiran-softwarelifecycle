-- V012: two real gaps in the Import Queue — no Word (.docx) upload kind existed at
-- all, and every imported candidate silently became `type = FUNCTIONAL` regardless
-- of content (Requirement.type's own field default, never overridden anywhere in
-- ImportService.commit). Adds WORD as a fifth upload kind, and a
-- propose/confirm pair for `type` mirroring the existing capability proposal
-- exactly — an AI classification is a proposal, never applied automatically.

ALTER TABLE import_batch DROP CONSTRAINT import_batch_upload_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_upload_kind_check
  CHECK (upload_kind IN ('FREEFORM', 'STANDARD_SPEC', 'REQIF', 'EXCEL', 'WORD'));

ALTER TABLE import_candidate ADD COLUMN proposed_type TEXT
  CHECK (proposed_type IN ('FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE',
                            'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE'));
ALTER TABLE import_candidate ADD COLUMN type_confirmed BOOLEAN NOT NULL DEFAULT false;
