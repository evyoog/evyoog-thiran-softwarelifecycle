import { useState, type ReactNode } from 'react'
import { List, LayoutGrid } from 'lucide-react'
import { Modal } from '@/shared/ui/Modal'
import type { Capability, ImportCandidateInfo } from '@/shared/api/client'

/**
 * VYB-0666: what a standard-template import extracted, in the register's own two shapes.
 *
 * <p>The card list the prose imports use is built for reading one paragraph at a time,
 * which is the work when a model produced the candidate. A template import is the
 * opposite: the author already filled in a grid, so the questions are "did every row come
 * through" and "did the columns land in the right places".
 *
 * <p>Two views, because a template import raises two different questions.
 * <strong>List</strong> is the register's own requirements grid — the fastest way to check
 * twenty rows against the spreadsheet they came from, at the cost of showing only the
 * columns that fit a scannable row. <strong>Cards</strong> reads them as requirements
 * rather than as rows, with the full extraction behind a click.
 *
 * <p>List is fixed-layout on percentage widths rather than content-sized: a long
 * statement in an auto-layout table pushes the columns after it off the right-hand edge,
 * which is how the Capability control became unreachable in the first version of this
 * screen.
 */
export function PrdTable({
  candidates, capabilities, onSelect, onConfirmCapability, actions,
}: {
  candidates: ImportCandidateInfo[]
  capabilities: Capability[]
  onSelect: (id: string, selected: boolean) => void
  onConfirmCapability: (id: string, capabilityId: string) => void
  /** The import action — owned by the screen, but it belongs on this toolbar. */
  actions?: ReactNode
}) {
  const [view, setView] = useState<'list' | 'cards'>('list')
  const [openId, setOpenId] = useState<string | null>(null)

  const selectable = candidates.filter((c) => !c.committedRequirementId)
  const allSelected = selectable.length > 0 && selectable.every((c) => c.selected)
  const open = candidates.find((c) => c.id === openId) ?? null

  return (
    <>
      <div className="prd-bar">
        <div className="seg">
          <button className={view === 'list' ? 'on' : ''} onClick={() => setView('list')} aria-pressed={view === 'list'}>
            <List /> List
          </button>
          <button className={view === 'cards' ? 'on' : ''} onClick={() => setView('cards')} aria-pressed={view === 'cards'}>
            <LayoutGrid /> Cards
          </button>
        </div>
        <div className="prd-bar-r">
          <label className="prd-all">
            <input
              type="checkbox" checked={allSelected}
              onChange={() => selectable.forEach((c) => onSelect(c.id, !allSelected))}
            />
            Tick all
          </label>
          {actions}
        </div>
      </div>

      {view === 'list' ? (
        <div className="prd-list">
          <table className="rt prd-rt" style={{ tableLayout: 'fixed' }}>
            <thead>
              <tr>
                <th style={{ width: '4%' }} />
                <th style={{ width: '6%' }}>Row</th>
                <th style={{ width: '12%' }}>Ref</th>
                <th style={{ width: '34%' }}>Requirement</th>
                <th style={{ width: '12%' }}>Type</th>
                <th style={{ width: '9%' }}>Priority</th>
                <th style={{ width: '17%' }}>Capability</th>
                <th style={{ width: '6%' }}>AC</th>
              </tr>
            </thead>
            <tbody>
              {candidates.map((c) => (
                <ListRow
                  key={c.id} c={c} capabilities={capabilities}
                  onSelect={onSelect} onConfirmCapability={onConfirmCapability}
                  onOpen={() => setOpenId(c.id)}
                />
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="prd-cards">
          {candidates.map((c) => (
            <CandidateTile key={c.id} c={c} capabilities={capabilities} onSelect={onSelect} onOpen={() => setOpenId(c.id)} />
          ))}
        </div>
      )}

      {open && (
        <Modal onClose={() => setOpenId(null)} title={detail(open).title ?? 'Extracted requirement'}>
          <CandidateDetail
            c={open} capabilities={capabilities}
            onConfirmCapability={onConfirmCapability}
          />
        </Modal>
      )}
    </>
  )
}

// ---------------------------------------------------------------------------

function parse(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}

function criteriaOf(c: ImportCandidateInfo): string[] {
  if (!c.acceptedCriteria) return []
  try {
    const parsed = JSON.parse(c.acceptedCriteria)
    return Array.isArray(parsed) ? (parsed as string[]) : []
  } catch {
    return []
  }
}

/** Everything the template put on this row, read once so the three views agree. */
function detail(c: ImportCandidateInfo) {
  const flags = parse(c.flags)
  return {
    title: flags.briefTitle as string | undefined,
    priority: flags.briefPriority as string | undefined,
    sourceRow: flags.prdSourceRow as number | undefined,
    problems: (flags.prdProblems as string[] | undefined) ?? [],
    criteria: criteriaOf(c),
    tags: (flags.prdTags as string[] | undefined) ?? [],
    dependsOn: (flags.prdDependsOn as string[] | undefined) ?? [],
  }
}

// ---------------------------------------------------------------------------

function ListRow({
  c, capabilities, onSelect, onConfirmCapability, onOpen,
}: {
  c: ImportCandidateInfo
  capabilities: Capability[]
  onSelect: (id: string, selected: boolean) => void
  onConfirmCapability: (id: string, capabilityId: string) => void
  onOpen: () => void
}) {
  const d = detail(c)

  return (
    <tr>
      <td>
        <input
          type="checkbox" checked={c.selected} disabled={!!c.committedRequirementId}
          aria-label={`Import ${d.title ?? c.statement}`}
          onChange={(e) => onSelect(c.id, e.target.checked)}
        />
      </td>
      <td className="mono muted">{d.sourceRow ?? '—'}</td>
      <td className="c-id prd-ell">{c.tag ?? '—'}</td>
      <td>
        <button className="prd-open" onClick={onOpen} title="Open the full extraction">
          <span className="prd-title prd-ell">
            {d.problems.length > 0 && <span className="prd-flag" title="Needs fixing">!</span>}
            {d.title ?? '—'}
          </span>
          <span className="prd-sub prd-ell">{c.statement}</span>
        </button>
      </td>
      <td>{c.proposedType
        ? <span className="badge">{c.proposedType.replace(/_/g, ' ')}</span>
        : <span className="muted">—</span>}</td>
      <td>{d.priority
        ? <span className={`badge pr-${d.priority.toLowerCase()}`}>{d.priority}</span>
        : <span className="muted">—</span>}</td>
      <td><CapabilityPick c={c} capabilities={capabilities} onConfirm={onConfirmCapability} /></td>
      <td className="muted">{d.criteria.length || '—'}</td>
    </tr>
  )
}

// ---------------------------------------------------------------------------

function CandidateTile({
  c, capabilities, onSelect, onOpen,
}: {
  c: ImportCandidateInfo
  capabilities: Capability[]
  onSelect: (id: string, selected: boolean) => void
  onOpen: () => void
}) {
  const d = detail(c)
  const committed = !!c.committedRequirementId
  const resolved = capabilityResolved(c)
  const capabilityName = capabilities.find((cap) => cap.id === c.capabilityId)?.name

  return (
    <article className={`prd-card${c.selected ? ' on' : ''}`}>
      {/* The tick is the card's own control and must not open the modal, so it sits
          outside the button rather than inside it. */}
      <label className="prd-card-tick" onClick={(e) => e.stopPropagation()}>
        <input
          type="checkbox" checked={c.selected} disabled={committed}
          aria-label={`Import ${d.title ?? c.statement}`}
          onChange={(e) => onSelect(c.id, e.target.checked)}
        />
      </label>

      <button className="prd-card-b" onClick={onOpen}>
        <header className="prd-card-h">
          <span className="c-id">{c.tag ?? `Row ${d.sourceRow ?? '?'}`}</span>
          {d.problems.length > 0 && (
            <span className="prd-card-warn" title={d.problems.join('\n')}>Needs fixing</span>
          )}
        </header>

        <h4 className="prd-card-t">{d.title ?? '—'}</h4>
        {/* Three lines of statement: enough to tell one requirement from another without
            the tiles turning into a wall of prose. The rest is one click away. */}
        <p className="prd-card-s">{c.statement}</p>

        <footer className="prd-card-f">
          {c.proposedType && <span className="badge">{c.proposedType.replace(/_/g, ' ')}</span>}
          {d.priority && <span className={`badge pr-${d.priority.toLowerCase()}`}>{d.priority}</span>}
          <span className="prd-card-ac">
            {d.criteria.length > 0
              ? `${d.criteria.length} acceptance criteri${d.criteria.length === 1 ? 'on' : 'a'}`
              : 'No acceptance criteria'}
          </span>
        </footer>

        {/* VYB-0666: auto-confirmed capabilities show their name; anything the sheet's
            Capability column could not match stays visibly unconfirmed rather than
            looking the same as a row that needs no attention. */}
        {resolved ? (
          <span className="prd-card-cap prd-card-cap-ok">
            <span className="prd-cap-ok-dot" />{capabilityName ?? c.capabilityId}
          </span>
        ) : (
          <span className="prd-card-cap prd-card-cap-bad">Capability not confirmed</span>
        )}
      </button>
    </article>
  )
}

// ---------------------------------------------------------------------------

/**
 * A single column from the template as its own sub-heading and value — the modal's whole
 * layout is a list of these, in the template's own column order, so every column is
 * visible on open rather than a chosen few with the rest folded into one summary block.
 * An absent value renders "Not in your sheet" rather than the row being skipped, so a
 * blank column reads as checked-and-empty, not as never having been looked at.
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="prd-f">
      <span className="prd-f-l">{label}</span>
      <div className="prd-f-v">{children ?? <span className="muted">Not in your sheet</span>}</div>
    </div>
  )
}

function CandidateDetail({
  c, capabilities, onConfirmCapability,
}: {
  c: ImportCandidateInfo
  capabilities: Capability[]
  onConfirmCapability: (id: string, capabilityId: string) => void
}) {
  const flags = parse(c.flags)
  const d = detail(c)
  const cellText = (v: unknown) => (typeof v === 'string' && v.trim() ? v : undefined)

  return (
    <div className="prd-modal">
      {d.problems.length > 0 && (
        <div className="prd-prob">
          <span className="eyebrow">Needs fixing</span>
          {d.problems.map((p) => <div key={p}>{p}</div>)}
        </div>
      )}

      {/* Every column the template has, in its own order — the sheet's own # and Ready?
          are left out (bookkeeping the sheet computed for itself, not requirement data),
          as are the register's write-back columns (Vyoog ID, AI Quality Score, AI Flags,
          Import Status), which the sheet never supplied in the first place. */}
      <Field label="Row (#)">{d.sourceRow}</Field>
      <Field label="Your Ref">{c.tag || undefined}</Field>
      <Field label="Requirement Title">{cellText(flags.briefTitle)}</Field>
      <Field label="Requirement Statement"><span className="prd-stmt">{c.statement}</span></Field>
      <Field label="Type">
        {c.proposedType && <span className="badge">{c.proposedType.replace(/_/g, ' ')}</span>}
      </Field>
      <Field label="Priority">
        {d.priority && <span className={`badge pr-${d.priority.toLowerCase()}`}>{d.priority}</span>}
      </Field>
      <Field label="Acceptance Criteria">
        {d.criteria.length > 0 && (
          <ol className="prd-ac">{d.criteria.map((a) => <li key={a}>{a}</li>)}</ol>
        )}
      </Field>
      <Field label="Verification Method">{cellText(flags.prdVerificationMethod)}</Field>
      <Field label="Owner">{cellText(flags.prdOwnerName)}</Field>
      <Field label="Source / Requested By">{cellText(flags.prdRequestedBy)}</Field>
      <Field label="Parent Requirement">{cellText(flags.prdParentRef)}</Field>
      <Field label="Depends On">{d.dependsOn.length > 0 ? d.dependsOn.join(', ') : undefined}</Field>
      <Field label="Tags">{d.tags.length > 0 ? d.tags.join(', ') : undefined}</Field>
      <Field label="Regulatory Reference">{cellText(flags.prdRegulatoryReference)}</Field>
      <Field label="Target Release">{cellText(flags.prdTargetRelease)}</Field>
      <Field label="Notes">{cellText(flags.briefDescription)}</Field>
      <Field label="Capability">
        <CapabilityPick c={c} capabilities={capabilities} onConfirm={onConfirmCapability} />
      </Field>
    </div>
  )
}

