# Setting up notification email and chat (VYB-0955)

What the application needs from the deployment to send email and post to a Teams channel. Both are **off until set**: with nothing set, the inbox works as before and nothing is sent. What is announced, to whom, and how it is sent: [`../../04-workflows/notifications.md`](../../04-workflows/notifications.md). None of this has been tried against a real mail server or Teams channel; the **test buttons** on Administration, Notifications are the first real check.

## Environment (names only; values come from the secrets manager, D22)

| Variable | What | Required for |
|---|---|---|
| `MAIL_HOST` | the SMTP server | email |
| `MAIL_FROM` | the sender address on every message | email |
| `MAIL_PORT` | default 587; STARTTLS or plain (465, implicit TLS, is not supported) | |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | when the server needs a login; **a password is only ever sent over a connection upgraded with STARTTLS** (the application refuses to log in otherwise) | |
| `MAIL_STARTTLS` | default `true`; set `false` only for a server on this machine without it | |
| `NOTIFY_TEAMS_WEBHOOK_URL` | a Teams incoming webhook (Workflows "post to a channel when a webhook request is received") address, **https only**; it carries its own signature, so treat it as a secret | chat |
| `PUBLIC_BASE_URL` | the address people open the app at, with no trailing slash; without it messages carry no link | links |

A half-set channel stops the application starting with a message saying which value is missing (`MAIL_HOST` without `MAIL_FROM`, a username without a password, a chat address that is not https). Leave both empty to switch the channel off.

## Setting it up

1. Set the variables for the environment and restart.
2. **Administration, Notifications** should say email and chat are set up. If a channel says "not set up", the variable is missing or empty.
3. **Send a test email to me** (arrives at the administrator's own address) and **Post a test message**. A failure shows the reason; the address and the password are never shown.
4. Tick the kinds to post to chat (none are on by default) and **Save**. A post carries a title and a link, never a reason or a comment, so a team channel is a reasonable place for them.
5. Tell the team they can change what they get from the bell, **Preferences**. Urgent kinds (something they must act on) are emailed at once by default; the rest are inbox only until chosen.

## Watching it

The Notifications tab shows, per channel, how many are waiting, how many were sent and how many failed in the last 24 hours, and the last error. A failed message is tried again 1, 2, 4 and 8 minutes later and then left failed. A growing "waiting" count with nothing sent means the server or the webhook is not answering.

## Pilot note

On a shared mail relay, ask the owner to allow the sender address first; the first real message is easier to diagnose from the test button than from a missed review request.
