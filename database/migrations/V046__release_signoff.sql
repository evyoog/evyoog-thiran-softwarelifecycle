-- VYB-0929: release sign-off. Freezing and releasing are signature events (spec 4.5): they need step-up
-- authentication at the moment of the action, and the move records the authentication level achieved
-- (acr) and when the person last authenticated (auth_time), next to who made it (changed_by).
--
-- The rows V045 wrote before this existed have no signature, so the check below is NOT VALID: it holds for every
-- new row and leaves the old ones alone.
ALTER TABLE release_transition ADD COLUMN signature_acr TEXT;
ALTER TABLE release_transition ADD COLUMN auth_time TIMESTAMPTZ;
ALTER TABLE release_transition ADD CONSTRAINT release_transition_signed_chk
  CHECK (to_state NOT IN ('FROZEN', 'RELEASED') OR btrim(coalesce(signature_acr, '')) <> '') NOT VALID;
