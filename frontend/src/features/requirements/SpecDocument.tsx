import type { Capability, Requirement } from '@/shared/api/client'

/**
 * The prototype's Document view: the same requirements the grid is showing, read as the
 * specification they add up to rather than as rows.
 *
 * <p>The grid answers "which one do I open"; this answers "does this read as a coherent
 * spec". Those are different jobs, which is why it is a view and not a second screen —
 * it renders whatever the grid's filters and scope currently select, so narrowing to a
 * capability narrows the document to that capability's section.
 *
 * <p>Nothing is generated here. Every sentence is the requirement's own statement, every
 * heading is a real capability, and the numbering is positional (4.1, 4.2) rather than
 * stored — a document that invented prose to join requirements up would be a different
 * artefact from the register, and the two would drift.
 */
export function SpecDocument({
  requirements, capabilities, scopeLabel, onOpen,
}: {
  requirements: Requirement[]
  capabilities: Capability[]
  scopeLabel: string
  onOpen: (id: string) => void
}) {
  const nameOf = new Map(capabilities.map((c) => [c.id, c.name]))

  // Grouped in the order the requirements arrive, so the document follows the grid's sort
  // rather than imposing an alphabetical one the grid never showed.
  const sections = new Map<string, Requirement[]>()
  for (const r of requirements) {
    const key = r.capabilityId ? (nameOf.get(r.capabilityId) ?? 'Unnamed capability') : 'Not placed under a capability'
    const list = sections.get(key)
    if (list) list.push(r)
    else sections.set(key, [r])
  }

  const revision = requirements.reduce((max, r) => Math.max(max, r.revision), 0)

  return (
    <div className="specdoc">
      <h2 className="spec-h1">{scopeLabel} — System Specification</h2>
      <div className="spec-meta">
        {requirements.length} item{requirements.length === 1 ? '' : 's'} · highest revision {revision} ·
        {' '}{sections.size} section{sections.size === 1 ? '' : 's'} · generated from the register, not stored
      </div>

      {[...sections.entries()].map(([capability, items], sectionIndex) => (
        <section key={capability}>
          <div className="spec-h2">
            <span className="no">4.{sectionIndex + 1}</span> {capability}
          </div>
          {items.map((r) => <SpecItem key={r.id} r={r} onOpen={onOpen} />)}
        </section>
      ))}
    </div>
  )
}

/**
 * Why this requirement is worth a reader's attention, or null when nothing is.
 *
 * <p>Deliberately only the things the register actually knows. The prototype's mock-up
 * showed a model's rewrite suggestion under a flagged item; that is real elsewhere in the
 * product but it is not on this payload, so inventing one here would be putting words in
 * an agent's mouth. What is known is the quality score and the coverage — both computed,
 * both checkable.
 */
function concernOf(r: Requirement): string | null {
  if (r.qualityScore !== undefined && r.qualityScore < 60) {
    return `Quality score ${r.qualityScore}/100 — open it to see which checks failed.`
  }
  if (!r.hasTest) {
    return 'No passing test at the current revision, so nothing yet proves this works.'
  }
  return null
}

function SpecItem({ r, onOpen }: { r: Requirement; onOpen: (id: string) => void }) {
  const concern = concernOf(r)

  return (
    <div className={`spec-item${concern ? ' flag' : ''}`}>
      <div className="spec-id">
        <button className="spec-key" onClick={() => onOpen(r.id)} title="Open this requirement">
          {r.key}
        </button>
        <span className={`badge st-${r.status.toLowerCase()}`}>{r.status.toLowerCase().replace(/_/g, ' ')}</span>
        <span className={`badge pr-${r.priority.toLowerCase()}`}>{r.priority.toLowerCase()}</span>
        <span className="muted">{r.type.replace(/_/g, ' ').toLowerCase()}</span>
      </div>
      <p className="spec-tx">{r.statement}</p>
      {/* Not amber: a missing test and a low score are facts the register computed, not a
          model's opinion (Principle 5). */}
      {concern && <div className="spec-note">{concern}</div>}
    </div>
  )
}
