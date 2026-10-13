# Localisation, code splitting and accessibility

Added by VYB-0958 (Phase 6, Sprint 12, F38, F42). No backend change, no migration. Code: `frontend/src/shared/i18n/` (the locale layer), `frontend/src/shared/a11y/`, `frontend/src/app/RouteBoundary.tsx`, `frontend/scripts/` (`chunk-budget.mjs`, `a11y-audit.mjs`). Decision: D45.

## The locale layer

A small layer written for this app, with no library. **English is the source of truth**: `catalog.en.ts` holds every message under a key (`nav.home`, `mywork.stat.overdue`), and `t('some.key')` accepts only keys that exist, so a typo is a compile error.

- **Placeholders and plurals.** `{name}` is filled from the parameters; a message that depends on a count is an object of plural forms chosen by `{count}` with the locale's own rules (`Intl.PluralRules`), falling back to `other`. A number given as a parameter is formatted for the locale (`1,234`). Never build a sentence from pieces: other languages order them differently, so each variant (`release.notReady.FROZEN`) is a whole message.
- **A missing message never breaks a screen.** A language's catalogue may be partial; what it lacks is shown in English. A key built from data that has no message (a state the server sent that the app does not know) shows its key.
- **Dates and numbers** are formatted by the locale (`useLocale().fmt`: date, date and time, month and year, short weekdays starting Monday, numbers), never with the browser's default.
- **The language** starts from the person's earlier choice, else the browser's language, else English, and is changed with the switcher in the top bar (and on the sign-in screen). It is **kept in this browser** (a per-device convenience, so every read and write of browser storage is guarded); nothing is stored on the account. The page's `lang` and `dir` follow it, so assistive technology reads in the right voice.
- **The test language** (`en-XA`) rewrites every English message with accented letters, brackets and about a third more length, keeping placeholders. Anything that is still plain English on screen has bypassed the catalogue, and a layout too tight for longer words shows it. It is offered in the switcher only while developing (or with `VITE_SHOW_TEST_LOCALE=true`, or while it is the language in use).
- **No real language is shipped yet** (decided: English plus the test language). Adding one is a catalogue file of the same keys, an entry in `LOCALES`, and a native speaker's review.

### What is translated

The shell (menu, top bar, command palette, notifications), the sign-in screen, **Home** and **My Work** (all four tabs). Everything else is still English in its own source and moves into the catalogue screen by screen. `translatedScreens.test.ts` lists the screens that have moved and **fails on any text written straight into their markup or labels**, so a moved screen cannot slip back; add a screen to its list when its text is moved.

Not translated, by nature: **text that comes from the server** (a task's reason, a release gate's detail, an integration's description, a person's name), because the server writes it in English. Translating it is a backend piece of work, not part of this row.

## Code splitting

Every screen is its own chunk, loaded when its route is first opened (`app/App.tsx`), inside the shell, which stays in the main bundle so the menu paints at once. While a chunk arrives the screen area says "Loading this screen…" (announced); if it cannot be fetched (typically the application was updated after the page opened, so the old file is gone) it says so with a Reload button (`RouteBoundary`), and resets when the person moves to another screen. React, the query library and the icons are their own long-lived chunks. **The first page load fell from one 861 kB script to about 320 kB** (plus the one screen opened).

`scripts/chunk-budget.mjs` runs at the end of `npm run build` and **fails it** if one script exceeds 250 kB or the first page load exceeds 450 kB (raw bytes), so the split cannot quietly come undone.

## Accessibility

Measured with **axe-core** (WCAG 2.0 and 2.1 A and AA, plus best practice) on all 14 signed-in screens and the sign-in screen, in both themes, against a stubbed API: `node frontend/scripts/a11y-audit.mjs <url>` (see the script's header). **At the start: 68 violations across the screens, 14 of them serious or critical. Now: none.** What was fixed:

- **Form fields without names** (the serious ones): about 100 labels were never joined to their controls. `shared/a11y/associateLabels.ts` joins a label to the control that follows it as screens render, where the pairing is unambiguous; the lasting repair is `htmlFor` in the source, so a **ratchet test** (`labelAssociation.test.ts`) counts the unjoined labels in the source (100 now) and fails if the number grows. Fix some, lower the number. New forms write `htmlFor`.
- **Landmarks:** a header, a labelled sidebar, one main region on every screen including sign-in.
- **Headings:** one level-one heading per screen, and the section headings (38 of them) and empty-state headings are level two, so the order no longer skips. The page title is also the browser tab's title, and **moving to another screen moves focus to its heading** so a screen reader announces where you are (the first load does not take focus).
- **A skip link** to the main content, a **visible focus ring** on everything that can take focus (controls that had removed their outline relied on a border colour change), and reduced-motion respected.
- **Names for controls:** the theme, sign-out, notifications, show-password and calendar buttons, the sign-in fields (`htmlFor`), the Requirements filter row (it had empty header cells and unnamed selects), and the import and Delivery selects.
- **The sign-in screen:** the errors are announced, and the password toggle is reachable by keyboard and says what it does (it was removed from the tab order).

### What this does not show

Automated rules find about a third of real problems. **Nobody has used these screens with a keyboard only or a screen reader**, and the audit ran on empty states (the stub returns no rows), so the screens with data (the grid, the trace graph, the design canvas) have had less checking than the tabs and forms. Colour contrast was checked by axe on the pages it saw and, for the design tokens, by the existing contrast test. Clickable rows elsewhere in the app that are not links or buttons are not found by axe; one on Home was fixed, the rest are not hunted down. Treat the audit as a regression guard, not a certificate.
