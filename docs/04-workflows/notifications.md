# Notifications

Added by VYB-0955 (Phase 6, Sprint 12, F21). Migration `V058__notification_channels_and_preferences.sql` (`notification_preference`, `notification_chat_kind`, `notification_delivery`). Code: `com.vyoog.notify` (`NotificationKind`, `NotificationService`, `NotificationEvents`, `NotificationRecipients`, `NotificationPreferenceService`, `NotificationSettingsService`, `NotificationDeliveryQueue`, `NotifyProperties`), `NotificationDeliveryService` (`com.vyoog.api.notify`, the sender), `NotificationController` (`/notifications/preferences`), `NotificationSettingsController` (`/settings/notifications`); front end `app/NotificationBell.tsx` (the Preferences view), `app/NotificationPreferences.tsx`, `features/admin/NotificationsTab.tsx`. Decision: D47. Setting email and chat up: [`../08-architecture/deployment/notifications-setup.md`](../08-architecture/deployment/notifications-setup.md).

A notification is a person being told that something now waits on them, or happened to something of theirs. It is **never the only way to find the work**: My Work derives the same tasks whatever anyone's notification choices are, and nothing here creates, blocks or changes a task or a requirement.

## What is announced, and to whom

| Kind | Who is told | Urgent | Chat |
|---|---|---|---|
| Review requested | Reviewers (REVIEWER) at or above where the requirement sits, when it is submitted or resubmitted | yes | yes |
| Decision requested | Approvers (APPROVER) at or above where it sits, when it is marked reviewed. Its author is never asked | yes | yes |
| Requirement approved | its author (owner and creator) | no | yes |
| Requirement rejected | its author, with the reason | yes | yes |
| Requirement sent back for revision | its author, with the reason | yes | yes |
| Release moved | Approvers whose scope holds a requirement committed to the release | no | yes |
| Test failed | the author of the requirement the test failed against (from CI, or when a manual test run is completed) | no | yes |
| You were mentioned | the person mentioned in a comment | yes | no |
| Clarification asked, answered, escalated; defect routed, fixed, reopened | as before this row: the person it is addressed to | per kind | no |

- "At or above where it sits" is the same walk up the hierarchy (capability, application, product, platform) that decides who may act. A grant that is revoked or expired, and a person who is on leave or has departed, are not told.
- **The person who made the change is never told about it.** Withdrawing to draft tells nobody. A move that is refused tells nobody, and a change that rolls back takes its notifications with it (they are written in the same transaction).
- A bulk edit tells each requirement's people as a single move would, and the inbox collapses the repeats (below). Undoing a bulk edit tells nobody.
- Not announced: edits, comments (other than mentions), a passing test, anything about a requirement nobody is waiting on.

## Channels

| Channel | Who chooses | Default |
|---|---|---|
| **Inbox** (the bell, live) | each person, per kind | on for every kind |
| **Email** | each person, per kind | on for **urgent** kinds only |
| **Team chat** (Microsoft Teams) | the administrator, per kind | off for every kind |

- **Inbox.** As before: repeats of one kind to one person within the digest window (15 minutes, an app setting) are one item with a count. A kind switched off for the inbox leaves no inbox row at all.
- **Email.** An urgent kind is sent at once, except that a second one of the same kind to the same person inside the digest window waits and goes in the digest instead: the first has told them, the inbox says how many. A kind that is not urgent (and chosen) waits one digest window, and when any of a person's quiet email is due, **all of it goes as one message**. Nothing is dropped. The message carries the same words as the inbox row and, if the deployment knows its public address, a link. It goes only to the person told.
- **Team chat.** **One post per event, not per person**, for the kinds the administrator has switched on, as a card with the title, the requirement's own title and a link. **Never a reason or a comment**: a shared channel is not where a rejection's reason belongs. A bulk edit is one post; every failing test in one run is one post. Kinds that are personal to one person cannot be posted to chat.
- **Not set up means off.** Email and chat are switched on only by the deployment's environment (a mail server and sender; a Teams webhook address). With neither, nothing is queued for them and the settings and preferences screens say so; the inbox works as before.

## How it is sent

Each email and chat post is a row in `notification_delivery`, written in the same transaction as the change that caused it, and a scheduled job (every 30 seconds, behind the scheduler lock so one instance sends) sends it afterwards. Sending can therefore never fail or slow a change. A message that fails is recorded with the error and tried again after 1, 2, 4 and 8 minutes, then left failed and shown on the Notifications tab. Sent and failed rows are removed after 30 days (nightly); pending ones never. Sending is at least once: a crash between sending and recording can send a message twice.

The mail password and the chat address are secrets: environment only, never logged, never in an error stored on a row, never returned by an endpoint (the settings report only whether each is set).

## Screens

- **Preferences**: the bell, then **Preferences**. Each kind, grouped, with an Inbox box and an Email box that save as they are ticked, a "use the default" for a kind that has been changed, and a note when email is not set up.
- **Administration, Notifications**: whether email and chat are set up, how many are waiting, sent and failed in the last 24 hours and the last error, which kinds go to chat, and a test message to each channel (the email test goes to the administrator's own address).

## API

| Endpoint | Who |
|---|---|
| `GET /notifications/preferences` | any signed-in person (their own) |
| `PUT /notifications/preferences` | any signed-in person (their own); `{changes:[{kind, channel: INBOX\|EMAIL, enabled: true\|false\|null}]}`, null puts the kind back to its default; audited |
| `GET /settings/notifications` | administrator |
| `PUT /settings/notifications/chat-kinds` | administrator; audited |
| `POST /settings/notifications/test` | administrator; `{channel: EMAIL\|CHAT}` |

## What was not done

- **Never tested against a real mail server or a real Teams channel**: there is neither in the build environment. A fake of each, on the same machine, proves the protocol and the behaviour; the first real send is the administrator's test button.
- **Email only on STARTTLS or plain SMTP** (port 587 or 25); implicit TLS (465) is not supported.
- **Chat is a channel, not a direct message**: a message to a person in Teams needs a registered chat app, which was not built.
- A person's delegate or manager is not copied; someone on leave is simply not told.
- Reminders (a decision left unmade for days), quiet hours and a change of address are not built.
