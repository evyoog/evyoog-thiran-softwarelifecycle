import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useVirtualizer } from '@tanstack/react-virtual'
import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Plus, Edit3, Upload, Columns3, Download, Trash2 } from 'lucide-react'
import { api, ApiError, type BulkEditRequest, type Capability, type Requirement, type RequirementPriority, type RequirementStatus, type RequirementType } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { StatusBadge, PriorityBadge, CoveragePips } from '@/shared/ui/Badges'
import { BulkEditModal } from './BulkEditModal'
import { Modal } from '@/shared/ui/Modal'
import { UndoToast } from '@/shared/ui/UndoToast'
import { ScopeTree, type DetailTarget, type SavedView, type Scope } from './requirements/ScopeTree'
import { ScopeDetailPane } from './requirements/ScopeDetailPane'
import { DetailPane } from './requirements/DetailPane'
import { NeedsCriteria } from './requirements/NeedsCriteria'
import { SpecDocument } from './requirements/SpecDocument'
import { TraceGraph } from './requirements/TraceGraph'

// VYB-0802: REVIEWED is a real status now — leaving it out here would make requirements
// in the manual review gate unfilterable from the grid.
const STATUSES: RequirementStatus[] = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED', 'REJECTED']
const PRIORITIES: RequirementPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const TYPES: RequirementType[] = [
  'FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE', 'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE',
]
const PAGE_SIZES = [25, 50, 100, 250]

type ColKey = 'key' | 'title' | 'type' | 'status' | 'priority' | 'coverage' | 'revision' | 'createdAt'
type SortField = 'key' | 'title' | 'priority' | 'status' | 'createdAt'
type Density = 'comfortable' | 'compact' | 'dense'
type GroupBy = 'none' | 'status' | 'priority' | 'type'

const COLUMNS: { key: ColKey; label: string; sortable: boolean; defaultWidth: number }[] = [
  { key: 'key', label: 'ID', sortable: true, defaultWidth: 110 },
  { key: 'title', label: 'Requirement', sortable: true, defaultWidth: 320 },
  { key: 'type', label: 'Type', sortable: false, defaultWidth: 140 },
  { key: 'status', label: 'Status', sortable: true, defaultWidth: 120 },
  { key: 'priority', label: 'Pri', sortable: true, defaultWidth: 110 },
  { key: 'coverage', label: 'Coverage', sortable: false, defaultWidth: 100 },
  { key: 'revision', label: 'Rev', sortable: false, defaultWidth: 60 },
  { key: 'createdAt', label: 'Created', sortable: true, defaultWidth: 120 },
]
const DENSITY_HEIGHT: Record<Density, number> = { comfortable: 42, compact: 34, dense: 27 }
const STORAGE_KEY = 'vyoog-grid-requirements'

interface GridPrefs {
  order: ColKey[]
  hidden: ColKey[]
  widths: Partial<Record<ColKey, number>>
  density: Density
  pageSize: number
}

/** "19 Aug, 00:11" — enough to read an order by, without a full timestamp per row. */
function whenCreated(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—'
    : d.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

function loadPrefs(): GridPrefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      return {
        order: parsed.order ?? COLUMNS.map((c) => c.key),
        hidden: parsed.hidden ?? [],
        widths: parsed.widths ?? {},
        density: parsed.density ?? 'comfortable',
        pageSize: parsed.pageSize ?? PAGE_SIZES[0],
      }
    }
  } catch { /* fall through to defaults */ }
  return { order: COLUMNS.map((c) => c.key), hidden: [], widths: {}, density: 'comfortable', pageSize: PAGE_SIZES[0] }
}

/**
 * VYB-0170–0180: the full grid contract. Column drag-reorder/resize/hide, multi-column
 * sort (shift-click adds a column, matching the server's own multi-value `sort=`
 * param — see RequirementController's javadoc, which already documented the backend
 * supported this before any frontend used it), a real per-column filter row, grouping
 * (over the currently loaded page — see GroupedRows's own note on why, honestly, not
 * globally), density modes, inline cell edit for title/type/priority (not status —
 * that goes through the lifecycle transition's own validation, on purpose), and row
 * virtualization so a page of 250 rows costs the DOM about as much as a page of 25.
 *
 * The layout is the prototype's three-pane workspace: scope tree, grid, detail pane.
 * A row click now opens the detail pane rather than navigating away — the pane's own
 * link goes to the full page, which is still where every mutation other than the
 * grid's inline edits happens.
 */
