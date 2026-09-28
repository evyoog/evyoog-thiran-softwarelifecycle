import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Plus, Search, ChevronDown, ChevronRight, Edit2, X, List, Waypoints } from 'lucide-react'
import { api, type RequirementTestCaseSummary, type TestCase, type TestCaseSuggestionCategory } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { TestCaseAuthoringPanel } from './TestCaseAuthoringPanel'
import { DescriptionView } from './SuggestionCard'
import { DependencyDiagram, type DiagramEdge, type DiagramNode } from './DependencyDiagram'

type View = 'list' | 'diagram'

export function TestCasesTab() {
  const [view, setView] = useState<View>('list')

  return (
    <>
      <p className="hint muted" style={{ marginBottom: 12 }}>
        Every requirement that has at least one test case. Individual test cases validate the requirement's own
        screens, fields and behaviour on its own; Dependency ones validate it together with something it's
        trace-linked to.
      </p>

      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <button className={`btn${view === 'list' ? ' pri' : ''}`} onClick={() => setView('list')}>
          <List size={14} /> List
        </button>
        <button className={`btn${view === 'diagram' ? ' pri' : ''}`} onClick={() => setView('diagram')}>
          <Waypoints size={14} /> Diagram
        </button>
      </div>

      {view === 'list' ? <ListView /> : <DiagramView />}
    </>
  )
}

function ListView() {
  const [q, setQ] = useState('')
  const [page, setPage] = useState(0)
  const [expanded, setExpanded] = useState('')

  const { data, isLoading } = useQuery({
    queryKey: ['test-cases-by-requirement', q, page],
    queryFn: () => api.testCasesByRequirement({ q: q.trim() || undefined, page, size: 20 }),
  })

  return (
    <>
      <div className="field" style={{ maxWidth: 380 }}>
        <label className="label">Search</label>
        <div style={{ position: 'relative' }}>
          <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--tx-3)' }} />
          <input className="input" style={{ paddingLeft: 30, width: '100%' }} placeholder="Requirement or test case key/title"
            value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} />
        </div>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && data.content.length === 0 && (
        <Empty title="No test cases yet" desc={q ? 'Nothing matches this search.' : 'Draft or generate one from the Verification tab.'} />
      )}

      {data?.content.map((r) => (
        <RequirementRow key={r.requirementId} summary={r} expanded={expanded === r.requirementId}
          onToggle={() => setExpanded((e) => (e === r.requirementId ? '' : r.requirementId))} />
      ))}

      {data && data.totalPages > 1 && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10 }}>
          <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }} disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
          <span className="mono muted" style={{ fontSize: 11 }}>Page {data.number + 1} of {Math.max(data.totalPages, 1)}</span>
          <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }} disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
        </div>
      )}
    </>
  )
}

/** VYB-0830: only fetched for an expanded row's own links — cheap even though it's one call per open row, the same lazy-on-expand pattern its test cases already use. */
function DependencyHint({ requirementId }: { requirementId: string }) {
  const { data: links } = useQuery({
    queryKey: ['trace-links', requirementId],
    queryFn: () => api.links('REQUIREMENT', requirementId),
  })

  const dependsOnIds = (links?.outgoing ?? [])
    .filter((l) => l.fromType === 'REQUIREMENT' && l.toType === 'REQUIREMENT').map((l) => l.toId)
  const dependedOnByIds = (links?.incoming ?? [])
    .filter((l) => l.fromType === 'REQUIREMENT' && l.toType === 'REQUIREMENT').map((l) => l.fromId)
  const ids = [...new Set([...dependsOnIds, ...dependedOnByIds])]

  const reqQueries = useQueries({
    queries: ids.map((id) => ({ queryKey: ['requirement', id], queryFn: () => api.requirement(id) })),
  })
  const keyById = new Map(ids.map((id, i) => [id, reqQueries[i]?.data?.key]))

  if (!links || (dependsOnIds.length === 0 && dependedOnByIds.length === 0)) return null

  return (
    <p className="hint muted" style={{ marginBottom: 10 }}>
      {dependsOnIds.length > 0 && <>Depends on: {dependsOnIds.map((id) => keyById.get(id) ?? '…').join(', ')}</>}
      {dependsOnIds.length > 0 && dependedOnByIds.length > 0 && ' · '}
      {dependedOnByIds.length > 0 && <>Depended on by: {dependedOnByIds.map((id) => keyById.get(id) ?? '…').join(', ')}</>}
    </p>
  )
}

