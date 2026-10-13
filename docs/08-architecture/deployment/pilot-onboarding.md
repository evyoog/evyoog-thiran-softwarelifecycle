# Pilot onboarding: one real team (VYB-0959)

How to take one real team from nothing to working in Vyoog. This is a runbook for the person running the pilot, not a
description of what the product could do. The steps marked **product** are the same steps `PilotDryRunIT` runs against an invented
team on every build, so if this page and the product drift apart, that test fails. The steps marked **human** need a person with
access to something this repository cannot reach (Keycloak, the real deployment, the team); nobody has done them yet.

**Status: the kit and a dry run exist. No real team has been onboarded.** Picking the team, getting their agreement and doing the
**human** steps below is the pilot, and it is open.

## Before you start (human)

| # | Check | Who |
|---|---|---|
| 1 | The deployment is up and `/actuator/health` is UP; the database restore rehearsal has been done on today's schema (`pilot-rehearsals.md`) | Operator |
| 2 | Required secrets are set (the app refuses to start without them, D22); `BOOTSTRAP_TOKEN` is set **only** for the next step and unset again after | Operator |
| 3 | The team and its sponsor have agreed what the pilot is for and when it is judged: a date, and two or three things that would make it a success | Product owner |
| 4 | Each person on the team has an account in the shared `eVyoog` Keycloak realm (Vyoog creates no credentials and holds no passwords) and knows the address | Keycloak administrator |
| 5 | You know which application and capabilities the team's requirements belong to, and whether they have an existing spreadsheet or Word file to bring | Pilot lead |

## The steps

### 1. First administrator (human, once per deployment)

Set `BOOTSTRAP_TOKEN`, sign in as the person who will administer, then `POST /api/v1/settings/bootstrap` with their user id and the
header `X-Bootstrap-Token`. It succeeds once and only while no administrator exists. Unset the token afterwards and restart.
(Covered by `Vyb0901OpenDoorsTest`; `PilotDryRunIT` starts after this step because a shared test database already has an administrator.)

### 2. The portfolio (product)

As the administrator, create the **product**, its **application** and the **capabilities** the team will work in
(`POST /products`, `/products/{id}/applications`, `/applications/{id}/capabilities`; or the Portfolio screens). Only an administrator can.

### 3. The people, before they sign in (product)

Add each person to the directory with `POST /users` (Administration, People): email, display name, and a **role at the capability**
(Business analyst, Reviewer, Approver, Developer, Viewer, and so on). Do it before their first sign-in so the access is waiting for
them. The email address must be the one Keycloak will send; **a first sign-in claims the prepared row by email**, so a mismatch
(a nickname, another domain) gives them a second, empty account and no access. The dry run proves the claim works and that nobody ends
up with two accounts; it cannot prove the address in Keycloak is the address you typed, so compare them.

Keep roles honest: the person who writes a requirement cannot be the one who approves it (separation of duties is enforced, and the
dry run shows the 403).

### 4. First sign-in (human, with the product)

Ask each person to open the address and sign in through Keycloak. Check **Administration, People**: every person shows as signed in
with the grants you gave them. Anyone who shows a second row, or no grants, is the email mismatch above: fix the directory row, not
the person.

### 5. The team (product)

`POST /teams` creates it; it returns nothing, so find its id in `GET /teams` (or use the Teams screen). Add each person
(`PUT /teams/{id}/members/{userId}`), and make one of them **LEAD** (`PUT .../role`). A lead can manage the team's members and make
its board templates.

### 6. The team's board (product)

The lead creates a board template (`Boards`, new template): the columns in the order the team works, scoped to the application. The
default board (every status) still exists for everyone.

### 7. Bring the existing requirements (product, with a person reading the result)

`GET /import/templates/xlsx` (or `docx`) is the template to fill in; **Import** validates a file before it is uploaded and lists every
problem at once (`POST /import/validate`). Upload, extract, and read the candidates before committing: a person decides every one
(AI proposes, the human decides). The template with a blank body is refused with "no rows", which is the expected answer for an empty
file.

Do not import a thousand rows on the first day. Start with twenty the team actually works on this week.

### 8. First real work (product)

The analyst writes or imports requirements with acceptance criteria and submits them; the reviewer reviews; the approver approves. Then
the approver creates a release and commits the approved requirements to it, and the readiness page answers for it. Everything is on the
audit record. The dry run does all of this with five invented people.

### 9. The first week (human)

Sit with them for the first session. Note, in the order they happen, every question, every wrong click and every time they reached for
the spreadsheet instead. Those notes are the pilot's result. Do not tune the product during the week unless something blocks them.

## What the dry run found missing

These are the gaps the scripted dry run (and the load rehearsal) hit. None is fixed by this row, because each is a decision or a change
of its own (see `pilot-rehearsals.md`):

1. **`POST /teams` returns no id.** The caller has to list the teams and find it by name. The Teams screen does this; a script cannot do it safely if two teams share a name.
2. **Granting a role to someone who has never signed in is only possible through `POST /users`**, and only a typed email links the row to the person later. There is no check that the email matches what Keycloak will send.
3. **`PATCH /requirements/{id}` without `capabilityId` removes the requirement's placement.** Every client must send it back on every edit; a client that forgets orphans the requirement and loses the edit rights that came from its placement.
4. **There is no email or chat message when it is someone's turn.** There is an in-app inbox and live stream (`/notifications`), and My Work; the dry run does not check which handovers reach the inbox. Email and chat channels are a separate, not-started row (VYB-0955), so tell the team to look at My Work.
5. **Keycloak is not rehearsed here.** The sandbox has no Keycloak; the realm, the users and the sign-in redirect are human steps and untested by this row.

## The pilot is done when

The date in item 3 of "Before you start" has come, the team says whether they would carry on, and you have the notes from step 9.
Record the decision in `docs/DECISIONS.md`. If the answer is "not yet", the notes say why.
