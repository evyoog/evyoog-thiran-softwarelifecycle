import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Plus, Download, Wand2 } from 'lucide-react'
import { api, type DesignFlowProgress, type DesignNode, type DesignNodeKind, type Requirement } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { RelatedGraph } from './design/RelatedGraph'
import { UndoToast } from '@/shared/ui/UndoToast'
import { Modal } from '@/shared/ui/Modal'
import { resolveApplicationFromCapability } from '@/shared/ui/CapabilityPicker'

const KINDS: DesignNodeKind[] = ['start', 'step', 'decision', 'integration', 'end', 'testing', 'deployment']

// Hardcoded, not var(--x): VYB-0538 AC1 — the exported SVG has to render identically
// outside this application, where our theme's CSS custom properties don't exist.
const NODE_COLORS = {
  satisfied: { fill: '#123322', stroke: '#236B41', text: '#4ADE80' },
  problem: { fill: '#3F1719', stroke: '#7A2F32', text: '#FF6B6B' },
  partial: { fill: '#1E2430', stroke: '#414C60', text: '#98A2B5' },
}
const EDGE_COLOR = '#697489'
const LABEL_COLOR = '#E8ECF3'

const COL_WIDTH = 210
const ROW_HEIGHT = 84
const NODE_W = 150
const NODE_H = 52

/** VYB-0531/0532: BFS distance-from-start as the column — cycle-safe by construction (a visited set), and it's exactly what "branches occupy the same column" (AC2) means once same-depth nodes share a column. */
export function layout(nodes: DesignNode[], edges: { fromNode: string; toNode: string }[]) {
  const outgoing = new Map<string, string[]>()
  const incoming = new Map<string, number>()
  nodes.forEach((n) => incoming.set(n.id, 0))
  edges.forEach((e) => {
    outgoing.set(e.fromNode, [...(outgoing.get(e.fromNode) ?? []), e.toNode])
    incoming.set(e.toNode, (incoming.get(e.toNode) ?? 0) + 1)
  })
  // VYB-0816: a single fallback root broke down once "every requirement feeds one
  // shared Testing node" made most of the graph many independent sources rather than
  // one chain — BFS from just one of them left the other N-1 unreached, all dumped
  // into a single overflow column instead of laid out across the width. A root is
  // properly any node nothing points into; explicit 'start' nodes stay authoritative
  // when a person placed one, but no longer suppress every other real source.
  const explicitStarts = nodes.filter((n) => n.kind === 'start').map((n) => n.id)
  const sourceNodes = nodes.filter((n) => (incoming.get(n.id) ?? 0) === 0).map((n) => n.id)
  const roots = explicitStarts.length > 0 ? explicitStarts : sourceNodes
  const startIds = roots.length > 0 ? roots : nodes.slice(0, 1).map((n) => n.id)

  const distance = new Map<string, number>()
  const order: string[] = []
  const queue = [...startIds]
  startIds.forEach((id) => distance.set(id, 0))
  let qi = 0
  while (qi < queue.length) {
    const cur = queue[qi++]
    order.push(cur)
    const d = distance.get(cur) ?? 0
    for (const next of outgoing.get(cur) ?? []) {
      if (!distance.has(next)) { distance.set(next, d + 1); queue.push(next) }
    }
  }
  let maxCol = 0
  distance.forEach((d) => { if (d > maxCol) maxCol = d })
  // Nodes no edge reaches (disconnected fragments) still have to render somewhere.
  nodes.forEach((n) => { if (!distance.has(n.id)) { distance.set(n.id, maxCol + 1); order.push(n.id) } })

  const columns = new Map<number, string[]>()
  order.forEach((id) => {
    const col = distance.get(id) ?? 0
    columns.set(col, [...(columns.get(col) ?? []), id])
  })
  const positions = new Map<string, { x: number; y: number }>()
  columns.forEach((ids, col) => {
    ids.forEach((id, row) => positions.set(id, { x: col * COL_WIDTH + 30, y: row * ROW_HEIGHT + 30 }))
  })
  const maxRows = Math.max(1, ...Array.from(columns.values()).map((ids) => ids.length));
  return { positions, width: (maxCol + 1) * COL_WIDTH + NODE_W, height: maxRows * ROW_HEIGHT + NODE_H }
}

type ReqStatusMap = Record<string, string>