export function Requirements() {
  const navigate = useNavigate()
  const qc = useQueryClient()
  // VYB-0821: seeded from the URL, same reasoning as `scope` below — a lane in My
  // Work's Pipeline linking here for "N more" DRAFT requirements must land on a
  // filtered list, not an unfiltered one that reads as "every requirement, somewhere
  // in here are the ones you wanted."
  const [status, setStatus] = useState<RequirementStatus | ''>(() => {
    const fromUrl = new URLSearchParams(window.location.search).get('status')
    return fromUrl && (STATUSES as string[]).includes(fromUrl) ? (fromUrl as RequirementStatus) : ''
  })
  const [priority, setPriority] = useState<RequirementPriority | ''>('')
  const [type, setType] = useState<RequirementType | ''>('')
  const [titleSearch, setTitleSearch] = useState('')
  const [page, setPage] = useState(0)
  // Newest first. Sorting by key was lexicographic, which put VY-1, VY-10, VY-11 above
  // VY-2 — neither insertion order nor numeric order, just the order strings compare in.
  const [sort, setSort] = useState<{ field: SortField; dir: 'asc' | 'desc' }[]>([{ field: 'createdAt', dir: 'desc' }])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [showBulkEdit, setShowBulkEdit] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleteReason, setDeleteReason] = useState('')
  const [undo, setUndo] = useState<{ batchId: string; message: string } | null>(null)
  const [skipReasons, setSkipReasons] = useState<{ reason: string; count: number }[]>([])
  const [groupBy, setGroupBy] = useState<GroupBy>('none')
  // The prototype's three ways of reading the same selection: as rows, as the
  // specification they add up to, and as the trace chains they sit on. All three render
  // the set the filters and scope currently select — they are views, not screens, so
  // narrowing the scope narrows all of them together.
  const [view, setView] = useState<'grid' | 'spec' | 'graph'>('grid')

  // Capability names for the Document view's section headings. Only fetchable once a
  // scope names an application — without one the document still renders, with its rows
  // under a heading that says the capability is unnamed rather than a blank one.
  const [showColumnMenu, setShowColumnMenu] = useState(false)
  const [editingCell, setEditingCell] = useState<{ rowId: string; col: ColKey } | null>(null)
  const [editDraft, setEditDraft] = useState('')

  // Three-pane state: what the tree has scoped to, and what the detail pane is showing.
  // Seeded from the URL so a capability card on the app detail screen can link straight
  // into its requirements. Without this the link opened an unfiltered list, which reads
  // as "this capability has every requirement in the platform".
  const [searchParams, setSearchParams] = useSearchParams()
  const [scope, setScope] = useState<Scope | null>(() => {
    const capabilityId = searchParams.get('capabilityId')
    if (!capabilityId) return null
    return {
      capabilityId,
      applicationId: searchParams.get('applicationId') ?? '',
      label: searchParams.get('label') ?? 'Selected capability',
    }
  })

  // The params have done their job once they are in state; leaving them in the address
  // bar would make Clear look like it had not worked after a reload.
  useEffect(() => {
    if (searchParams.has('capabilityId')) setSearchParams({}, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  // VYB-0833: a product or app picked in the sidebar — shows that product's/app's own
  // details in the main pane instead of the grid. Mutually exclusive with `scope`: only
  // a capability ever filters the grid, so picking either clears the other.
  const [detailTarget, setDetailTarget] = useState<DetailTarget | null>(null)
  const [savedView, setSavedView] = useState<SavedView | null>(null)
  const [detailRow, setDetailRow] = useState<Requirement | null>(null)

  const [prefs, setPrefs] = useState<GridPrefs>(loadPrefs)
  useEffect(() => { localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs)) }, [prefs])
  // A column added after someone saved their preferences is absent from the stored
  // order, and indexOf returns -1 for it — which would silently sort it to the front and
  // make a new column look like a deliberate reordering. Unknown columns keep their
  // declared position instead.
  const visibleCols = COLUMNS.filter((c) => !prefs.hidden.includes(c.key))
    .sort((a, b) => {
      const ia = prefs.order.indexOf(a.key), ib = prefs.order.indexOf(b.key)
      return (ia === -1 ? COLUMNS.findIndex((c) => c.key === a.key) + prefs.order.length : ia)
           - (ib === -1 ? COLUMNS.findIndex((c) => c.key === b.key) + prefs.order.length : ib)
    })
  const colCount = visibleCols.length + 1 // +1 for the pinned checkbox column

  const [dragCol, setDragCol] = useState<ColKey | null>(null)
  const [resizing, setResizing] = useState<{ col: ColKey; startX: number; startWidth: number } | null>(null)

  // VYB-0767: roving-tabindex grid keyboard nav, adapted for a virtualized row list —
  // the focused row is scrolled into view via the virtualizer, not just `.focus()`.
  const [focusedCell, setFocusedCell] = useState({ row: 0, col: 1 })
  const cellRefs = useRef<(HTMLTableCellElement | null)[][]>([])
  const scrollRef = useRef<HTMLDivElement>(null)

  const sortParam = sort.map((s) => `${s.field},${s.dir}`)

  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', scope?.applicationId],
    queryFn: () => api.capabilities(scope!.applicationId),
    enabled: !!scope?.applicationId,
  })
  // VYB-0833: the selected capability's own record — name/code/description — for the
  // highlighted empty state below when it has no requirements yet. Reuses the query
  // above rather than a second fetch; a capability id alone doesn't say which of these
  // rows it is, so this is a plain find, not a new request.
  const selectedCapability: Capability | undefined = capabilities?.find((c) => c.id === scope?.capabilityId)

  const { data, isLoading, isError } = useQuery({
    queryKey: ['requirements', status, priority, type, titleSearch, page, prefs.pageSize, sort, scope?.capabilityId, 'with-criteria'],
    queryFn: () => api.requirements({
      status: status || undefined, priority: priority || undefined, type: type || undefined,
      title: titleSearch || undefined, capabilityId: scope?.capabilityId,
      // VYB-0666: a requirement with no acceptance criteria is not readable as a
      // requirement yet — NeedsCriteria below surfaces them instead of the grid burying
      // them among rows that are ready.
      hasCriteria: true,
      page, size: prefs.pageSize, sort: sortParam,
    }),
  })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['requirements'] })

  // VYB-0814: this mutation had no error handling at all — a 409 (the rate limit,
  // VYB-0782; a permission refusal; a guard the row failed) failed silently, the
  // modal stayed open with no explanation, and clicking Apply again was the only
  // thing left to try — which, inside the rate limiter's 5-second window, just
  // produced another 409, making one real failure look like a permanent one. The
  // actual reason (`bulkEdit.error`, read below) is now shown where the action was
  // taken, the same way every other mutation on this screen already surfaces its own.
  const bulkEdit = useMutation({
    mutationFn: (body: BulkEditRequest) => api.bulkEdit(body),
    onSuccess: (result) => {
      setShowBulkEdit(false)
      setSelected(new Set())
      const applied = result.outcomes.filter((o) => o.applied)
      const skipped = result.outcomes.filter((o) => !o.applied)
      // The server says why each row was skipped; showing only a count was the reason a
      // fully-skipped bulk edit looked like it had silently done nothing. Reasons are
      // grouped because a whole selection usually fails for one shared reason.
      const byReason = new Map<string, number>()
      for (const o of skipped) {
        const why = o.reason ?? 'no reason given'
        byReason.set(why, (byReason.get(why) ?? 0) + 1)
      }
      setSkipReasons([...byReason.entries()].map(([reason, count]) => ({ reason, count })))
      setUndo({
        batchId: result.batchId,
        message: `Updated ${applied.length} requirement${applied.length === 1 ? '' : 's'}` +
          (skipped.length ? ` — ${skipped.length} skipped` : ''),
      })
      invalidate()
    },
  })

  const inlineEdit = useMutation({
    mutationFn: ({ row, col, value }: { row: Requirement; col: ColKey; value: string }) =>
      api.updateRequirement(row.id, {
        revision: row.revision, title: col === 'title' ? value : row.title, statement: row.statement,
        type: (col === 'type' ? value : row.type) as RequirementType,
        priority: (col === 'priority' ? value : row.priority) as RequirementPriority,
        capabilityId: row.capabilityId,
      }),
    onSuccess: () => { setEditingCell(null); invalidate() },
  })

  const toggleSort = (field: SortField, additive: boolean) => {
    setSort((prev) => {
      const existing = prev.find((s) => s.field === field)
      if (!additive) {
        return existing && prev.length === 1
          ? [{ field, dir: existing.dir === 'asc' ? 'desc' : 'asc' }]
          : [{ field, dir: 'asc' }]
      }
      if (!existing) return [...prev, { field, dir: 'asc' }]
      if (existing.dir === 'asc') return prev.map((s) => s.field === field ? { ...s, dir: 'desc' } : s)
      return prev.filter((s) => s.field !== field) // third click on a shift-added column drops it
    })
  }

  const toggleSelectAll = () => {
    if (!data) return
    setSelected((prev) =>
      prev.size === data.content.length ? new Set() : new Set(data.content.map((r) => r.id)));
  }

  const sortIndicator = (field: SortField) => {
    const idx = sort.findIndex((s) => s.field === field)
    if (idx === -1) return ''
    const arrow = sort[idx].dir === 'asc' ? '▲' : '▼'
    return sort.length > 1 ? ` ${arrow}${idx + 1}` : ` ${arrow}`
  }

  useEffect(() => { setFocusedCell({ row: 0, col: 1 }) }, [data])

  const rowHeight = DENSITY_HEIGHT[prefs.density]
  const rows = data?.content ?? []
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    overscan: 10,
  })

  useEffect(() => {
    cellRefs.current[focusedCell.row]?.[focusedCell.col]?.focus()
    virtualizer.scrollToIndex(focusedCell.row, { align: 'auto' })
  }, [focusedCell]) // eslint-disable-line react-hooks/exhaustive-deps

  const onGridKeyDown = (e: React.KeyboardEvent) => {
    if (editingCell) return // let the inline editor own its own keys
    if (rows.length === 0) return
    const maxRow = rows.length - 1
    if (e.key === 'ArrowDown') { e.preventDefault(); setFocusedCell((c) => ({ ...c, row: Math.min(c.row + 1, maxRow) })) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setFocusedCell((c) => ({ ...c, row: Math.max(c.row - 1, 0) })) }
    else if (e.key === 'ArrowRight') { e.preventDefault(); setFocusedCell((c) => ({ ...c, col: Math.min(c.col + 1, colCount - 1) })) }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); setFocusedCell((c) => ({ ...c, col: Math.max(c.col - 1, 0) })) }
    else if (e.key === 'Home') { e.preventDefault(); setFocusedCell((c) => ({ ...c, row: 0 })) }
    else if (e.key === 'End') { e.preventDefault(); setFocusedCell((c) => ({ ...c, row: maxRow })) }
    else if (e.key === 'Enter') {
      const r = rows[focusedCell.row]
      const col = visibleCols[focusedCell.col - 1]
      if (focusedCell.col === 0) {
        setSelected((prev) => { const next = new Set(prev); next.has(r.id) ? next.delete(r.id) : next.add(r.id); return next })
      } else if (col && (col.key === 'title' || col.key === 'type' || col.key === 'priority')) {
        startEdit(r, col.key)
      } else if (col && col.key === 'key') {
        setDetailRow(r)
      }
    }
  }

  const startEdit = (row: Requirement, col: ColKey) => {
    setEditingCell({ rowId: row.id, col })
    setEditDraft(col === 'title' ? row.title : col === 'type' ? row.type : col === 'priority' ? row.priority : '')
  }
  const commitEdit = (row: Requirement, col: ColKey) => {
    inlineEdit.mutate({ row, col, value: editDraft })
  }

  const cellRef = (row: number, col: number) => (el: HTMLTableCellElement | null) => {
    if (!cellRefs.current[row]) cellRefs.current[row] = []
    cellRefs.current[row][col] = el
  }
  const cellProps = (row: number, col: number) => ({
    ref: cellRef(row, col),
    tabIndex: focusedCell.row === row && focusedCell.col === col ? 0 : -1,
    role: 'gridcell' as const,
    onFocus: () => setFocusedCell({ row, col }),
  })
  const focusOutline = (row: number, col: number): React.CSSProperties | undefined =>
    focusedCell.row === row && focusedCell.col === col ? { outline: '2px solid var(--brand)', outlineOffset: -2 } : undefined

  // ── Column resize ──────────────────────────────────────────────────────────
  useEffect(() => {
    if (!resizing) return
    const onMove = (e: MouseEvent) => {
      const delta = e.clientX - resizing.startX
      setPrefs((p) => ({ ...p, widths: { ...p.widths, [resizing.col]: Math.max(50, resizing.startWidth + delta) } }))
    }
    const onUp = () => setResizing(null)
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    return () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
  }, [resizing])

  const widthOf = (col: ColKey) => prefs.widths[col] ?? COLUMNS.find((c) => c.key === col)!.defaultWidth

  // ── Column reorder (native drag-and-drop) ───────────────────────────────────
  const onDropCol = (target: ColKey) => {
    if (!dragCol || dragCol === target) return
    setPrefs((p) => {
      const order = visibleCols.map((c) => c.key)
      const from = order.indexOf(dragCol)
      const to = order.indexOf(target)
      order.splice(from, 1); order.splice(to, 0, dragCol)
      // Preserve hidden columns' relative position at the end, unaffected.
      return { ...p, order: [...order, ...p.hidden.filter((h) => !order.includes(h))] }
    })
    setDragCol(null)
  }

  const totalHeight = virtualizer.getTotalSize()
  const virtualRows = virtualizer.getVirtualItems()
  const paddingTop = virtualRows.length > 0 ? virtualRows[0].start : 0
  const paddingBottom = virtualRows.length > 0 ? totalHeight - virtualRows[virtualRows.length - 1].end : 0

  const grouped = useMemo(() => groupRows(rows, groupBy), [rows, groupBy])

  const applySavedView = (v: SavedView) => {
    setSavedView(v)
    setStatus(v.status ?? '')
    setPriority(v.priority ?? '')
    setType(v.type ?? '')
    setTitleSearch(v.titleContains ?? '')
    // A view that captured a capability restores that scope too, so it reproduces the
    // rows it was saved from rather than the same filters against whatever is scoped now.
    if (v.capabilityId && v.capabilityId !== scope?.capabilityId) {
      setScope({ capabilityId: v.capabilityId, applicationId: '', label: v.label })
    }
    setPage(0)
  }

  // Every chip is a filter actually in effect on the query above — clearing one clears
  // the state it names, nothing more.
  // Soft delete: the rows and their history survive and every read path hides them. The
  // reason travels to the audit trail, which is the only place left that can answer "why
  // did VY-1042 vanish" once the row is out of every view.
  const remove = useMutation({
    mutationFn: async () => {
      for (const rid of selected) await api.deleteRequirement(rid, deleteReason.trim() || undefined)
    },
    onSuccess: () => {
      setSelected(new Set())
      setConfirmingDelete(false)
      setDeleteReason('')
      setDetailRow(null)
      void qc.invalidateQueries({ queryKey: ['requirements'] })
    },
  })

  const chips: { label: string; clear: () => void }[] = [
    ...(scope ? [{ label: scope.label, clear: () => { setScope(null); setPage(0) } }] : []),
    ...(savedView ? [{ label: `view: ${savedView.label}`, clear: () => { setSavedView(null); setStatus(''); setPriority(''); setPage(0) } }] : []),
    ...(status && !savedView ? [{ label: `status: ${status}`, clear: () => { setStatus(''); setPage(0) } }] : []),
    ...(priority && !savedView ? [{ label: `priority: ${priority}`, clear: () => { setPriority(''); setPage(0) } }] : []),
    ...(type ? [{ label: `type: ${type}`, clear: () => { setType(''); setPage(0) } }] : []),
    ...(titleSearch ? [{ label: `title contains "${titleSearch}"`, clear: () => { setTitleSearch(''); setPage(0) } }] : []),
  ]

  const gapsOnPage = rows.filter((r) => !r.hasTest).length

  return (
    <div className="workspace">
      <div className="vhead">
        <div style={{ minWidth: 0 }}>
          <div className="crumb">
            <button onClick={() => { setScope(null); setDetailTarget(null); setPage(0) }}>Portfolio</button>
            <span className="sep">▸</span>
            <span className="cur">{detailTarget ? detailTarget.label : scope ? scope.label : 'All requirements'}</span>
          </div>
          <h1 className="vhead-t">Requirements</h1>
        </div>
        <div className="sp" />
        <div style={{ display: 'flex', gap: 8, flexShrink: 0, alignItems: 'center' }}>
          {/* The selection's own composition, so the route into Delivery is legible from
              here: only approved requirements are briefed, and this says how many of the
              ticked rows already are. */}
          {selected.size > 0 && (() => {
            const picked = rows.filter((r) => selected.has(r.id))
            const ready = picked.filter((r) => r.status === 'APPROVED').length
            return (
              <span className="hint muted" style={{ fontSize: 11, maxWidth: 210, lineHeight: 1.4 }}>
                {ready} of {picked.length} selected {ready === 1 ? 'is' : 'are'} approved and would reach Delivery.
              </span>
            )
          })()}
          {selected.size > 0 && (
            <button className="btn" onClick={() => setShowBulkEdit(true)}>
              <Edit3 /> Bulk edit ({selected.size})
            </button>
          )}
          {selected.size > 0 && (
            <button className="btn danger" disabled={remove.isPending} onClick={() => setConfirmingDelete(true)}>
              <Trash2 /> Delete ({selected.size})
            </button>
          )}
          <button className="btn" onClick={() => navigate('/requirements/documents')}><Download /> ReqIF / Word</button>
          <button className="btn" onClick={() => navigate('/requirements/import')}><Upload /> Import queue</button>
          <button className="btn pri" onClick={() => navigate('/requirements/new')}><Plus /> New requirement</button>
        </div>
      </div>

      <div className={`pane3${detailRow ? '' : ' no-detail'}`}>
        <ScopeTree
          scope={scope}
          onPickScope={(s) => { setScope(s); setDetailTarget(null); setPage(0) }}
          detail={detailTarget}
          onPickDetail={(d) => { setDetailTarget(d); setScope(null); setPage(0) }}
          activeView={savedView}
          onPickView={applySavedView}
        />

        <div className="gridpane">
        {detailTarget ? (
          <ScopeDetailPane
            target={detailTarget}
            onPickDetail={setDetailTarget}
            onPickScope={(s) => { setScope(s); setDetailTarget(null); setPage(0) }}
            onClear={() => setDetailTarget(null)}
            onOpenGaps={() => navigate('/requirements/coverage')}
          />
        ) : (<>
          <div className="gridbar">
            <div className="seg" role="group" aria-label="Requirement view">
              {([['grid', 'Grid'], ['spec', 'Document'], ['graph', 'Graph']] as const).map(([id, label]) => (
                <button
                  key={id} className={view === id ? 'on' : ''} aria-pressed={view === id}
                  onClick={() => setView(id)}
                >
                  {label}
                </button>
              ))}
            </div>

            {chips.map((c) => (
              <button key={c.label} className="chip" onClick={c.clear} title="Remove this filter">
                {c.label} <span className="x">×</span>
              </button>
            ))}

            <div className="sp" />

            <select className="select" style={{ minWidth: 0, fontSize: 11, padding: '4px 6px' }} value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)} aria-label="Group rows by">
              <option value="none">No grouping</option>
              <option value="status">Group by status</option>
              <option value="priority">Group by priority</option>
              <option value="type">Group by type</option>
            </select>
            <select className="select" style={{ minWidth: 0, fontSize: 11, padding: '4px 6px' }} value={prefs.density} onChange={(e) => setPrefs((p) => ({ ...p, density: e.target.value as Density }))} aria-label="Row density">
              <option value="comfortable">Comfortable</option>
              <option value="compact">Compact</option>
              <option value="dense">Dense</option>
            </select>
            <div style={{ position: 'relative' }}>
              <button className="btn" style={{ padding: '4px 8px', fontSize: 11 }} onClick={() => setShowColumnMenu((v) => !v)}><Columns3 /> Columns</button>
              {showColumnMenu && (
                <div className="card" style={{ position: 'absolute', top: '100%', right: 0, zIndex: 20, marginTop: 4, padding: 8, minWidth: 160 }}>
                  {COLUMNS.map((c) => (
                    <label key={c.key} style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12, padding: '3px 0' }}>
                      <input
                        type="checkbox" checked={!prefs.hidden.includes(c.key)}
                        onChange={(e) => setPrefs((p) => ({
                          ...p, hidden: e.target.checked ? p.hidden.filter((h) => h !== c.key) : [...p.hidden, c.key],
                        }))}
                      />
                      {c.label}
                    </label>
                  ))}
                </div>
              )}
            </div>

            {data && (
              <span className="mono" style={{ fontSize: 10.5, color: 'var(--tx-3)' }}>
                {data.totalElements} items · {gapsOnPage} on this page with no passing test
              </span>
            )}
          </div>

          <NeedsCriteria capabilityId={scope?.capabilityId} />

          <div className="gridscroll" ref={scrollRef}>
            {isLoading && <p className="eyebrow" style={{ padding: 16 }}>Loading…</p>}
            {isError && <p className="err-text" style={{ padding: 16 }}>Could not load requirements.</p>}

            {data && data.content.length > 0 && view === 'spec' && (
              <SpecDocument
                requirements={data.content}
                capabilities={capabilities ?? []}
                scopeLabel={scope ? scope.label : 'All requirements'}
                onOpen={(id) => { const r = data.content.find((x) => x.id === id); if (r) setDetailRow(r) }}
              />
            )}
            {data && data.content.length > 0 && view === 'graph' && (
              <TraceGraph
                requirements={data.content}
                onOpen={(id) => { const r = data.content.find((x) => x.id === id); if (r) setDetailRow(r) }}
                applicationId={scope?.applicationId}
              />
            )}

            {/* VYB-0812: grid view keeps its own empty row inside the table (below) so the
                filter row above it never disappears — losing the filters is exactly what
                would strand someone once a filter narrows the page to zero rows. The
                other views have no filter row to preserve, so they still swap out whole. */}
            {data && data.content.length === 0 && view !== 'grid' && (
              <div style={{ padding: 20 }}>
                {scope
                  ? <CapabilityEmptyState scope={scope} capability={selectedCapability} />
                  : (
                    <Empty
                      title="No requirements here"
                      desc="Create the first one — it's written through the API with an immutable revision from the start."
                    />
                  )}
              </div>
            )}

            {data && view === 'grid' && (
              <table
                className="rt" onKeyDown={onGridKeyDown}
                role="grid" aria-rowcount={data.totalElements} aria-colcount={colCount}
                style={{ tableLayout: 'fixed' }}
              >
                <thead>
                  <tr role="row">
                    <th style={{ width: 28 }}>
                      <input type="checkbox" checked={selected.size === data.content.length} onChange={toggleSelectAll} aria-label="Select all rows" />
                    </th>
                    {visibleCols.map((c) => (
                      <th
                        key={c.key}
                        role="columnheader"
                        aria-sort={
                          sort.find((s) => s.field === c.key)
                            ? (sort.find((s) => s.field === c.key)!.dir === 'asc' ? 'ascending' : 'descending')
                            : 'none'
                        }
                        draggable
                        onDragStart={() => setDragCol(c.key)}
                        onDragOver={(e) => e.preventDefault()}
                        onDrop={() => onDropCol(c.key)}
                        onClick={(e) => c.sortable && toggleSort(c.key as SortField, e.shiftKey)}
                        style={{ cursor: c.sortable ? 'pointer' : 'default', width: widthOf(c.key), position: 'relative', userSelect: 'none' }}
                        title={c.sortable ? 'Click to sort, shift-click to add a secondary sort column' : undefined}
                      >
                        {c.label}{c.sortable ? sortIndicator(c.key as SortField) : ''}
                        <span
                          onMouseDown={(e) => { e.stopPropagation(); setResizing({ col: c.key, startX: e.clientX, startWidth: widthOf(c.key) }) }}
                          style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 6, cursor: 'col-resize' }}
                        />
                      </th>
                    ))}
                  </tr>
                  {/* VYB-0170–0180: the per-column filter row — text-contains for title, exact match for the rest. */}
                  <tr role="row" className="filters">
                    <th />
                    {visibleCols.map((c) => (
                      <th key={c.key}>
                        {c.key === 'title' && (
                          <input className="input" style={{ fontSize: 11, padding: '3px 6px', width: '100%' }} placeholder="Contains…"
                            value={titleSearch} onChange={(e) => { setTitleSearch(e.target.value); setPage(0) }} />
                        )}
                        {c.key === 'status' && (
                          <select className="select" style={{ fontSize: 11, padding: '3px 4px', minWidth: 0, width: '100%' }} value={status}
                            onChange={(e) => { setStatus(e.target.value as RequirementStatus | ''); setSavedView(null); setPage(0) }}>
                            <option value="">All</option>
                            {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                          </select>
                        )}
                        {c.key === 'priority' && (
                          <select className="select" style={{ fontSize: 11, padding: '3px 4px', minWidth: 0, width: '100%' }} value={priority}
                            onChange={(e) => { setPriority(e.target.value as RequirementPriority | ''); setSavedView(null); setPage(0) }}>
                            <option value="">All</option>
                            {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                          </select>
                        )}
                        {c.key === 'type' && (
                          <select className="select" style={{ fontSize: 11, padding: '3px 4px', minWidth: 0, width: '100%' }} value={type}
                            onChange={(e) => { setType(e.target.value as RequirementType | ''); setPage(0) }}>
                            <option value="">All</option>
                            {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                          </select>
                        )}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.content.length === 0 ? (
                    <tr>
                      <td colSpan={colCount} style={{ padding: 20 }}>
                        {scope
                          ? <CapabilityEmptyState scope={scope} capability={selectedCapability} />
                          : (
                            <Empty
                              title="No requirements here"
                              desc="Create the first one — it's written through the API with an immutable revision from the start."
                            />
                          )}
                      </td>
                    </tr>
                  ) : groupBy === 'none' ? (
                    <>
                      {paddingTop > 0 && <tr style={{ height: paddingTop }}><td colSpan={colCount} /></tr>}
                      {virtualRows.map((vr) => {
                        const r = rows[vr.index]
                        return (
                          <GridRow
                            key={r.id} r={r} rowIdx={vr.index} height={rowHeight} visibleCols={visibleCols}
                            selected={selected.has(r.id)} active={detailRow?.id === r.id}
                            onToggleSelect={() => setSelected((prev) => {
                              const next = new Set(prev); next.has(r.id) ? next.delete(r.id) : next.add(r.id); return next
                            })}
                            onSelect={setDetailRow} cellProps={cellProps} focusOutline={focusOutline}
                            editingCell={editingCell} editDraft={editDraft} setEditDraft={setEditDraft}
                            startEdit={startEdit} commitEdit={commitEdit} cancelEdit={() => setEditingCell(null)}
                          />
                        )
                      })}
                      {paddingBottom > 0 && <tr style={{ height: paddingBottom }}><td colSpan={colCount} /></tr>}
                    </>
                  ) : (
                    <GroupedRows
                      grouped={grouped} visibleCols={visibleCols} height={rowHeight} selected={selected}
                      activeId={detailRow?.id}
                      onToggleSelect={(id) => setSelected((prev) => { const next = new Set(prev); next.has(id) ? next.delete(id) : next.add(id); return next })}
                      onSelect={setDetailRow}
                    />
                  )}
                </tbody>
              </table>
            )}
          </div>

          {data && data.content.length > 0 && (
            <div className="gridfoot">
              <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }} disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
              <span className="eyebrow">
                Page {data.number + 1} of {Math.max(data.totalPages, 1)}
                {selected.size > 0 && ` — ${selected.size} selected`}
              </span>
              <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }} disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
              <select className="select" style={{ minWidth: 0, fontSize: 11, padding: '4px 6px' }} value={prefs.pageSize} onChange={(e) => { setPrefs((p) => ({ ...p, pageSize: Number(e.target.value) })); setPage(0) }} aria-label="Rows per page">
                {PAGE_SIZES.map((n) => <option key={n} value={n}>{n} rows/page</option>)}
              </select>
              {groupBy !== 'none' && (
                <span className="hint muted">Grouping applies to this page's {rows.length} loaded rows, not the full {data.totalElements}.</span>
              )}
            </div>
          )}
          </>
        )}
        </div>

        {detailRow && <DetailPane req={detailRow} onClose={() => setDetailRow(null)} />}
      </div>

      {confirmingDelete && (
        <Modal onClose={() => setConfirmingDelete(false)} title="Delete requirements">
          <h3>Delete {selected.size} requirement{selected.size === 1 ? '' : 's'}?</h3>
          <p className="muted" style={{ fontSize: 12.5 }}>
            They disappear from every view in the product. Their revisions, trace links and any
            brief that already quoted them are kept, so the history still resolves — but nothing
            here will show them again.
          </p>
          {(() => {
            // Approving is a signature. Deleting past one should at least be deliberate.
            const agreed = rows.filter((r) => selected.has(r.id) && r.status === 'APPROVED')
            return agreed.length > 0 ? (
              <p className="err-text" style={{ marginTop: 8 }}>
                {agreed.length} of these {agreed.length === 1 ? 'is' : 'are'} already approved
                ({agreed.slice(0, 4).map((r) => r.key).join(', ')}{agreed.length > 4 ? '…' : ''}) — somebody
                signed those off, and deleting is not the same as rejecting them.
              </p>
            ) : null
          })()}
          <div className="field" style={{ marginTop: 10 }}>
            <label className="label">Reason (recorded on the audit trail)</label>
            <input
              className="input" value={deleteReason} autoFocus
              placeholder="e.g. duplicate of VY-1039"
              onChange={(e) => setDeleteReason(e.target.value)}
            />
          </div>
          {remove.isError && <p className="err-text">Could not delete — nothing was changed.</p>}
          <div className="actions">
            <button className="btn" onClick={() => setConfirmingDelete(false)}>Cancel</button>
            <button className="btn danger" disabled={remove.isPending} onClick={() => remove.mutate()}>
              {remove.isPending ? 'Deleting…' : `Delete ${selected.size}`}
            </button>
          </div>
        </Modal>
      )}

      {showBulkEdit && (
        <BulkEditModal
          rows={rows.filter((r) => selected.has(r.id))}
          initialApplicationId={scope?.applicationId}
          onCancel={() => setShowBulkEdit(false)}
          onApply={(body) => bulkEdit.mutate(body)}
          pending={bulkEdit.isPending}
          error={bulkEdit.isError
            ? (bulkEdit.error instanceof ApiError ? (bulkEdit.error.detail ?? bulkEdit.error.title) : 'Could not apply — nothing was changed.')
            : undefined}
        />
      )}

      {/* Why the rows were skipped, not just how many. Grouped, because a selection
          almost always fails for one shared reason — an illegal move from a status they
          all share, or an override the batch needed and did not carry. */}
      {skipReasons.length > 0 && (
        <div className="card" style={{ position: 'fixed', bottom: 78, right: 20, zIndex: 60, maxWidth: 420, borderColor: 'var(--high-bd)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
            <strong style={{ fontSize: 12.5 }}>Some rows were skipped</strong>
            <div className="sp" />
            <button className="btn" style={{ padding: '2px 7px', fontSize: 11 }} onClick={() => setSkipReasons([])}>Dismiss</button>
          </div>
          {skipReasons.map((s) => (
            <p key={s.reason} style={{ fontSize: 11.5, color: 'var(--high)', margin: '0 0 4px', lineHeight: 1.5 }}>
              <span className="mono">{s.count}×</span> {s.reason}
            </p>
          ))}
        </div>
      )}

      {undo && (
        <UndoToast message={undo.message} onExpire={() => setUndo(null)} onUndo={async () => { await api.undoBulkEdit(undo.batchId); invalidate() }} />
      )}
    </div>
  )
}