const DIAGRAM_PAGE_SIZE = 200

/** VYB-0830: the same requirements as List view, drawn as a graph instead of read as a hint line — click a node to see its test cases, the same click-to-inspect pattern the Design screen's diagram already uses. */
function DiagramView() {
  const { data, isLoading } = useQuery({
    queryKey: ['test-cases-by-requirement', 'diagram-all'],
    queryFn: () => api.testCasesByRequirement({ size: DIAGRAM_PAGE_SIZE }),
  })
  const requirements = data?.content ?? []

  const linkQueries = useQueries({
    queries: requirements.map((r) => ({
      queryKey: ['trace-links', r.requirementId],
      queryFn: () => api.links('REQUIREMENT', r.requirementId),
    })),
  })
  const linksLoading = linkQueries.some((q) => q.isLoading)

  const [inspecting, setInspecting] = useState<RequirementTestCaseSummary | null>(null)

  const { nodes, edges } = useMemo(() => {
    const nodes: DiagramNode[] = requirements.map((r) => ({
      id: r.requirementId, key: r.requirementKey, title: r.requirementTitle, testCaseCount: r.totalCount,
    }))
    const edges: DiagramEdge[] = []
    const seen = new Set<string>()
    for (const lq of linkQueries) {
      for (const l of [...(lq.data?.outgoing ?? []), ...(lq.data?.incoming ?? [])]) {
        if (l.fromType !== 'REQUIREMENT' || l.toType !== 'REQUIREMENT') continue
        if (seen.has(l.id)) continue
        seen.add(l.id)
        edges.push({ from: l.fromId, to: l.toId, type: l.linkType })
      }
    }
    return { nodes, edges }
  }, [requirements, linkQueries.map((q) => q.dataUpdatedAt).join()])

  if (isLoading || linksLoading) return <p className="eyebrow">Reading requirements and their links…</p>
  if (requirements.length === 0) {
    return <Empty title="No test cases yet" desc="Draft or generate one from the Verification tab." />
  }

  return (
    <>
      {data && data.totalElements > requirements.length && (
        <p className="hint muted" style={{ marginBottom: 10 }}>
          Showing the first {requirements.length} of {data.totalElements} requirements with test cases — switch to
          List view and search to find one not shown here.
        </p>
      )}
      <DependencyDiagram nodes={nodes} edges={edges}
        onOpenNode={(id) => setInspecting(requirements.find((r) => r.requirementId === id) ?? null)} />
      {inspecting && (
        <Modal onClose={() => setInspecting(null)} title={inspecting.requirementKey}>
          <h3>{inspecting.requirementKey}</h3>
          <p className="hint muted">{inspecting.requirementTitle}</p>
          <RequirementTestCases requirementId={inspecting.requirementId} />
        </Modal>
      )}
    </>
  )
}

