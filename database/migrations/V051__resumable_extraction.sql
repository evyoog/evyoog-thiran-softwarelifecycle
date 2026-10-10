-- VYB-0940 (F31): an AI extraction is many model calls (every chunk read, the summary, the check, the briefs). It used to run
-- inside one database transaction, so a failure part way threw away every call already paid for and held a connection the whole
-- time. Now each finished step is saved here as it completes, and extracting again continues from the last saved step.
--
-- A step's result is what the model returned for it (JSON). source_digest is a hash of the document text and name the step was
-- made from; a step made from different text is never reused. Rows go when the extraction succeeds or the batch is deleted.
CREATE TABLE import_extraction_step (
  batch_id      UUID NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
  step_key      TEXT NOT NULL CHECK (btrim(step_key) <> ''),
  source_digest TEXT NOT NULL CHECK (btrim(source_digest) <> ''),
  result        JSONB NOT NULL,
  saved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (batch_id, step_key)
);

-- Where extraction stands. state gains EXTRACTING and EXTRACTION_FAILED (state is free text). A claim older than the batch
-- deadline with no result is taken over (the instance that held it died), see JdbcExtractionProgress.
ALTER TABLE import_batch ADD COLUMN extraction_started_at TIMESTAMPTZ;
ALTER TABLE import_batch ADD COLUMN extraction_error TEXT;
