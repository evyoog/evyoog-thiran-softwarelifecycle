-- V024: the PRD template import is a real upload kind, not a parser mode hidden behind
-- EXCEL. The API enum and UI already send PRD_TEMPLATE; this keeps the database
-- constraint in sync so .ods/.xlsx templates can be saved to import_batch.

ALTER TABLE import_batch DROP CONSTRAINT import_batch_upload_kind_check;
ALTER TABLE import_batch ADD CONSTRAINT import_batch_upload_kind_check
  CHECK (upload_kind IN ('FREEFORM', 'STANDARD_SPEC', 'REQIF', 'EXCEL', 'WORD', 'PRD_TEMPLATE'));
