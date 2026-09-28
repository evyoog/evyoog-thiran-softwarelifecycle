import { useMemo, useRef, useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { api, type BulkEditRequest, type Requirement, type RequirementPriority, type RequirementStatus, type RequirementType, type TraceLink } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { CapabilityPicker } from '@/shared/ui/CapabilityPicker'

export const STATUSES: RequirementStatus[] = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED']

/**
 * The server's own state machine (RequirementStatus.allowedNext), mirrored so the modal
 * can say which rows a status will actually move before you apply it. The server remains
 * the authority — this only stops the dialog offering a move that will skip every row and
 * report it afterwards with no explanation.
 *
 * VYB-0813 (D17): a full replacement of the prior five-state machine, not an extension
 * of it — NEEDS_REVISION is new, REJECTED now reopens into NEEDS_REVISION rather than
 * DRAFT, rejecting directly from IN_REVIEW is gone (a decision is only made once a
 * review is actually complete), and APPROVED is fully terminal (no transition leaves it —
 * editing an approved requirement is refused, and forking a new version on such an edit
 * is a deferred follow-up, not built yet).
 */
export const ALLOWED_NEXT: Record<RequirementStatus, RequirementStatus[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['DRAFT', 'REVIEWED'],
  REVIEWED: ['APPROVED', 'REJECTED', 'NEEDS_REVISION'],
  NEEDS_REVISION: ['IN_REVIEW'],
  APPROVED: [],
  REJECTED: ['NEEDS_REVISION'],
}

/** How many of the selected rows a given target would actually move. Same status counts as reachable — it is a no-op, not a skip. */
export function reachableCount(rows: Requirement[], target: RequirementStatus): number {
  return rows.filter((r) => r.status === target || ALLOWED_NEXT[r.status].includes(target)).length
}
const PRIORITIES: RequirementPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const TYPES: RequirementType[] = [
  'FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE', 'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE',
]

/** The one value every row shares, or undefined when the selection actually differs. */
export function sharedValue<T>(rows: Requirement[], pick: (r: Requirement) => T): T | undefined {
  if (rows.length === 0) return undefined
  const first = pick(rows[0])
  return rows.every((r) => pick(r) === first) ? first : undefined
}

/**
 * VYB-0666: the ids a requirement's own DERIVES links point at that are not part of the
 * set being bulk-edited — the real-trace-link equivalent of the import queue's Depends
 * On check. Only DERIVES, and only toward another REQUIREMENT: a SATISFIES or VERIFIES
 * link names a different kind of relationship, not "this one needs that one first".
 */
export function unselectedDependencyIds(outgoing: TraceLink[], effectiveIds: Set<string>): string[] {
  return outgoing
    .filter((l) => l.linkType === 'DERIVES' && l.toType === 'REQUIREMENT' && !effectiveIds.has(l.toId))
    .map((l) => l.toId)
}

/**
 * VYB-0180: every field defaults to leave-unchanged (AC1); the modal states how many
 * rows and fields will change before applying (AC2).
 *
 * <p>Capability is set here, not only cleared. It used to offer nothing but "unassign",
 * because that is the one capability action needing no picker — so a requirement
 * imported under the wrong capability, or committed before anyone decided, could not be
 * moved from this screen at all. {@link CapabilityPicker} is that picker; the backend has
 * accepted {@code capabilityId} alongside {@code touchCapability} since VYB-0121.
 *
 * <p>Owner reassignment is still not offered — that needs a decision about who may
 * reassign whose work, which no requirement here settles.
 */
export function BulkEditModal({
  rows,
  onApply,
  onCancel,
  initialApplicationId,
  pending,
  error,
}: {
  rows: Requirement[]
  onApply: (body: BulkEditRequest) => void
  onCancel: () => void
  /** Pre-selects the application the grid is already scoped to, so the picker opens near the answer. */
  initialApplicationId?: string
  /** True while the last Apply is still in flight — disables a second click rather than letting it queue behind the first and trip the server's own rate limit (VYB-0782). */
  pending?: boolean
  /** The last Apply's failure, if any, already resolved to a human-readable string by the caller. */
  error?: string
}) {
  // VYB-0666: "auto load the fields" — when every selected row already agrees on a
  // priority, type or capability, the field opens showing that value instead of a blank
  // "leave unchanged" placeholder the person has to re-derive by eye from the grid. A
  // mixed selection still opens blank, because there is no one true value to show.
  //
  // Showing the current value is not the same as marking it changed: initial* freezes
  // what the field opened with, and "touched" compares the live value against that
  // frozen one, not against "" — so applying without touching anything sends nothing for
  // that field, exactly as if it had opened blank. Status is deliberately left out of
  // this: it is a transition to apply, not an attribute to display, and prefilling it to
  // the row's own current status would make "leave unchanged" and "transition to the
  // status already held" the same selection while needing different reason-field
  // behaviour — a state machine's current state is not its own valid next move.
  const initialPriority = useRef(sharedValue(rows, (r) => r.priority) ?? '').current
  const initialType = useRef(sharedValue(rows, (r) => r.type) ?? '').current
  const sharedCapability = useRef(sharedValue(rows, (r) => r.capabilityId ?? null)).current
  const initialCapabilityId = sharedCapability === undefined ? '' : sharedCapability

  const [status, setStatus] = useState('')
  const [priority, setPriority] = useState(initialPriority)
  const [type, setType] = useState(initialType)
  const [reason, setReason] = useState('')
  const [capabilityId, setCapabilityId] = useState<string | null>(initialCapabilityId)

  // VYB-0666: the same check the import queue's confirm dialog already does for a fresh
  // import — here for bulk-editing requirements that already exist. "Depends On" from a
  // PRD template becomes a real DERIVES trace link once both sides are committed
  // (ImportService#commit), so the check reads that real link rather than a sheet
  // column: if a selected requirement depends on another one that is not part of this
  // bulk action, applying anyway carries out half of a relationship the sheet — or
  // whoever linked them by hand — explicitly recorded. "Tick it too" adds the missing
  // one to what gets edited, without needing it to be ticked in the grid behind this
  // modal (which may not even have it loaded, on another page).
  const [extraRows, setExtraRows] = useState<Requirement[]>([])
  const effectiveRows = useMemo(
    () => [...rows, ...extraRows.filter((x) => !rows.some((r) => r.id === x.id))],
    [rows, extraRows],
  )
  const effectiveIds = useMemo(() => new Set(effectiveRows.map((r) => r.id)), [effectiveRows])

  const linkQueries = useQueries({
    queries: rows.map((r) => ({
      queryKey: ['trace-links', r.id],
      queryFn: () => api.links('REQUIREMENT', r.id),
    })),
  })
  const missingDepIds = useMemo(() => {
    const ids = new Set<string>()
    rows.forEach((_r, i) => {
      for (const id of unselectedDependencyIds(linkQueries[i]?.data?.outgoing ?? [], effectiveIds)) ids.add(id)
    })
    return [...ids]
  }, [rows, effectiveIds, linkQueries.map((q) => q.dataUpdatedAt).join()])

  const depTargetQueries = useQueries({
    queries: missingDepIds.map((id) => ({
      queryKey: ['requirement', id],
      queryFn: () => api.requirement(id),
    })),
  })
  const depTargetById = new Map(missingDepIds.map((id, i) => [id, depTargetQueries[i]?.data]))

  const depWarnings = rows
    .map((r, i) => ({
      source: r,
      missing: unselectedDependencyIds(linkQueries[i]?.data?.outgoing ?? [], effectiveIds)
        .map((id) => depTargetById.get(id))
        .filter((t): t is Requirement => !!t),
    }))
    .filter((w) => w.missing.length > 0)

  const selectedIds = effectiveRows.map((r) => r.id)
  // A field only actually changes when its value is neither still "— leave unchanged —"
  // (''), nor back to whatever it opened with — the second clause is what makes
  // re-selecting "leave unchanged" after this auto-filled a real value behave exactly
  // like never having touched it, instead of counting as a change to an empty string.
  const sendPriority = priority !== '' && priority !== initialPriority
  const sendType = type !== '' && type !== initialType
  const touchCapability = capabilityId !== '' && capabilityId !== initialCapabilityId
  const changedFieldCount = (sendPriority ? 1 : 0) + (sendType ? 1 : 0)
    + (status ? 1 : 0) + (touchCapability ? 1 : 0)

  // Principle 8: shown once, above the fields, so "auto-filled" is legible rather than
  // something the reviewer has to notice by eye against the grid. Capability has no name
  // available without a lookup this component doesn't have, so it states presence
  // rather than inventing a label.
  const statusSummary = sharedValue(rows, (r) => r.status) ?? 'mixed'
  const capabilitySummary = sharedCapability === undefined ? 'different capabilities'
    : sharedCapability === null ? 'no capability assigned' : 'one shared capability'

  const target = status ? (status as RequirementStatus) : null
  const movable = target ? reachableCount(effectiveRows, target) : effectiveRows.length
  // Required for a rejection, which always carries one. For a submit it is an override
  // the server demands per row for requirements with no acceptance criteria — the grid
  // row carries no criteria count, so the client cannot tell which rows those are and
  // offers the field rather than pretending to know.
  const needsReason = target === 'REJECTED'
  // VYB-0813: NEEDS_REVISION only actually requires a reason for a row coming from
  // REVIEWED (rejecting it back to NEEDS_REVISION already carries one) — a mixed
  // selection can have both, so this offers the field without hard-blocking Apply,
  // the same treatment IN_REVIEW's acceptance-criteria override already gets.
  const offersReason = needsReason || target === 'IN_REVIEW' || target === 'NEEDS_REVISION'
  const blocked = !!target && movable === 0
  const canApply = changedFieldCount > 0 && !blocked && (!needsReason || reason.trim().length > 0) && !pending

  return (
    <Modal onClose={onCancel} title={`Bulk edit ${selectedIds.length} requirement${selectedIds.length === 1 ? '' : 's'}`}>
        <h3>Bulk edit {selectedIds.length} requirement{selectedIds.length === 1 ? '' : 's'}</h3>
        <p className="hint muted">Every field left as "unchanged" is not touched. An illegal status move skips only that row.</p>
        <p className="hint muted">
          Currently: <strong>{statusSummary}</strong> · {initialPriority || 'mixed priorities'} · {initialType || 'mixed types'} · {capabilitySummary}.
          {(initialPriority || initialType || initialCapabilityId !== '') && ' Priority, type and capability below already show what every selected row shares.'}
        </p>
        {/* VYB-0814: the request that just failed, named — not a silently closed modal
            someone has to guess about, or a reason to click Apply again into the same
            5-second rate-limit window that (if this was it) is exactly what produced it. */}
        {error && <p className="err-text">{error}</p>}

        {/* VYB-0666: a selected requirement's own recorded dependency, pointing outside
            this bulk action. Naming it here — not just leaving it to be discovered later
            as an inconsistency — is what "confirm capability and import that requirement
            too" means once the dependency is a real link rather than a sheet column. */}
        {depWarnings.length > 0 && (
          <div className="prd-depwarn">
            <span className="eyebrow">Depends on requirements not included here</span>
            {depWarnings.map(({ source, missing }) => (
              <div key={source.id} className="prd-depwarn-row">
                <span className="mono">{source.key}</span> depends on{' '}
                {missing.map((m, i) => (
                  <span key={m.id}>
                    {i > 0 && ', '}
                    <span className="mono">{m.key}</span>
                    <button
                      className="btn" style={{ marginLeft: 6, fontSize: 10.5, padding: '2px 7px' }}
                      onClick={() => setExtraRows((prev) => prev.some((x) => x.id === m.id) ? prev : [...prev, m])}
                    >
                      Include it too
                    </button>
                  </span>
                ))}
              </div>
            ))}
          </div>
        )}

        <div className="field">
          <label className="label">Status</label>
          <select className="select" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">— leave unchanged —</option>
            {STATUSES.map((s) => {
              const n = reachableCount(effectiveRows, s)
              return (
                <option key={s} value={s} disabled={n === 0}>
                  {s}
                  {n === 0 ? ' — not reachable from any selected row'
                    : n < effectiveRows.length ? ` — ${n} of ${effectiveRows.length} rows` : ''}
                </option>
              )
            })}
          </select>
          {/* The reported bug was invisible here: every row skipped and the only feedback
              was a count. Which rows a move can reach is knowable before applying, so it
              is said before applying. */}
          {blocked && (
            <span className="err-text" style={{ fontSize: 11 }}>
              No selected requirement can move to {status}. Every row would be skipped.
            </span>
          )}
          {!blocked && target && movable < effectiveRows.length && (
            <span className="hint">{effectiveRows.length - movable} row{effectiveRows.length - movable === 1 ? '' : 's'} will be skipped — their current status has no legal move to {status}.</span>
          )}
        </div>

        {offersReason && (
          <div className="field">
            <label className="label">
              Reason {needsReason ? '(required)'
                : target === 'NEEDS_REVISION' ? '(required for rows coming from Reviewed)'
                : '(override, if any row has no acceptance criteria)'}
            </label>
            <input className="input" value={reason} onChange={(e) => setReason(e.target.value)}
              placeholder={target === 'REJECTED' ? 'Why these are rejected'
                : target === 'NEEDS_REVISION' ? 'What needs to change'
                : 'Why these go to review with no acceptance criteria'} />
            <span className="hint">
              {target === 'REJECTED'
                ? 'A rejection is recorded with its reason, the same as rejecting one at a time.'
                : target === 'NEEDS_REVISION'
                ? 'A row coming from Reviewed needs a reason; a row coming from Rejected (reopening) does not and is not skipped without one.'
                : 'A requirement with no acceptance criteria can only be submitted with an override reason. Rows that already have criteria do not need it, and rows that need it are skipped without one.'}
            </span>
          </div>
        )}
        <div className="field">
          <label className="label">Priority</label>
          <select className="select" value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">— leave unchanged —</option>
            {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label">Type</label>
          <select className="select" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">— leave unchanged —</option>
            {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div className="divider" />
        <CapabilityPicker
          value={capabilityId}
          onChange={setCapabilityId}
          allowUnassign
          initialApplicationId={initialApplicationId}
        />
        {touchCapability && (
          <p className="hint muted" style={{ marginTop: -6 }}>
            {capabilityId === null
              ? `All ${effectiveRows.length} selected requirement${effectiveRows.length === 1 ? '' : 's'} will be left with no capability.`
              : `All ${effectiveRows.length} selected requirement${effectiveRows.length === 1 ? '' : 's'} move to this capability.`}
          </p>
        )}

        <div className="actions">
          <button className="btn" onClick={onCancel}>Cancel</button>
          <button
            className="btn pri"
            disabled={!canApply}
            onClick={() => onApply({
              ids: selectedIds,
              status: status ? (status as RequirementStatus) : undefined,
              // Not a bare `priority || undefined`: priority/type can hold a real value
              // that this modal auto-filled from the rows' own shared current value
              // rather than one the reviewer chose to change — only send it when it
              // actually differs from what every row already had.
              priority: sendPriority ? priority : undefined,
              type: sendType ? type : undefined,
              reason: reason.trim() || undefined,
              touchCapability,
              capabilityId: capabilityId || undefined,
            })}
          >
            {pending ? 'Applying…' : `Apply to ${selectedIds.length} row${selectedIds.length === 1 ? '' : 's'} · ${changedFieldCount} field${changedFieldCount === 1 ? '' : 's'}`}
          </button>
        </div>
    </Modal>
  )
}