/** VYB-0810: VERIFIED no longer exists as a status — APPROVED is now the terminal one, so "every requirement behind this node is APPROVED" is what "satisfied" (green) means. */
export function nodeState(node: DesignNode, statuses: ReqStatusMap, progress?: DesignFlowProgress): keyof typeof NODE_COLORS {
  if (node.requirementIds.length === 0) return 'problem' // VYB-0534 AC2
  // VYB-0816: "Testing"/"Deployment" read real evidence (verification, deployment
  // records) via the progress endpoint, not the requirement's own status flag — an
  // APPROVED requirement with no passing test is not "tested" just because it's approved.
  if (node.kind === 'testing' || node.kind === 'deployment') {
    if (!progress || progress.totalRequirements === 0) return 'problem'
    const done = node.kind === 'testing' ? progress.verifiedRequirements : progress.deployedRequirements
    return done === progress.totalRequirements ? 'satisfied' : done === 0 ? 'problem' : 'partial'
  }
  const allApproved = node.requirementIds.every((id) => statuses[id] === 'APPROVED')
  return allApproved ? 'satisfied' : 'partial' // VYB-0534 AC1
}

/**
 * VYB-0826: a small corner badge — how many of the requirements behind an ordinary step
 * already have a test case (`Requirement.hasTest`, the same signal `RelatedGraph`'s
 * "Relationships" tab already uses). Deliberately not folded into `nodeState`/
 * `NODE_COLORS`: that scheme is about approval status, and a step can be fully approved
 * with nothing written to test it — this is a second, independent fact about the same
 * step, shown alongside its colour rather than overriding it. Same green/grey pairing
 * `NODE_COLORS.satisfied`/`.partial` already use, not amber — amber is reserved for AI
 * output (CLAUDE.md), and this is a real count, not a model's guess.
 */
export function testCoverage(requirementIds: string[], requirementsById: Record<string, Requirement>) {
  const total = requirementIds.length
  const tested = requirementIds.filter((id) => requirementsById[id]?.hasTest).length
  return { tested, total, full: total > 0 && tested === total }
}

function TestCoverageBadge({
  x, y, requirementIds, requirementsById,
}: { x: number; y: number; requirementIds: string[]; requirementsById: Record<string, Requirement> }) {
  const { tested, total, full } = testCoverage(requirementIds, requirementsById)
  const cx = x - 9, cy = y + 9
  return (
    <g>
      <circle cx={cx} cy={cy} r={9} fill={full ? '#123322' : '#1E2430'} stroke={full ? '#236B41' : '#414C60'} strokeWidth={1} />
      <text x={cx} y={cy + 3} textAnchor="middle" fontSize={full ? 9 : 7.5} fontWeight={700} fill={full ? '#4ADE80' : '#98A2B5'}>
        {full ? '✓' : `${tested}/${total}`}
      </text>
      <title>{tested}/{total} requirement{total === 1 ? '' : 's'} behind this step {total === 1 ? 'has' : 'have'} a test case</title>
    </g>
  )
}

/**
 * VYB-0803: only a REVIEWED requirement offers the design screen's Approve button — the
 * same REVIEWED -> APPROVED edge the requirement detail page offers, not a shortcut past
 * it (a DRAFT or IN_REVIEW requirement still needs to pass through Reviewed first).
 */
export function canApproveFromDesignScreen(status: string | undefined): boolean {
  return status === 'REVIEWED'
}

/**
 * VYB-0811: the design screen half of "Manual verify" — the same IN_REVIEW -> REVIEWED
 * move the requirement detail page's "Verify" modal offers, once the reviewer has
 * actually read the requirement here.
 */
export function canVerifyFromDesignScreen(status: string | undefined): boolean {
  return status === 'IN_REVIEW'
}