// ---------------------------------------------------------------------------

/**
 * Whether the sheet's own Capability column named a real capability that exists in this
 * application — the strict question, distinct from {@code c.capabilityConfirmed}. That
 * flag is also true when the row only resolved as far as the application or the product
 * (a real placement, but not the capability the author actually typed), which is exactly
 * the case this control needs to keep asking about. {@code placementLevel === 'CAPABILITY'}
 * is the one signal that means the leaf itself matched.
 */
function capabilityResolved(c: ImportCandidateInfo): boolean {
  return c.placementLevel === 'CAPABILITY' && !!c.capabilityId
}

/**
 * The capability this row will import into.
 *
 * <p>Auto-confirmed when the sheet's own Capability column named one that exists in this
 * application — {@link PrdTemplateResolver} on the backend already matched it, so showing
 * a picker here and making the reviewer choose it again would be asking them to re-approve
 * their own spreadsheet cell. Resolved rows render the name as a plain, confirmed value; a
 * pencil re-opens the picker for the rare case the match was to the wrong capability.
 *
 * <p>Unresolved rows render the picker open, with a highlighted border — this is one of
 * the two things (with acceptance criteria and mandatory fields) that keeps a row out of
 * the grid, so it should not look identical to a row that needs no attention at all.
 */
function CapabilityPick({
  c, capabilities, onConfirm,
}: {
  c: ImportCandidateInfo
  capabilities: Capability[]
  onConfirm: (id: string, capabilityId: string) => void
}) {
  const resolved = capabilityResolved(c)
  const [editing, setEditing] = useState(false)
  const [choice, setChoice] = useState(c.capabilityId ?? '')
  const chosen = choice || c.capabilityId || ''
  // Confirmation stays an explicit act rather than a side effect of the select firing: a
  // pre-filled dropdown left alone never fires onChange, and an application with a single
  // capability has no second option to switch to — both left rows permanently unconfirmed
  // the last time this was wired that way.
  const pending = chosen !== '' && chosen !== c.capabilityId
  const resolvedName = capabilities.find((cap) => cap.id === c.capabilityId)?.name

  if (capabilities.length === 0) {
    return (
      <span className="muted prd-cap-none">
        No capabilities in this application yet — add one in Portfolio, or import at
        application level.
      </span>
    )
  }

  if (resolved && !editing) {
    return (
      <button className="prd-cap-ok" onClick={() => setEditing(true)} title="Matched from your sheet's Capability column — click to change">
        <span className="prd-cap-ok-dot" />
        {resolvedName ?? c.capabilityId}
      </button>
    )
  }

  return (
    <div className={`prd-cap${resolved ? '' : ' prd-cap-bad'}`}>
      <select className="select" value={chosen} aria-label="Capability" onChange={(e) => setChoice(e.target.value)}>
        <option value="">This application</option>
        {capabilities.map((cap) => <option key={cap.id} value={cap.id}>{cap.name}</option>)}
      </select>
      {pending && (
        <button className="btn pri" onClick={() => { onConfirm(c.id, chosen); setEditing(false) }}>Set</button>
      )}
      {resolved && !pending && (
        <button className="btn" onClick={() => setEditing(false)}>Cancel</button>
      )}
    </div>
  )
}
