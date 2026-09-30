<!-- Split verbatim from the former backend/docs/vyoog-build-specification.md (lines 919–936). Section numbers are unchanged; docs/02-requirements/SPECIFICATION-INDEX.md maps every section to its file. -->

### 7.0 Cross-cutting: the application shell

| Feature | Behaviour |
|---|---|
| Sidebar | Ten modules, one divider before Administration. Collapsible. Per-item badge counts. |
| **Role-based visibility** | A module is hidden entirely if the user holds no grant permitting it. Driven by `access_grant`, not a hardcoded list. |
| Command palette | `⌘K` / `Ctrl+K`. "Go to" entries generated from the nav; "Actions" entries for create/import/generate/rescan/baseline/glossary/workflow. Fuzzy match. |
| Theme | Dark and light. Full token set for both. Persisted per user. |
| Global search | Across requirements, capabilities, glossary, findings. Trigram-backed. |
| Notification inbox | Unread count in the header; items link to the object. |
| Toasts with undo | Every mutation that can be reversed shows a toast with an **Undo** action for 7 seconds. Undo is a real inverse operation, not a UI trick. |
| Confirm dialogs | For destructive or consequential actions. Title, body, an explicit list of what is affected, a named danger button. |
| Breadcrumb | Portfolio → Product → App → Capability where applicable. |

**Design tokens.** Port the CSS custom properties from the prototype exactly — both
token sets. Amber is reserved for AI (Principle 3); enforce this with a lint rule that
fails the build if `--ai` is used on a status element.