/** VYB-0530–0538. */
export function Design() {
  const [tab, setTab] = useState<'diagram' | 'coverage' | 'related'>('diagram')
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId,
  })

  // VYB-0811: "Manual verify" on the requirement detail page sends the reviewer here
  // with ?requirementId=<id> rather than making them re-find the product and
  // application themselves — the same capability-to-application-to-product walk
  // CapabilityPicker already does from a bare capability id, one level further up.
  const [searchParams] = useSearchParams()
  const focusRequirementId = searchParams.get('requirementId') ?? undefined
  const { data: focusReq } = useQuery({
    queryKey: ['requirement', focusRequirementId],
    queryFn: () => api.requirement(focusRequirementId!),
    enabled: !!focusRequirementId,
  })
  const candidateProducts = (products ?? []).filter((p) => !p.archived)
  const needsFocusResolution = !!focusReq?.capabilityId && !productId
  const focusAppLookups = useQueries({
    queries: candidateProducts.map((p) => ({
      queryKey: ['applications', p.id],
      queryFn: () => api.applications(p.id),
      enabled: needsFocusResolution,
    })),
  })
  const focusApplications = focusAppLookups.flatMap((q) => q.data ?? [])
  const focusCapLookups = useQueries({
    queries: focusApplications.map((a) => ({
      queryKey: ['capabilities', a.id],
      queryFn: () => api.capabilities(a.id),
      enabled: needsFocusResolution,
    })),
  })
  useEffect(() => {
    if (!needsFocusResolution || !focusReq?.capabilityId) return
    const hit = resolveApplicationFromCapability(
      focusApplications, focusCapLookups.map((q) => q.data), focusReq.capabilityId)
    if (hit) { setProductId(hit.productId); setApplicationId(hit.applicationId); setTab('diagram') }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needsFocusResolution, focusReq?.capabilityId, focusCapLookups.map((q) => q.dataUpdatedAt).join()])
  // Shared queryKey with DiagramTab/CoverageTab's own useQuery for the same flow —
  // React Query dedupes identical keys, so this doesn't cost a second request.
  const { data: flow } = useQuery({
    queryKey: ['design-flow', applicationId], queryFn: () => api.designFlowFor(applicationId),
    enabled: !!applicationId, retry: false,
  })
  const { data: coverage } = useQuery({
    queryKey: ['design-coverage-summary', applicationId, flow?.id],
    queryFn: () => api.designCoverageSummary(applicationId, flow!.id),
    enabled: !!applicationId && !!flow,
  })

  return (
    <Page title="Design" desc="One workflow per app, assembled from the requirements it implements.">
      <div className="row toolbar" style={{ marginBottom: 14 }}>
        <div className="field">
          <label className="label">Product</label>
          <select className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId('') }}>
            <option value="">—</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label">Application</label>
          <select className="select" value={applicationId} disabled={!productId} onChange={(e) => setApplicationId(e.target.value)}>
            <option value="">—</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      </div>

      {!applicationId ? (
        <Empty title="Pick a product and application" desc="Each application has its own workflow." />
      ) : (
        <>
          <div style={{ display: 'flex', gap: 8, marginBottom: 14, alignItems: 'center' }}>
            <button className={`btn${tab === 'diagram' ? ' pri' : ''}`} onClick={() => setTab('diagram')}>Diagram</button>
            <button className={`btn${tab === 'coverage' ? ' pri' : ''}`} onClick={() => setTab('coverage')}>Coverage</button>
            {/* The links between requirements already exist; nothing showed the shape they
                add up to. This groups them so a change can be scoped to everything it
                actually touches rather than to the one requirement in front of you. */}
            <button className={`btn${tab === 'related' ? ' pri' : ''}`} onClick={() => setTab('related')}>Relationships</button>
            {/* VYB-0537: a real percentage with its denominator (see DesignService.coverageSummary),
                not just the two offending-item counts the Coverage tab itself shows. */}
            {coverage && (
              <span
                className="badge"
                title={`${coverage.requirementsWithDesign}/${coverage.totalRequirements} requirements have a design node · ${coverage.nodesWithRequirement}/${coverage.totalNodes} design nodes trace to a requirement`}
                style={{
                  marginLeft: 'auto',
                  color: coverage.requirementCoveragePct >= 90 ? 'var(--ok)' : coverage.requirementCoveragePct >= 60 ? 'var(--high)' : 'var(--crit)',
                }}
              >
                {coverage.requirementCoveragePct}% requirement coverage · {coverage.nodeCoveragePct}% nodes traced
              </span>
            )}
          </div>
          {tab === 'diagram' && <DiagramTab applicationId={applicationId} focusRequirementId={focusRequirementId} />}
          {tab === 'coverage' && <CoverageTab applicationId={applicationId} />}
          {tab === 'related' && <RelatedGraph applicationId={applicationId} />}
        </>
      )}
    </Page>
  )
}

