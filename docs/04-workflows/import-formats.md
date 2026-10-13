# Import: Word, ReqIF, templates and the file check

Added by VYB-0957 (Phase 6, Sprint 12, F25). No migration. Code: `DocxSheetReader` and `ReqIfSheetReader` (`com.vyoog.importqueue.sheet`), the changes to `PrdTemplateParser`, `ImportFileValidator`, `ImportTemplateService`, `ImportController` (`POST /api/v1/import/validate`, `GET /api/v1/import/templates/{xlsx|docx}`), and under `frontend/src/features/importqueue/`: `FileCheck`, `fileCheck.ts`. Decision: D44. It extends the spreadsheet import (VYB-0666); the screen is the Import queue.

## What can be imported

One **Requirements file**, whatever it is made in, read **by its content** (the extension is not trusted):

| File | Read as | Placement |
|---|---|---|
| Excel (`.xlsx`, `.xls`), OpenDocument (`.ods`), CSV | the first sheet, a row per requirement, columns found by their headings | Product, App and Capability on each row |
| Word (`.docx`) | the first **table** whose heading row names the template's columns; prose is never read | Product, App and Capability on each row |
| ReqIF (`.reqif`, `.reqifz`) | each `SPEC-OBJECT` is a row; columns are its attributes by name | the application chosen at upload (a ReqIF file has no hierarchy) |

Every format goes through the same reader (`PrdTemplateParser`), so the same rules apply: **nothing is guessed**. A Word table or ReqIF is a deterministic read, with no AI, exactly as the spreadsheet is; Word and ReqIF were closed in VYB-0666 because they were read by analysis agents that inferred the type and priority, and they are reopened only on this footing. Type and Priority are required; a row missing one is a problem on that row, and a file with no Type or Priority column or attribute is refused with that said. There are no defaults.

**Word:** a cell with several paragraphs keeps them as lines (Acceptance Criteria is one per line). A cover or version table before the requirements table is passed over; tables after it are not read. **ReqIF:** string, XHTML (flattened to plain text, one line per paragraph), integer, real, date and boolean values are read as text, an enumeration as the long name of the value chosen. The object's `IDENTIFIER` is **Your Ref** (identifiers are kept), and each `SPEC-RELATION` becomes a **Depends On** on its source, which becomes a `DERIVES` trace link when both ends are imported. Attribute names are matched like column headings, with the names common tools use added (`ReqIF.Name`, `ReqIF.ChapterName` for the title; `ReqIF.Text`, `Text` for the statement). The XML is read with DTDs and external entities refused; a `.reqifz` entry is capped at 50 MB unpacked.

## The file check

Choosing a file on the Import queue **checks it at once**, before anything is stored (`POST /import/validate`, same role rule as upload): it reads the file as extraction will and lists **every problem by row** at once.

- **A whole-file problem blocks the upload:** the file is not one the import reads, is empty, has no table or no recognised headings, a required column or attribute is missing, a file inside a `.reqifz` is missing, or it holds more than **5,000** requirements. The upload endpoint checks the same thing and refuses with the reason, so nothing unreadable is ever stored.
- **A row's own problem does not block it:** a required cell empty, a Type or Priority outside the register's lists, a product, app or capability that does not exist. These are listed by row (the first 25 on screen, with a count of the rest), and if the file is uploaded as it is, those rows arrive **unticked with the reason beside them**, as they always have. The Upload button says how many (`Upload (3 need attention)`).

Checking writes nothing and is not audited; the upload is (`import.uploaded`), as before.

## The templates

`GET /api/v1/import/templates/xlsx` and `/docx`, for anyone signed in, generated on request (no binary file to keep in step with the importer). Both use the headings the import reads; a required column is marked `*`.

- **Excel:** the **Requirements** sheet first (the import reads the first sheet): a banner, the heading row and room for 500 rows, with dropdowns on Type and Priority; an **Instructions** sheet with every column, whether it is required, what to enter and an example. The example is on the Instructions sheet, not in the table, so it cannot be imported by accident.
- **Word:** a landscape page with instructions, an example line as text, and a table with ten columns (Your Ref, Product, App, Capability, Requirement Title, Requirement Statement, Type, Priority, Acceptance Criteria, Depends On). Another column of the Excel template can be added by its heading.

A blank template is found to have "nothing to import", not a pile of empty rows.

## A bug found on the way

Resolving "Depends On" links at commit checked that both requirements existed with plain SQL while the requirements just created were still unflushed, so the link failed silently and marked the commit's transaction rollback-only; it worked only when something else (writing acceptance criteria) had flushed first. A ReqIF file rarely has criteria, so its relations hit it every time. The commit now flushes before resolving links (covered by `ImportFormatsIT`).

## Not in this row

No AI reading of prose in Word or ReqIF (by decision), no defaults for a missing Type or Priority, no mapping screen to say which ReqIF attribute is the Type, no import of ReqIF's document hierarchy (chapters) or attachments, no `.doc` (old Word), no more than one table per Word file, and no per-row check of a ReqIF's capability (it is placed at application level and the reviewer confirms the capability, as for any import without one).