function RequirementRow({
  summary, expanded, onToggle,
}: { summary: RequirementTestCaseSummary; expanded: boolean; onToggle: () => void }) {
  const [showAdd, setShowAdd] = useState(false)
  const qc = useQueryClient()

  const onGenerated = () => {
    void qc.invalidateQueries({ queryKey: ['test-cases-by-requirement'] })
    void qc.invalidateQueries({ queryKey: ['test-cases-for-requirement', summary.requirementId] })
  }

  return (
    <div className="card" style={{ padding: 0, marginTop: 12, overflow: 'hidden' }}>
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', cursor: 'pointer' }}
        onClick={onToggle}
      >
        <span style={{ color: 'var(--tx-3)', display: 'flex' }}>
          {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </span>
        <span className="mono muted" style={{ fontSize: 10 }}>{summary.requirementKey}</span>
        <strong style={{ flex: 1, fontSize: 13.5 }}>{summary.requirementTitle}</strong>
        <span className="badge">{summary.totalCount} test case{summary.totalCount === 1 ? '' : 's'}</span>
        <button className="btn" style={{ fontSize: 11 }} onClick={(e) => { e.stopPropagation(); setShowAdd(true) }}>
          <Plus size={13} /> Add test case
        </button>
      </div>
      {expanded && (
        <div style={{ padding: '10px 14px 14px', borderTop: '1px solid var(--line)' }}>
          <DependencyHint requirementId={summary.requirementId} />
          <RequirementTestCases requirementId={summary.requirementId} />
        </div>
      )}
      {showAdd && (
        <TestCaseAuthoringPanel
          requirement={{ id: summary.requirementId, key: summary.requirementKey }}
          onClose={() => setShowAdd(false)} onGenerated={onGenerated} />
      )}
    </div>
  )
}

function CategoryGroup({
  title, desc, accent, items, requirementId,
}: { title: string; desc: string; accent: string; items: TestCase[]; requirementId: string }) {
  return (
    <div style={{ borderLeft: `3px solid ${accent}`, paddingLeft: 12, marginTop: 14 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 2 }}>
        <span className="eyebrow">{title}</span>
        <span className="mono muted" style={{ fontSize: 10 }}>{items.length}</span>
      </div>
      <p className="hint muted" style={{ marginTop: 0, marginBottom: 6 }}>{desc}</p>
      {items.length === 0 && <p className="hint muted">None.</p>}
      {items.map((tc) => <TestCaseRow key={tc.id} tc={tc} requirementId={requirementId} />)}
    </div>
  )
}

function RequirementTestCases({ requirementId }: { requirementId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['test-cases-for-requirement', requirementId],
    queryFn: () => api.testCasesForRequirement(requirementId),
  })

  if (isLoading) return <p className="eyebrow" style={{ marginTop: 8 }}>Loading…</p>
  if (!data) return null

  const individual = data.filter((tc) => tc.category === 'INDIVIDUAL')
  const dependency = data.filter((tc) => tc.category === 'DEPENDENCY')
  const other = data.filter((tc) => !tc.category)

  return (
    <div style={{ paddingTop: 10 }}>
      <CategoryGroup title="Individual" desc="Validates this requirement on its own"
        accent="var(--brand)" items={individual} requirementId={requirementId} />
      <CategoryGroup title="Dependency" desc="Validates it together with something it depends on"
        accent="var(--ok)" items={dependency} requirementId={requirementId} />
      {other.length > 0 && (
        <CategoryGroup title="Other" desc="No category recorded (drafted before VYB-0827, or manual)"
          accent="var(--line-2)" items={other} requirementId={requirementId} />
      )}
    </div>
  )
}

const CATEGORY_OPTIONS: { value: TestCaseSuggestionCategory | ''; label: string }[] = [
  { value: '', label: '— none —' },
  { value: 'INDIVIDUAL', label: 'Individual' },
  { value: 'DEPENDENCY', label: 'Dependency' },
]

function TestCaseRow({ tc, requirementId }: { tc: TestCase; requirementId: string }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(tc.title)
  const [description, setDescription] = useState(tc.description ?? '')
  const [category, setCategory] = useState<TestCaseSuggestionCategory | ''>(tc.category ?? '')

  const save = useMutation({
    mutationFn: () => api.updateTestCase(tc.id, title, description.trim() || undefined, category || undefined),
    onSuccess: () => {
      setEditing(false)
      void qc.invalidateQueries({ queryKey: ['test-cases-for-requirement', requirementId] })
      void qc.invalidateQueries({ queryKey: ['test-cases-by-requirement'] })
    },
  })

  if (editing) {
    return (
      <div className="card" style={{ marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <span className="eyebrow">Edit test case</span>
          <button className="btn" style={{ padding: 4 }} onClick={() => setEditing(false)} aria-label="Cancel">
            <X size={14} />
          </button>
        </div>
        <div className="field">
          <label className="label">Title</label>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field">
          <label className="label">Description / steps</label>
          <textarea className="textarea" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        {description.trim() && (
          <div className="muted" style={{ fontSize: 11, marginBottom: 12 }}>
            <DescriptionView text={description} />
          </div>
        )}
        <div className="field">
          <label className="label">Category</label>
          <select className="select" value={category} onChange={(e) => setCategory(e.target.value as TestCaseSuggestionCategory | '')}>
            {CATEGORY_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
        {save.isError && <p className="err-text">Could not save.</p>}
        <div className="actions">
          <button className="btn" onClick={() => {
            setEditing(false); setTitle(tc.title); setDescription(tc.description ?? ''); setCategory(tc.category ?? '')
          }}>
            Cancel
          </button>
          <button className="btn pri" disabled={!title.trim() || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="list-item" style={{ alignItems: 'flex-start' }}>
      <span className="mono muted" style={{ fontSize: 10 }}>{tc.key}</span>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600 }}>{tc.title}</div>
        {tc.description && (
          <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
            <DescriptionView text={tc.description} />
          </div>
        )}
      </div>
      <span className="badge">{tc.status}</span>
      <button className="btn" style={{ padding: 4 }} onClick={() => setEditing(true)} aria-label="Edit">
        <Edit2 size={13} />
      </button>
    </div>
  )
}