function DiagramTab({ applicationId, focusRequirementId }: { applicationId: string; focusRequirementId?: string }) {
  const qc = useQueryClient()
  const svgRef = useRef<SVGSVGElement>(null)
  const [selectedNode, setSelectedNode] = useState<string>('')
  const [showAdd, setShowAdd] = useState(false)
  const [undo, setUndo] = useState<{ nodeId: string; message: string } | null>(null)

  const { data: flow, isLoading: loadingFlow, isError: noFlow } = useQuery({
    queryKey: ['design-flow', applicationId], queryFn: () => api.designFlowFor(applicationId), retry: false,
  })
  const { data: nodes } = useQuery({
    queryKey: ['design-nodes', flow?.id], queryFn: () => api.designNodes(flow!.id), enabled: !!flow,
  })
  // VYB-0811: once this application's nodes are in, jump straight to whichever one
  // already draws the requirement "Manual verify" sent us here for — so the reviewer
  // lands on it rather than having to find it themselves in a diagram they may not
  // have opened before.
  useEffect(() => {
    if (!focusRequirementId || selectedNode) return
    const hit = (nodes ?? []).find((n) => n.requirementIds.includes(focusRequirementId))
    if (hit) setSelectedNode(hit.id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusRequirementId, nodes])
  const focusNotDrawn = !!focusRequirementId && !!nodes
    && !nodes.some((n) => n.requirementIds.includes(focusRequirementId)) && !selectedNode
  const { data: edges } = useQuery({
    queryKey: ['design-edges', flow?.id], queryFn: () => api.designEdges(flow!.id), enabled: !!flow,
  })
  const { data: progress } = useQuery({
    queryKey: ['design-progress', flow?.id], queryFn: () => api.designFlowProgress(flow!.id), enabled: !!flow,
  })

  const distinctReqIds = useMemo(
    () => Array.from(new Set((nodes ?? []).flatMap((n) => n.requirementIds))), [nodes]);
  const reqQueries = useQueries({
    queries: distinctReqIds.map((id) => ({ queryKey: ['requirement', id], queryFn: () => api.requirement(id) })),
  })
  const statuses: ReqStatusMap = useMemo(() => {
    const map: ReqStatusMap = {}
    distinctReqIds.forEach((id, i) => { const r = reqQueries[i]?.data; if (r) map[id] = r.status })
    return map
  }, [distinctReqIds, reqQueries])
  // VYB-0803: the full requirement, not just its status string — the Approve button in
  // NodeInspector needs the revision to call the same transition endpoint the
  // requirement detail page uses (optimistic concurrency applies here too).
  const requirementsById = useMemo(() => {
    const map: Record<string, Requirement> = {}
    distinctReqIds.forEach((id, i) => { const r = reqQueries[i]?.data; if (r) map[id] = r })
    return map
  }, [distinctReqIds, reqQueries])

  const createFlow = useMutation({
    mutationFn: () => api.createDesignFlow(applicationId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['design-flow', applicationId] }),
  })

  const invalidateFlow = () => {
    void qc.invalidateQueries({ queryKey: ['design-nodes', flow?.id] })
    void qc.invalidateQueries({ queryKey: ['design-edges', flow?.id] })
    void qc.invalidateQueries({ queryKey: ['design-progress', flow?.id] })
  }

  const deleteNode = useMutation({
    mutationFn: (id: string) => api.deleteDesignNode(id),
    onSuccess: () => { setSelectedNode(''); invalidateFlow() },
  })

  // VYB-0666: placing a node per requirement by hand after every import is the sort of
  // transcription nobody keeps up, so the diagram was always out of date with the
  // register. This derives it. Kept as an explicit action rather than something that runs
  // on load: the flow is a thing people edit, and a diagram that regenerates itself under
  // an open editor is worse than one that waits to be asked.
  const generate = useMutation({
    mutationFn: () => api.generateDesign(flow!.id, applicationId),
    onSuccess: invalidateFlow,
  })

  const exportSvg = () => {
    if (!svgRef.current) return
    const serializer = new XMLSerializer()
    const source = serializer.serializeToString(svgRef.current)
    const blob = new Blob([`<?xml version="1.0" encoding="UTF-8"?>\n${source}`], { type: 'image/svg+xml' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = `${applicationId}-workflow.svg`; a.click()
    URL.revokeObjectURL(url)
  }

  if (loadingFlow) return <p className="eyebrow">Loading…</p>
  if (noFlow || !flow) {
    return (
      <div>
        <Empty
          title="No workflow yet for this application"
          desc="Create the one flow this application gets — a second one is refused."
        />
        <button className="btn pri" style={{ marginTop: 10 }} onClick={() => createFlow.mutate()}>
          <Plus /> Create workflow
        </button>
      </div>
    )
  }

  const { positions, width, height } = layout(nodes ?? [], edges ?? [])
  const selected = nodes?.find((n) => n.id === selectedNode);

  return (
    <div style={{ display: 'grid', gridTemplateColumns: selected ? '1fr 320px' : '1fr', gap: 16 }}>
      <div>
        {focusNotDrawn && (
          <p className="hint" style={{ marginBottom: 8, color: 'var(--high)' }}>
            This requirement isn't drawn on this diagram yet — click "Generate from requirements" below to add it, then it'll open here.
          </p>
        )}
        <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
          <button className="btn" onClick={() => setShowAdd(true)}><Plus /> Add a step</button>
          <button
            className="btn pri" disabled={generate.isPending}
            onClick={() => generate.mutate()}
            title="Draw a step for every approved requirement in this application that has none yet"
          >
            <Wand2 /> {generate.isPending ? 'Generating…' : 'Generate from requirements'}
          </button>
          <button className="btn" onClick={exportSvg}><Download /> Export SVG</button>
          {/* Principle 8: what it did, in the numbers it actually did it in — including
              the requirements it left alone because they were already on the diagram. */}
          {generate.data && (
            <span className="hint muted">
              {generate.data.nodesCreated === 0 && generate.data.skippedAlreadyDrawn > 0
                ? `Already up to date — all ${generate.data.skippedAlreadyDrawn} requirements are on the diagram.`
                : `Added ${generate.data.nodesCreated} step${generate.data.nodesCreated === 1 ? '' : 's'} and ${generate.data.edgesCreated} link${generate.data.edgesCreated === 1 ? '' : 's'}${generate.data.skippedAlreadyDrawn > 0 ? `; ${generate.data.skippedAlreadyDrawn} were already drawn` : ''}.`}
            </span>
          )}
          {generate.isError && <span className="err-text">Could not generate the diagram.</span>}
        </div>
        {(!nodes || nodes.length === 0) ? (
          <Empty title="No steps yet" desc="Add the first one above." />
        ) : (
          <div className="card" style={{ overflow: 'auto', padding: 0 }}>
            <svg ref={svgRef} width={width} height={height} style={{ display: 'block', background: '#12161F' }}>
              {(edges ?? []).map((e) => {
                const from = positions.get(e.fromNode); const to = positions.get(e.toNode)
                if (!from || !to) return null
                const x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2
                const x2 = to.x, y2 = to.y + NODE_H / 2
                const midX = (x1 + x2) / 2
                return (
                  <g key={e.id}>
                    <path d={`M ${x1} ${y1} C ${midX} ${y1}, ${midX} ${y2}, ${x2} ${y2}`}
                      fill="none" stroke={EDGE_COLOR} strokeWidth={1.5} markerEnd="url(#arrow)" />
                    {e.label && (
                      <text x={midX} y={(y1 + y2) / 2 - 6} fill={LABEL_COLOR} fontSize={10} textAnchor="middle">
                        {e.label}
                      </text>
                    )}
                  </g>
                )
              })}
              <defs>
                <marker id="arrow" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 Z" fill={EDGE_COLOR} />
                </marker>
              </defs>
              {(nodes ?? []).map((n) => {
                const pos = positions.get(n.id)
                if (!pos) return null
                const state = nodeState(n, statuses, progress)
                const c = NODE_COLORS[state]
                const isSelected = n.id === selectedNode
                const isMilestone = n.kind === 'testing' || n.kind === 'deployment'
                return (
                  <g key={n.id} style={{ cursor: 'pointer' }} onClick={() => setSelectedNode(n.id)}>
                    {n.kind === 'decision' ? (
                      <polygon
                        points={`${pos.x + NODE_W / 2},${pos.y} ${pos.x + NODE_W},${pos.y + NODE_H / 2} ${pos.x + NODE_W / 2},${pos.y + NODE_H} ${pos.x},${pos.y + NODE_H / 2}`}
                        fill={c.fill} stroke={isSelected ? LABEL_COLOR : c.stroke} strokeWidth={isSelected ? 2 : 1.5}
                      />
                    ) : n.kind === 'start' || n.kind === 'end' || isMilestone ? (
                      <rect x={pos.x} y={pos.y} width={NODE_W} height={NODE_H} rx={NODE_H / 2}
                        fill={c.fill} stroke={isSelected ? LABEL_COLOR : c.stroke} strokeWidth={isSelected ? 2 : 1.5} />
                    ) : (
                      <rect x={pos.x} y={pos.y} width={NODE_W} height={NODE_H} rx={6}
                        fill={c.fill} stroke={isSelected ? LABEL_COLOR : c.stroke} strokeWidth={isSelected ? 2 : 1.5}
                        strokeDasharray={n.kind === 'integration' ? '6,4' : undefined} />
                    )}
                    <text x={pos.x + NODE_W / 2} y={pos.y + NODE_H / 2 - 2} fill={LABEL_COLOR} fontSize={11.5}
                      textAnchor="middle" fontWeight={600}>
                      {n.label.length > 20 ? n.label.slice(0, 19) + '…' : n.label}
                    </text>
                    <text x={pos.x + NODE_W / 2} y={pos.y + NODE_H / 2 + 14} fill={c.text} fontSize={9.5} textAnchor="middle">
                      {n.kind === 'integration' ? 'external · ' : ''}
                      {n.kind === 'testing' && progress ? `${progress.verifiedRequirements}/${progress.totalRequirements} verified`
                        : n.kind === 'deployment' && progress ? `${progress.deployedRequirements}/${progress.totalRequirements} deployed`
                        : n.requirementIds.length === 0 ? 'no requirement' : `${n.requirementIds.length} req(s)`}
                    </text>
                    {/* VYB-0826: which requirements behind this step already have a test
                        case — separate from `state`'s APPROVED-status colour, since a step
                        can be fully approved and still have nothing written to test it. */}
                    {!isMilestone && n.requirementIds.length > 0 && (
                      <TestCoverageBadge x={pos.x + NODE_W} y={pos.y} requirementIds={n.requirementIds} requirementsById={requirementsById} />
                    )}
                  </g>
                )
              })}
            </svg>
          </div>
        )}
      </div>

      {selected && (
        <NodeInspector
          node={selected} statuses={statuses} requirementsById={requirementsById} edges={edges ?? []} nodes={nodes ?? []}
          onClose={() => setSelectedNode('')}
          onLinked={invalidateFlow}
          onDelete={() => {
            setUndo({ nodeId: selected.id, message: `Removed "${selected.label}"` })
            deleteNode.mutate(selected.id)
          }}
        />
      )}

      {showAdd && (
        <AddStepModal
          flowId={flow.id} nodes={nodes ?? []}
          onClose={() => setShowAdd(false)}
          onAdded={(nodeId, label) => { invalidateFlow(); setUndo({ nodeId, message: `Added "${label}"` }) }}
        />
      )}

      {undo && (
        <UndoToast
          message={undo.message}
          onExpire={() => setUndo(null)}
          onUndo={async () => { await api.deleteDesignNode(undo.nodeId); invalidateFlow() }}
        />
      )}
    </div>
  )
}

/** VYB-0535: requirements, connections, and attach/detach controls. */
function NodeInspector({
  node, statuses, requirementsById, edges, nodes, onClose, onLinked, onDelete,
}: {
  node: DesignNode
  statuses: ReqStatusMap
  requirementsById: Record<string, Requirement>
  edges: { id: string; fromNode: string; toNode: string; label?: string }[]
  nodes: DesignNode[]
  onClose: () => void
  onLinked: () => void
  onDelete: () => void
}) {
  const qc = useQueryClient()
  const [newReqId, setNewReqId] = useState('')
  const link = useMutation({
    mutationFn: () => api.linkDesignRequirement(node.id, newReqId.trim()),
    onSuccess: () => { setNewReqId(''); onLinked() },
  })
  const unlink = useMutation({
    mutationFn: (reqId: string) => api.unlinkDesignRequirement(node.id, reqId),
    onSuccess: onLinked,
  })
  // VYB-0803: "I can approve the requirements from the design screen too" — the same
  // REVIEWED -> APPROVED move the requirement detail page offers, reusing the same
  // transition endpoint rather than a second copy of the state machine.
  const approve = useMutation({
    mutationFn: (reqId: string) => {
      const r = requirementsById[reqId]
      if (!r) throw new Error('Requirement not loaded yet')
      return api.transitionRequirement(reqId, r.revision, 'APPROVED')
    },
    onSuccess: (_data, reqId) => { void qc.invalidateQueries({ queryKey: ['requirement', reqId] }) },
  })
  // VYB-0811: the other half of "Manual verify" — read the requirement below, then
  // record the IN_REVIEW -> REVIEWED move from here, the same transition endpoint
  // every other move on this screen and the detail page already share.
  const verify = useMutation({
    mutationFn: (reqId: string) => {
      const r = requirementsById[reqId]
      if (!r) throw new Error('Requirement not loaded yet')
      return api.transitionRequirement(reqId, r.revision, 'REVIEWED')
    },
    onSuccess: (_data, reqId) => { void qc.invalidateQueries({ queryKey: ['requirement', reqId] }) },
  })
  const labelFor = (id: string) => nodes.find((n) => n.id === id)?.label ?? id.slice(0, 8)

  return (
    <div className="card" style={{ alignSelf: 'start' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <strong>{node.label}</strong>
        <button className="btn" style={{ padding: 4 }} onClick={onClose}>✕</button>
      </div>
      <div className="muted" style={{ fontSize: 11, marginBottom: 10 }}>{node.kind}{node.note ? ` · ${node.note}` : ''}</div>

      <div className="eyebrow" style={{ marginBottom: 4 }}>Requirements</div>
      {node.requirementIds.length === 0 && <p className="hint muted">None linked — this is why it renders as a problem.</p>}
      {node.requirementIds.map((id) => {
        const r = requirementsById[id]
        return (
          <div key={id} style={{ padding: '5px 8px', borderBottom: '1px solid var(--line-2)' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
              {/* VYB-0811: "read the full requirement" needs the title on screen, not
                  just an id — the full statement is one click away on its own page
                  rather than duplicated here, so editing it always happens in one place. */}
              <Link to={`/requirements/${id}`} className="mono" style={{ flex: 1, fontSize: 11 }} title="Open the full requirement">
                {r ? `${r.key} — ${r.title}` : `${id.slice(0, 8)}…`}
              </Link>
              <span className="muted" style={{ fontSize: 10 }}>{statuses[id] ?? '…'}</span>
              <button className="btn" style={{ padding: 2 }} onClick={() => unlink.mutate(id)}>✕</button>
            </div>
            {r?.statement && <p className="hint muted" style={{ margin: '3px 0 4px', fontSize: 10.5 }}>{r.statement}</p>}
            {canVerifyFromDesignScreen(statuses[id]) && (
              <button
                className="btn" style={{ padding: '2px 6px', fontSize: 10 }}
                disabled={verify.isPending}
                title="Verify this requirement — moves it from In review to Reviewed, ready for approval"
                onClick={() => verify.mutate(id)}
              >
                Verify
              </button>
            )}
            {canApproveFromDesignScreen(statuses[id]) && (
              <button
                className="btn" style={{ padding: '2px 6px', fontSize: 10 }}
                disabled={approve.isPending}
                title="Approve this requirement — moves it from Reviewed to Approved"
                onClick={() => approve.mutate(id)}
              >
                Approve
              </button>
            )}
          </div>
        )
      })}
      {verify.isError && (
        <p className="hint" style={{ color: 'var(--crit)' }}>
          Could not verify — it may have moved on to a different status already.
        </p>
      )}
      {approve.isError && (
        <p className="hint" style={{ color: 'var(--crit)' }}>
          Could not approve — it may have moved on to a different status already.
        </p>
      )}
      <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
        <input className="input" style={{ flex: 1 }} placeholder="Requirement ID" value={newReqId} onChange={(e) => setNewReqId(e.target.value)} />
        <button className="btn" disabled={!newReqId.trim() || link.isPending} onClick={() => link.mutate()}>Link</button>
      </div>

      <div className="eyebrow" style={{ margin: '14px 0 4px' }}>Connections</div>
      {edges.filter((e) => e.toNode === node.id).map((e) => (
        <div key={e.id} className="mono muted" style={{ fontSize: 11 }}>← {labelFor(e.fromNode)}</div>
      ))}
      {edges.filter((e) => e.fromNode === node.id).map((e) => (
        <div key={e.id} className="mono muted" style={{ fontSize: 11 }}>→ {labelFor(e.toNode)}{e.label ? ` (${e.label})` : ''}</div>
      ))}

      <button className="btn" style={{ marginTop: 14, width: '100%' }} onClick={onDelete}>Delete this step</button>
    </div>
  )
}

/** VYB-0536: label, kind, predecessor, branch label and requirements — undoable, warns if nothing's linked. */
function AddStepModal({
  flowId, nodes, onClose, onAdded,
}: { flowId: string; nodes: DesignNode[]; onClose: () => void; onAdded: (nodeId: string, label: string) => void }) {
  const [label, setLabel] = useState('')
  const [kind, setKind] = useState<DesignNodeKind>('step')
  const [note, setNote] = useState('')
  const [predecessor, setPredecessor] = useState('')
  const [branchLabel, setBranchLabel] = useState('')
  const [requirementIds, setRequirementIds] = useState('')

  const add = useMutation({
    mutationFn: async () => {
      const node = await api.addDesignNode(flowId, { kind, label, note: note || undefined })
      if (predecessor) {
        await api.addDesignEdge(flowId, { fromNode: predecessor, toNode: node.id, label: branchLabel || undefined })
      }
      const ids = requirementIds.split(',').map((s) => s.trim()).filter(Boolean)
      for (const id of ids) await api.linkDesignRequirement(node.id, id)
      return node
    },
    onSuccess: (node) => { onAdded(node.id, label); onClose() },
  })

  const noRequirementWarning = !requirementIds.trim()

  return (
    <Modal onClose={onClose} title="Add a step">
        <h3>Add a step</h3>
        <div className="field">
          <label className="label">Label</label>
          <input className="input" value={label} onChange={(e) => setLabel(e.target.value)} />
        </div>
        <div className="row">
          <div className="field">
            <label className="label">Kind</label>
            <select className="select" value={kind} onChange={(e) => setKind(e.target.value as DesignNodeKind)}>
              {KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="label">Predecessor (optional)</label>
            <select className="select" value={predecessor} onChange={(e) => setPredecessor(e.target.value)}>
              <option value="">— none —</option>
              {nodes.map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
            </select>
          </div>
        </div>
        <div className="field">
          <label className="label">Branch label (only meaningful from a decision)</label>
          <input className="input" value={branchLabel} onChange={(e) => setBranchLabel(e.target.value)} disabled={!predecessor} />
        </div>
        <div className="field">
          <label className="label">Note (optional)</label>
          <input className="input" value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="field">
          <label className="label">Requirement IDs it implements (comma-separated)</label>
          <input className="input" value={requirementIds} onChange={(e) => setRequirementIds(e.target.value)} />
          {noRequirementWarning && <p className="hint" style={{ color: 'var(--high)' }}>No requirement linked — this step will render as a problem.</p>}
        </div>
        {add.isError && <p className="err-text">Could not add that step.</p>}
        <div className="actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn pri" disabled={!label.trim() || add.isPending} onClick={() => add.mutate()}>Add</button>
        </div>
    </Modal>
  )
}

/** VYB-0537: requirements with no design, and steps with no requirement. */
function CoverageTab({ applicationId }: { applicationId: string }) {
  const { data: flow } = useQuery({ queryKey: ['design-flow', applicationId], queryFn: () => api.designFlowFor(applicationId), retry: false })
  const { data: uncovered } = useQuery({ queryKey: ['design-coverage', applicationId], queryFn: () => api.designCoverage(applicationId) })
  const { data: orphans } = useQuery({
    queryKey: ['design-orphans', flow?.id], queryFn: () => api.designOrphanNodes(flow!.id), enabled: !!flow,
  })

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
      <div>
        <h4 className="section-h">Requirements with no design ({uncovered?.length ?? 0})</h4>
        {uncovered && uncovered.length === 0 && <Empty title="Every requirement has a design node" desc="" />}
        {uncovered?.map((r) => (
          <div key={r.id} className="list-item">
            <span className="mono muted" style={{ fontSize: 10 }}>{r.key}</span>
            <span style={{ flex: 1 }}>{r.title}</span>
          </div>
        ))}
      </div>
      <div>
        <h4 className="section-h">Design nobody asked for ({orphans?.length ?? 0})</h4>
        {orphans && orphans.length === 0 && <Empty title="Every step traces to a requirement" desc="" />}
        {orphans?.map((n) => (
          <div key={n.id} className="list-item">
            <span className="badge">{n.kind}</span>
            <span style={{ flex: 1 }}>{n.label}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
