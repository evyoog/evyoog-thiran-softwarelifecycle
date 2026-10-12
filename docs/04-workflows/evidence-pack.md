# Evidence pack for a baseline

Added by VYB-0948 (Phase 6, Sprint 10, F41). No migration: it reads what is already recorded. Code: `EvidencePackService` (assembly) and `EvidencePackWriter` (the bundle) in `com.vyoog.baseline`, `DocxFile` in `com.vyoog.platform.export` (shared with the release notes, which it was taken from), `BaselineController#evidencePack`, and an **Evidence pack** button per baseline on the Releases screen's Baselines tab. Decision: D39.

An evidence pack is what an auditor or a customer asks for: for each requirement a baseline froze, the wording at the revision that was frozen, how it stood at the freeze, who approved it, what was designed for it, how it was tested and what the results were. It is one download.

## Who and how

`GET /api/v1/baselines/{id}/evidence-pack`, a zip. Allowed to **Approver, Compliance Lead, Architect or Administrator** (the new access rule `EVIDENCE`; Reviewer is not in it, because a pack carries approvals and results out of the system). Every export is audited (`baseline.evidence_pack_exported`: who, which baseline, how many requirements, how many bytes). It is built in memory, in one read-only `REPEATABLE READ` transaction so its sections agree; a baseline of more than 5000 requirements is refused with its reason.

## What is in the zip

| File | What |
|---|---|
| `README.txt` | What each file is, and how to read it |
| `report.docx` | A report to read: the figures, then each requirement as frozen |
| `baseline.csv` | The baseline's own record: name, release, frozen when and by whom, gaps recorded at the freeze |
| `requirements.csv` | Each requirement at the **frozen revision** (wording, type, priority), its status at the freeze, and where it stands now (current revision and status, changed or deleted since) |
| `design.csv` | The design nodes drawn for each requirement |
| `tests.csv` | The tests linked to each requirement |
| `results.csv` | Every result recorded against the frozen revision: when, which build, and whether at or before the freeze |
| `approvals.csv` | Every move between statuses (the reviews and approvals): from, to, by whom, when, why, and whether at or before the freeze |
| `SHA256SUMS` | A hash of every file above, so `sha256sum -c SHA256SUMS` checks them |
| `manifest.json` | The index: format, the baseline, counts, and every other file with its size and SHA-256 |

CSV files are UTF-8 with a byte-order mark (Excel opens them directly), RFC 4180 quoting, dates in UTC. A cell that begins with `=`, `+`, `-` or `@` has an apostrophe put in front, so a spreadsheet never runs requirement text as a formula. Nothing in the pack is a cost, an estimate or a figure about a person (rule 7).

## What is, and is not, "as frozen"

- **Wording** is the frozen revision's own (`requirement_revision`), never the current text.
- **Status at the freeze** is the last recorded move at or before the freeze, from the audit log; a requirement with none was still a draft. Moves older than the log's retention may be missing.
- **Approvals and results** include everything recorded against the requirement or its frozen revision, each flagged `at_or_before_freeze`, so a result that arrived after the freeze is visible and not mistaken for evidence at the freeze. A result is kept only if it is for the frozen revision; results for any other revision are not in the pack.
- **Design has no history**, so design nodes are as at the export, and the README, the report and the CSV say so.
- A requirement changed after the freeze is shown at the frozen revision, with a line saying it has moved on. One deleted after the freeze is still in the pack, marked deleted.

## Integrity

Every file is hashed. A change to any file after the export fails `sha256sum -c SHA256SUMS` and no longer matches its hash in `manifest.json`. `manifest.json` is the one file nothing hashes; it is the index. The pack is not signed, so the hashes prove a file is unchanged from the pack as given to you, not who made it; the audit log says who took it out.
