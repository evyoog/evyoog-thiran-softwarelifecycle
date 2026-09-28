-- VYB-0832: a capability had no free-text field at all — name and an optional short
-- code only. Same shape as application.description (nullable, free text).
ALTER TABLE capability ADD COLUMN description TEXT;