/**
 * VYB-0833: a capability with requirements just shows them (unchanged); one without any
 * shows this instead of the generic empty state — its own name/code/description, so the
 * capability itself is never a dead end, plus a highlighted line naming the gap
 * explicitly rather than a caption that reads the same whether ten requirements got
 * filtered out or the capability has never had one.
 */
function CapabilityEmptyState({ scope, capability }: { scope: Scope; capability?: Capability }) {
  return (
    <div style={{ maxWidth: 480, margin: '0 auto', textAlign: 'center' }}>
      <div className="eyebrow">Capability</div>
      <h2 className="sdp-t" style={{ fontSize: 17 }}>
        {capability?.name ?? scope.label}
        {capability?.code && <span className="mono muted" style={{ fontSize: 10.5, marginLeft: 8 }}>{capability.code}</span>}
      </h2>
      <p className="sdp-desc">
        {capability?.description?.trim() || 'No description recorded for this capability.'}
      </p>
      <div className="bg-warn warn" style={{ display: 'inline-block', textAlign: 'left', marginTop: 8 }}>
        No requirements found for this capability yet — or every row is filtered out by the filters above.
      </div>
    </div>
  )
}

function groupRows(rows: Requirement[], groupBy: GroupBy): { label: string; rows: Requirement[] }[] {
  if (groupBy === 'none') return [{ label: '', rows }]
  const buckets = new Map<string, Requirement[]>()
  for (const r of rows) {
    const key = groupBy === 'status' ? r.status : groupBy === 'priority' ? r.priority : r.type
    buckets.set(key, [...(buckets.get(key) ?? []), r])
  }
  return [...buckets.entries()].map(([label, rows]) => ({ label, rows }))
}

