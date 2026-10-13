# Trend dashboards and exports

Added by VYB-0956 (Phase 6, Sprint 12, F24). Migration `V057__release_readiness_snapshot.sql` (`release_readiness_snapshot`). Code: `TrendService`, `TrendReportService` and `TrendReportWriter` (`com.vyoog.report`), `ReadinessSnapshotService` (`com.vyoog.release`), `TrendController`, the nightly `readinessSnapshot` job in `ScheduledJobs`, and under `frontend/src/features/quality/trends/`: `TrendsTab`, `TrendCharts`, `trendModel.ts`. Decision: D43. It is the **Trends** tab on the Quality screen.

Three trends, and the same figures as an Excel workbook or a PDF. Counts and rates only: nothing is per person (rule 7). **Anyone signed in** may read them and download the files (they are no more sensitive than the Quality screen they read from).

## What is measured

Weeks run **Monday to Sunday in UTC**. The period is the last **30, 90 or 180 days** (5, 13 or 26 weeks, the last being the week that holds today).

- **Defects, per week:** raised, closed, and the **backlog at the end of the week**, which is every defect not closed then (open or fixed). The state at a moment is read from the recorded transitions, so a reopened defect is back in the backlog, and a defect closed twice counts as closed in each week it was closed.
- **Test pass rate, per week:** of the pass and fail results recorded that week (CI and manual runs together), the share that passed. **A week with no results has no rate** ("no results"), which is not the same as 0%: two failures and nothing passed is a real 0%.
- **Release readiness, per recorded day of one release:** committed scope, approved, verified, blocked and open critical gaps, with the **live** figures beside them. The chart shows the approved and verified share of the scope.

The **product and application** filters narrow defects and results to requirements placed anywhere in that product or application (through `requirement_scope`, so product- and application-level requirements count). A defect with no requirement is counted only while no filter is set. A release has its own scope, so the filters do not apply to readiness.

## Why readiness is recorded

Readiness is derived on every read (VYB-0476) and nothing kept it, so a trend needs a record. A **nightly job (03:15 UTC)** writes one row per release per day, for every release still being worked and for any released in the last day (so its last figures are kept). Running it twice in a day replaces that day's row; the job is behind the scheduler lock like the others. The figures are the ones the release screen and gates use: committed, approved, verified, blocked, open critical gaps. **The trend starts the day the job first ran.** An earlier day, or a day the job did not run, is a **gap** (the line breaks), never a zero. A share of an empty scope is not 0% either.

## The files

`GET /api/v1/trends/export?format=xlsx|pdf` with the same filters. The workbook has Summary, Defects, Pass rate and Readiness sheets, numbers as numbers and an absence written in words ("no results", "no data"). The PDF is A4 with a bar chart and a table for each trend; every bar is also in its table, so nothing depends on colour. The PDF uses the built-in Latin-1 font, so a character outside it (a release named in another script) is written as `?`. Each export is **audited** (`report.trends_exported`, with the format and filters) because the file leaves the system; each nightly run is audited once (`report.readiness_snapshot_taken`, with the day and the number of releases). The PDF is drawn with **OpenPDF** (LGPL/MPL), added in the parent `dependencyManagement`; Excel uses POI, already present.

## The screen

Filters (product, application, period, release for readiness), a **View as tables** switch (the same figures as tables, for reading by screen reader or copying), Excel and PDF buttons, and four sections: the defect backlog, defects raised and closed, the test pass rate and release readiness. Charts are drawn in SVG with no library: thin marks, a quiet grid, a legend wherever there are two series, a hover tooltip carrying every value, and a gap where nothing was recorded. The two series colours (`--series-1` blue, `--series-2` magenta) were validated as a pair against both surfaces with the data-visualisation palette checker; neither is amber (AI) nor a status colour. The default release is the first one not yet released.

## Not in this row

No trend of anything per person or team, no baseline or forecast line, no e-mailed or scheduled report, no back-filled readiness before the first nightly run, no custom period, and no readiness for several releases at once. Weeks in UTC are not the viewer's local weeks.
