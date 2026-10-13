# Detection: the nightly sweep and how writes check what they changed

Added by VYB-0960 (Phase 6, Sprint 12). Migration `V059__incremental_detection_sweep.sql` (`finding.related_object_id`, `detection_sweep_state`, an index on `requirement_embedding (embedded_at, requirement_id)`). Code: `IncrementalSweep`, `DetectionSweepService`, `FindingReconciler.reconcileOne`, `Detector.sweepsChangedOnly` and `ensureAvailable` (`com.vyoog.detection`). Decision: D48. Background: [`gap-detection.md`](gap-detection.md) §6.4 and the load rehearsal in [`../08-architecture/deployment/pilot-rehearsals.md`](../08-architecture/deployment/pilot-rehearsals.md).

## The nightly sweep

At 02:00 (under the scheduler lock, one instance) every enabled rule is reconciled. Most rules are one query over the database and run to the end as they always did. **The two rules that compare requirements with one another** (a possible duplicate in another application, and, when AI is on, a contradiction) are different: comparing every embedding with every other grows with the square of the number of requirements (29 s at 2,000, not finished after 20 minutes at 50,000, about five hours extrapolated). For those rules the sweep:

1. checks only the requirements whose **embedding is new or changed since the rule's last pass**, oldest first, each with the same exact one-requirement check a write makes (about 0.2 s at 50,000), and reconciles each in its own small transaction;
2. keeps where it got to, as the position `(embedded_at, requirement id)` of the last requirement checked, in `detection_sweep_state`, so a pass that runs out of time carries on the next night instead of starting again, and a night with few changes is short;
3. has one **time budget** (`vyoog.detection.sweep-budget-minutes`, default 60) shared by these rules. When it runs out the pass stops, nothing is lost, and the rest is first in line next time. The first pass on a database that has never been swept checks everything, so at 50,000 requirements it takes several nights (about 0.2 s each);
4. **resets and checks everything again when the rule's threshold changes**, since the earlier checks were made under another one;
5. does nothing, and moves nothing, for a rule that cannot run (AI off): what accumulated while it was off is checked when it comes back.

What this changes in what a sweep reports, which the product owner chose (D48): the sweep no longer returns "the top 500 pairs"; each requirement it checks reports every pair above the threshold, as a write does. The old cap let a sweep resolve per-write findings beyond the 500.

**Not covered by the sweep, and still the job of the write path:** a requirement moved to another capability keeps its embedding, so it is not "changed" here, but the edit that moved it re-checks it at once. A requirement embedded in a transaction that commits after a later one has been checked can carry an older timestamp and be missed until the position is reset (a threshold change does it); the write that created or changed it checked it already.

## A finding about a pair

A finding about two requirements is about the **lower id** and names the other in its fingerprint. It is now also stored in `finding.related_object_id`, so re-checking **either** requirement finds it: a pair that stops being similar because the other requirement changed is resolved by checking whichever one changed, and deleting a requirement now re-checks it so the findings about it (and about pairs it was half of) are resolved at once instead of at night. Findings about one object have no related object.

## Writes that check what they changed

A write re-checks the requirement it changed in the same transaction (VYB-0161). A finding is shared between the writes that touch the same requirements, and two writes that took their locks on shared findings in different orders deadlocked: the load rehearsal at 60 concurrent people had the database stop writes with "deadlock detected", and the write failed with a 500 whose message said only "current transaction is aborted". Now:

- **Findings are locked and written one at a time in ascending fingerprint order** (`FindingReconciler.reconcileOne`: each row `FOR UPDATE` at its turn, new ones inserted at theirs). With every write taking its locks in the same order, none can wait on another in a circle, including for a finding another write creates in the meantime.
- **A database error during the check refuses the write with its real cause** (it used to be swallowed, which left the transaction aborted and failed the write's next statement). A write the database stopped over a lock conflict answers **409 "Try again"** and nothing it did is saved. Failures that are not database errors (a detector bug) are still only logged: detection is secondary.
- One deadlock is still possible in theory, between a bulk edit that holds two requirements and a write to one of them that shares a finding with the other; it ends as the 409 above.

## Operating it

`detection_sweep_state` shows, per rule, the position, the number checked in the last pass and when it last finished. Requirements waiting for a rule are those with `embedded_at` after its position.