/** One real, focusable, keyboard-navigable row. Kept as its own component so the virtualized and grouped render paths can share it. */
function GridRow({ r, rowIdx, height, visibleCols, selected, active, onToggleSelect, onSelect, cellProps, focusOutline, editingCell, editDraft, setEditDraft, startEdit, commitEdit, cancelEdit }: {
  r: Requirement
  rowIdx: number
  height: number
  visibleCols: typeof COLUMNS
  selected: boolean
  active: boolean
  onToggleSelect: () => void
  onSelect: (r: Requirement) => void
  cellProps: (row: number, col: number) => Record<string, unknown>
  focusOutline: (row: number, col: number) => React.CSSProperties | undefined
  editingCell: { rowId: string; col: ColKey } | null
  editDraft: string
  setEditDraft: (v: string) => void
  startEdit: (row: Requirement, col: ColKey) => void
  commitEdit: (row: Requirement, col: ColKey) => void
  cancelEdit: () => void
}) {
  const isEditing = (col: ColKey) => editingCell?.rowId === r.id && editingCell.col === col
  const editableKeyDown = (col: ColKey) => (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') { e.preventDefault(); commitEdit(r, col) }
    else if (e.key === 'Escape') { e.preventDefault(); cancelEdit() }
    e.stopPropagation()
  }

  return (
    <tr role="row" aria-selected={selected} className={active ? 'on' : undefined} style={{ height }}>
      <td {...cellProps(rowIdx, 0)} style={focusOutline(rowIdx, 0)} onClick={(e) => e.stopPropagation()}>
        <input type="checkbox" checked={selected} onChange={onToggleSelect} aria-label={`Select ${r.key}`} />
      </td>
      {visibleCols.map((c, colIdx) => {
        const gridCol = colIdx + 1
        if (c.key === 'key') {
          return (
            <td key={c.key} {...cellProps(rowIdx, gridCol)} className="c-id" onClick={() => onSelect(r)}
              style={{ cursor: 'pointer', ...focusOutline(rowIdx, gridCol) }}>
              {r.key}
            </td>
          )
        }
        if (c.key === 'title') {
          return (
            <td key={c.key} {...cellProps(rowIdx, gridCol)}
              onClick={() => !isEditing('title') && onSelect(r)}
              onDoubleClick={(e) => { e.stopPropagation(); startEdit(r, 'title') }}
              style={{ cursor: 'pointer', ...focusOutline(rowIdx, gridCol) }} title="Double-click to edit">
              {isEditing('title') ? (
                <input className="input" style={{ fontSize: 12 }} autoFocus value={editDraft}
                  onChange={(e) => setEditDraft(e.target.value)} onClick={(e) => e.stopPropagation()}
                  onKeyDown={editableKeyDown('title')} onBlur={() => commitEdit(r, 'title')} />
              ) : r.title}
            </td>
          )
        }
        if (c.key === 'type') {
          return (
            <td key={c.key} {...cellProps(rowIdx, gridCol)} className="muted"
              onDoubleClick={() => startEdit(r, 'type')} style={focusOutline(rowIdx, gridCol)} title="Double-click to edit">
              {isEditing('type') ? (
                <select className="select" style={{ fontSize: 12 }} autoFocus value={editDraft}
                  onChange={(e) => setEditDraft(e.target.value)} onClick={(e) => e.stopPropagation()}
                  onKeyDown={editableKeyDown('type')} onBlur={() => commitEdit(r, 'type')}>
                  {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              ) : r.type}
            </td>
          )
        }
        if (c.key === 'status') return <td key={c.key} {...cellProps(rowIdx, gridCol)} style={focusOutline(rowIdx, gridCol)}><StatusBadge status={r.status} /></td>
        if (c.key === 'priority') {
          return (
            <td key={c.key} {...cellProps(rowIdx, gridCol)} onDoubleClick={() => startEdit(r, 'priority')}
              style={focusOutline(rowIdx, gridCol)} title="Double-click to edit">
              {isEditing('priority') ? (
                <select className="select" style={{ fontSize: 12 }} autoFocus value={editDraft}
                  onChange={(e) => setEditDraft(e.target.value)} onClick={(e) => e.stopPropagation()}
                  onKeyDown={editableKeyDown('priority')} onBlur={() => commitEdit(r, 'priority')}>
                  {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                </select>
              ) : <PriorityBadge priority={r.priority} />}
            </td>
          )
        }
        if (c.key === 'coverage') return <td key={c.key} {...cellProps(rowIdx, gridCol)} style={focusOutline(rowIdx, gridCol)}><CoveragePips req={r} /></td>
        if (c.key === 'createdAt') {
          return (
            <td key={c.key} {...cellProps(rowIdx, gridCol)} className="muted" style={{ fontSize: 11.5, ...focusOutline(rowIdx, gridCol) }}>
              {whenCreated(r.createdAt)}
            </td>
          )
        }
        return <td key={c.key} {...cellProps(rowIdx, gridCol)} className="mono muted" style={focusOutline(rowIdx, gridCol)}>{r.revision}</td>
      })}
    </tr>
  )
}

/**
 * VYB-0170–0180 (grouping): honest about its scope — this groups the rows already
 * loaded for the current page, not a second server-side aggregate query across the
 * full result set. A "group by status" over page 3 of 40 shows that page's rows
 * bucketed by status, not a global count. Real, and clearly scoped, not a global
 * rollup pretending otherwise.
 */
function GroupedRows({ grouped, visibleCols, height, selected, activeId, onToggleSelect, onSelect }: {
  grouped: { label: string; rows: Requirement[] }[]
  visibleCols: typeof COLUMNS
  height: number
  selected: Set<string>
  activeId?: string
  onToggleSelect: (id: string) => void
  onSelect: (r: Requirement) => void
}) {
  const colCount = visibleCols.length + 1
  return (
    <>
      {grouped.map((group) => (
        <Fragment key={`group-${group.label}`}>
          <tr className="grp">
            <td colSpan={colCount}>{group.label} ({group.rows.length})</td>
          </tr>
          {group.rows.map((r, i) => (
            <GridRow
              key={r.id} r={r} rowIdx={i} height={height} visibleCols={visibleCols}
              selected={selected.has(r.id)} active={activeId === r.id}
              onToggleSelect={() => onToggleSelect(r.id)} onSelect={onSelect}
              cellProps={() => ({ tabIndex: -1, role: 'gridcell' as const })}
              focusOutline={() => undefined}
              editingCell={null} editDraft="" setEditDraft={() => {}}
              startEdit={() => {}} commitEdit={() => {}} cancelEdit={() => {}}
            />
          ))}
        </Fragment>
      ))}
    </>
  )
}
