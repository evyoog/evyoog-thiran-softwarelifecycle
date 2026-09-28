import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query'
import { api, type GraphEdge, type GraphNode, type Requirement } from '@/shared/api/client'

/**
 * The trace graph, as an actual graph rather than a column of parallel chains.
 *
 * <p>The previous version drew one row per requirement — its own NEED/DESIGN/TEST chain,
 * in isolation, with no way to show that two requirements were related to each other at
 * all. That was a real gap, not a style choice: the register knows exactly which
 * requirements relate (every trace link, including the ones the standard PRD template's
 * own Depends On column resolves into at import), and none of it reached this screen.
 *
 * <p>This fetches the real graph around every requirement on screen — {@code
 * /trace/{type}/{id}/graph}, the same endpoint the single-requirement Trace Links panel
 * already calls — and merges them into one structure by node id. A requirement two rows
 * link to shows up once, connected to both, which is what makes this graph theory rather
 * than a report: nodes and edges, not one strip per record.
 *
 * <p>Grouped into connected components, one drawn per group — two components sharing no
 * edge are two unrelated things, and drawing them on one canvas would imply a relationship
 * that is not there. Requirements with no links at all are counted, not plotted, so the
 * scope is stated rather than silently narrowed (Principle 8).
 */
export function TraceGraph({
  requirements, onOpen, applicationId,
}: {
  requirements: Requirement[]
  onOpen: (id: string) => void
  /** The single application these requirements are scoped to, if any — see autoDesign below. */
  applicationId?: string
}) {
  // Beyond this the fan-out becomes one request per row for no further insight — the
  // count below says what was left out rather than quietly truncating.
  const LIMIT = 40
  const shown = requirements.slice(0, LIMIT)

  const graphQueries = useQueries({
    queries: shown.map((r) => ({
      queryKey: ['trace-graph', 'REQUIREMENT', r.id, 3],
      queryFn: () => api.traceGraph('REQUIREMENT', r.id, 3),
    })),
  })
  const loading = graphQueries.some((q) => q.isLoading)

  const autoDesign = useAutoDesignCoverage(applicationId, graphQueries, shown.length)

  const merged = useMemo(() => {
    const nodes = new Map<string, GraphNode>()
    const edgeKeys = new Set<string>()
    const edges: GraphEdge[] = []
    for (const q of graphQueries) {
      for (const n of q.data?.nodes ?? []) {
        // A node reachable from more than one requirement's own subgraph arrives more
        // than once with an identical shape — keep the first, they never actually differ.
        if (!nodes.has(`${n.type}:${n.id}`)) nodes.set(`${n.type}:${n.id}`, n)
      }
      for (const e of q.data?.edges ?? []) {
        const key = `${e.fromType}:${e.fromId}>${e.toType}:${e.toId}:${e.linkType}`
        if (!edgeKeys.has(key)) { edgeKeys.add(key); edges.push(e) }
      }
    }
    return { nodes: [...nodes.values()], edges }
  }, [graphQueries.map((q) => q.dataUpdatedAt).join()])

  const requirementById = new Map(shown.map((r) => [r.id, r]))
  const clusters = useMemo(() => group(merged.nodes, merged.edges), [merged])
  const linked = clusters.filter((c) => c.nodes.length > 1)
  const isolatedCount = shown.length - new Set(linked.flatMap((c) => c.nodes.map((n) => n.id))
    .filter((id) => requirementById.has(id))).size

  if (loading) return <p className="eyebrow" style={{ padding: 20 }}>Reading the trace graph…</p>
  if (shown.length === 0) return <p className="eyebrow" style={{ padding: 20 }}>Nothing in scope to plot.</p>

  const banner = autoDesign.status && (
    <p className={`hint tg-auto ${autoDesign.status === 'error' ? 'err-text' : 'muted'}`}>
      {autoDesign.status === 'running' && 'Generating design coverage automatically…'}
      {autoDesign.status === 'done' && autoDesign.message}
      {autoDesign.status === 'error' && `Could not auto-generate design coverage: ${autoDesign.message}`}
    </p>
  )

  if (linked.length === 0) {
    return (
      <div style={{ padding: 20 }}>
        <p className="eyebrow">
          None of these {shown.length} requirements have design or test coverage recorded yet
          {isolatedCount < shown.length ? '' : ', and none depend on each other'}.
        </p>
        <p className="hint muted" style={{ marginTop: 6 }}>
          Depends-on relationships resolve automatically on import — nothing to link by hand there.
          {applicationId
            ? ' Design coverage is being generated the same way, automatically, from this application’s own approved requirements.'
            : ' Design coverage needs one application in scope to generate automatically — pick a product and app on the left to have it happen here.'}
        </p>
        {banner}
      </div>
    )
  }

  return (
    <div className="tg">
      {banner}
      <p className="hint muted tg-sum">
        {linked.length} connected group{linked.length === 1 ? '' : 's'} · {isolatedCount} of {shown.length} requirements
        have no link at all and are not drawn
        {requirements.length > LIMIT && ` · showing the first ${LIMIT} of ${requirements.length} requirements`}
      </p>
      {linked.map((cluster, i) => (
        <TraceCluster key={cluster.nodes[0].id} cluster={cluster} index={i + 1}
                       requirementById={requirementById} onOpen={onOpen} />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------

/**
 * VYB-0666: design coverage without a manual step. The Design screen's own "Generate
 * from requirements" button already does the real work here — one step per
 * approved-or-better requirement, linked, additive, never touching a hand-edited
 * diagram — this only removes the click, firing it once automatically when the Graph
 * tab is opened for an application that has no design coverage at all yet.
 *
 * <p>Scoped strictly to a single named application. Depends-on resolution is safe to run
 * for any set of requirements because it only ever creates links between rows already in
 * front of the person committing them — but generating a whole application's design
 * coverage writes into a different screen's data (Design), and doing that for every
 * application an unscoped "All requirements" view happens to touch, with no application
 * named, is a materially bigger and less accountable action than the one this was asked
 * to remove. Without a single applicationId this simply does not run — the empty state
 * says why, rather than reaching further than what was asked.
 */
function useAutoDesignCoverage(
  applicationId: string | undefined,
  graphQueries: { data?: { nodes: GraphNode[] }; isLoading: boolean }[],
  shownCount: number,
): { status: 'running' | 'done' | 'error' | null; message?: string } {
  const qc = useQueryClient()
  const [state, setState] = useState<{ status: 'running' | 'done' | 'error'; message?: string } | null>(null)
  const attempted = useRef<string | null>(null)

  const anyDesignNode = graphQueries.some((q) => q.data?.nodes.some((n) => n.type === 'DESIGN_NODE'))
  const stillLoading = graphQueries.length === 0 || graphQueries.some((q) => q.isLoading)

  const run = useMutation({
    mutationFn: async (appId: string) => {
      let flow = await api.designFlowFor(appId).catch(() => null)
      if (!flow) flow = await api.createDesignFlow(appId)
      return api.generateDesign(flow.id, appId)
    },
    onSuccess: (result) => {
      setState({
        status: 'done',
        message: result.nodesCreated === 0
          ? 'No approved requirements to generate design steps from yet.'
          : `Generated ${result.nodesCreated} design step${result.nodesCreated === 1 ? '' : 's'} and ${result.edgesCreated} link${result.edgesCreated === 1 ? '' : 's'} automatically.`,
      })
      void qc.invalidateQueries({ queryKey: ['trace-graph'] })
    },
    onError: (e) => setState({ status: 'error', message: e instanceof Error ? e.message : String(e) }),
  })

  useEffect(() => {
    if (!applicationId || shownCount === 0 || stillLoading || anyDesignNode) return
    if (attempted.current === applicationId) return
    attempted.current = applicationId
    setState({ status: 'running' })
    run.mutate(applicationId)
    // Runs once per application id the first time this graph has zero design nodes —
    // re-checking on every dependency change would refire it after every fetch, not
    // just the first time the answer was "nothing generated yet".
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationId, shownCount, stillLoading, anyDesignNode])

  return state ?? { status: null }
}

// ---------------------------------------------------------------------------

interface Node extends GraphNode {}
interface Group { nodes: Node[]; edges: GraphEdge[] }

/** Connected components by union-find — a link anywhere in the chain puts both ends in the same group. */
function group(nodes: Node[], edges: GraphEdge[]): Group[] {
  const parent = new Map<string, string>(nodes.map((n) => [n.id, n.id]))
  const find = (x: string): string => {
    let root = x
    while (parent.get(root) !== root) root = parent.get(root)!
    let cur = x
    while (parent.get(cur) !== root) { const next = parent.get(cur)!; parent.set(cur, root); cur = next }
    return root
  }
  for (const e of edges) {
    if (!parent.has(e.fromId) || !parent.has(e.toId)) continue
    const a = find(e.fromId), b = find(e.toId)
    if (a !== b) parent.set(a, b)
  }
  const groups = new Map<string, Group>()
  for (const n of nodes) {
    const root = find(n.id)
    const g = groups.get(root) ?? { nodes: [], edges: [] }
    g.nodes.push(n)
    groups.set(root, g)
  }
  for (const e of edges) {
    if (!parent.has(e.fromId)) continue
    groups.get(find(e.fromId))?.edges.push(e)
  }
  return [...groups.values()].sort((a, b) => b.nodes.length - a.nodes.length)
}

/**
 * Left to right by the stage the prototype's own graph uses — NEED, REQUIREMENT,
 * DESIGN_NODE, then everything downstream of a design (TEST, CODE) — so the picture
 * reads in the same direction the pipeline actually runs, rather than an arbitrary
 * layout that happens to avoid overlaps.
 */
const STAGE_ORDER: Record<string, number> = {
  NEED: 0, REQUIREMENT: 1, DESIGN_NODE: 2, CODE: 3, TEST: 3, RELEASE: 4, CLAUSE: 1,
}

const STAGE_LABEL: Record<number, string> = { 0: 'NEED', 1: 'REQUIREMENT', 2: 'DESIGN', 3: 'TEST', 4: 'RELEASE' }

/** Room at the top for the NEED / REQUIREMENT / DESIGN / TEST column headers. */
const AXIS_HEIGHT = 26

function layout(cluster: Group) {
  const columns = new Map<number, Node[]>()
  for (const n of cluster.nodes) {
    const col = STAGE_ORDER[n.type] ?? 1
    columns.set(col, [...(columns.get(col) ?? []), n])
  }

  const COL = 190
  const ROW = 58
  const cols = [...columns.keys()].sort((a, b) => a - b)
  const tallest = Math.max(...[...columns.values()].map((c) => c.length))
  const pos = new Map<string, { x: number; y: number }>()
  const axis: { x: number; label: string }[] = []
  cols.forEach((col, ci) => {
    const items = columns.get(col)!
    const x = 70 + ci * COL
    axis.push({ x, label: STAGE_LABEL[col] ?? '' })
    const offset = (tallest - items.length) / 2
    items.forEach((n, i) => pos.set(n.id, { x, y: AXIS_HEIGHT + 40 + (offset + i) * ROW }))
  })

  return {
    pos,
    axis,
    width: 70 + (cols.length - 1) * COL + 100,
    height: AXIS_HEIGHT + 40 + tallest * ROW + 20,
  }
}

const NODE_STYLE: Record<string, { rect: boolean; cls: string; textCls: string }> = {
  NEED: { rect: false, cls: 'g-root', textCls: 'g-root-t' },
  REQUIREMENT: { rect: false, cls: 'g-node', textCls: 'g-node-t' },
  DESIGN_NODE: { rect: true, cls: 'g-node', textCls: 'g-node-t' },
  TEST: { rect: true, cls: 'g-ok', textCls: 'g-ok-t' },
  CODE: { rect: true, cls: 'g-node', textCls: 'g-node-t' },
  RELEASE: { rect: true, cls: 'g-node', textCls: 'g-node-t' },
  CLAUSE: { rect: false, cls: 'g-node', textCls: 'g-node-t' },
}

/**
 * Zoom by scaling the SVG's own rendered box, not an inner transform on a fixed-size
 * viewport. The earlier version scaled a {@code <g>} inside an unchanged {@code <svg
 * width height>} — zooming in made the content larger inside a box that stayed the same
 * pixel size, so anything past the edge was clipped by the container's own {@code
 * overflow: hidden} with nothing to scroll to reach it. That is what read as "the graph
 * isn't visible" and "no connecting lines" — the lines were there, past the clipped edge.
 * Scaling the rendered {@code width}/{@code height} while the {@code viewBox} stays fixed
 * is how SVG zoom is meant to work: the browser's own scrollbar handles anything the
 * zoomed size no longer fits, so nothing needs a hand-rolled pan to reach it.
 *
 * <p>No wheel handler at all — scrolling this container is page scroll, exactly what
 * the mouse wheel is for everywhere else on the screen; hijacking it to zoom is what
 * made the graph appear to "zoom in and out on its own" while scrolling past it.
 */
function TraceCluster({
  cluster, index, requirementById, onOpen,
}: {
  cluster: Group
  index: number
  requirementById: Map<string, Requirement>
  onOpen: (id: string) => void
}) {
  const { pos, axis, width, height } = useMemo(() => layout(cluster), [cluster])
  const [hover, setHover] = useState<{ node: Node; x: number; y: number } | null>(null)
  const [scale, setScale] = useState(1)
  const svgRef = useRef<SVGSVGElement>(null)

  const requirementCount = cluster.nodes.filter((n) => n.type === 'REQUIREMENT').length
  const gapCount = cluster.nodes.filter((n) => {
    const r = requirementById.get(n.id)
    return r && !r.hasTest
  }).length

  return (
    <section className="tg-c">
      <header className="tg-c-h">
        <span className="eyebrow">Group {index}</span>
        <span className="muted">
          {requirementCount} requirement{requirementCount === 1 ? '' : 's'} · {cluster.edges.length} link{cluster.edges.length === 1 ? '' : 's'}
          {gapCount > 0 && ` · ${gapCount} with no passing test`}
        </span>
        <div className="tg-zoom">
          <button className="btn" onClick={() => setScale((s) => Math.max(0.5, s / 1.25))} title="Zoom out">−</button>
          <span className="mono muted">{Math.round(scale * 100)}%</span>
          <button className="btn" onClick={() => setScale((s) => Math.min(2.5, s * 1.25))} title="Zoom in">+</button>
          {scale !== 1 && <button className="btn" onClick={() => setScale(1)} title="Reset zoom">Reset</button>}
        </div>
      </header>

      <div className="tg-c-s">
        <svg ref={svgRef} viewBox={`0 0 ${width} ${height}`} width={width * scale} height={height * scale}
             role="img" aria-label={`${requirementCount} related requirements`}>
          <defs>
            <marker id={`tg-arrow-${index}`} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0 0 L8 4 L0 8 z" fill="var(--line-2)" />
            </marker>
          </defs>
          {/* The NEED / REQUIREMENT / DESIGN / TEST column headings — only the stages
              actually present in this cluster, so an empty column never gets a floating
              label with nothing under it. */}
          <g className="g-ax" fontFamily="var(--f-ui)" fontSize="9" letterSpacing="1.2">
            {axis.map(({ x, label }) => (
              <text key={x} x={x} y={16} textAnchor="middle">{label}</text>
            ))}
          </g>
          <g>
            {cluster.edges.map((e, i) => {
              const a = pos.get(e.fromId), b = pos.get(e.toId)
              if (!a || !b) return null
              const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
              const conflict = e.linkType === 'CONFLICTS'
              return (
                <g key={i}>
                  <path d={`M${a.x + 24} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${b.x - 24} ${b.y}`}
                        className={conflict ? 'g-brk' : 'g-edge'} strokeWidth={conflict ? 1.4 : 1.2}
                        strokeDasharray={conflict ? '5 4' : undefined} fill="none"
                        markerEnd={`url(#tg-arrow-${index})`} />
                  <text x={mx} y={my - 5} textAnchor="middle" fontSize="8.5" fontFamily="var(--f-mono)"
                        fill={conflict ? 'var(--crit)' : 'var(--tx-3)'}>
                    {e.linkType.toLowerCase()}
                  </text>
                </g>
              )
            })}

            {cluster.nodes.map((n) => {
              const p = pos.get(n.id)
              if (!p) return null
              const req = n.type === 'REQUIREMENT' ? requirementById.get(n.id) : undefined
              const style = NODE_STYLE[n.type] ?? NODE_STYLE.REQUIREMENT
              // A requirement we have data for and know failed its coverage is drawn red,
              // same distinction the single-requirement graph already used; a requirement
              // outside the current filtered view (a neighbour pulled in only because it
              // links to one that is) has no such data and stays neutral rather than
              // guessing at its state.
              const cls = req && !req.hasTest ? 'g-bad' : style.cls
              const textCls = req && !req.hasTest ? 'g-bad-t' : style.textCls
              return (
                <g key={`${n.type}:${n.id}`}
                   onClick={() => n.type === 'REQUIREMENT' && onOpen(n.id)}
                   onMouseEnter={(e) => {
                     const rect = svgRef.current!.getBoundingClientRect()
                     setHover({ node: n, x: e.clientX - rect.left, y: e.clientY - rect.top })
                   }}
                   onMouseLeave={() => setHover(null)}
                   style={{ cursor: n.type === 'REQUIREMENT' ? 'pointer' : 'default' }}>
                  {style.rect ? (
                    <rect className={cls} x={p.x - 30} y={p.y - 15} width="60" height="30" rx="5" />
                  ) : (
                    <circle className={cls} cx={p.x} cy={p.y} r="17"
                            strokeWidth={cls === 'g-bad' || cls === 'g-root' ? 1.4 : undefined} />
                  )}
                  <text className={textCls} x={p.x} y={p.y + 3.5} textAnchor="middle"
                        fontFamily="var(--f-mono)" fontSize="9">
                    {n.label.length > 10 ? `${n.label.slice(0, 9)}…` : n.label}
                  </text>
                </g>
              )
            })}
          </g>
        </svg>

        {/* The "small model box" on hover — a real preview instead of the browser's own
            plain-text title tooltip, so it can carry the requirement's status and a
            fuller label than fits on the node itself. */}
        {hover && (
          <div className="tg-hover" style={{ left: hover.x + 14, top: hover.y + 14 }}>
            <span className={`tg-hover-k tg-hover-${hover.node.type.toLowerCase()}`}>{hover.node.type.replace('_', ' ')}</span>
            <p className="tg-hover-l">{hover.node.label}</p>
            {(() => {
              const req = requirementById.get(hover.node.id)
              return req ? (
                <p className="tg-hover-s">
                  {req.statement.length > 140 ? `${req.statement.slice(0, 139)}…` : req.statement}
                </p>
              ) : null
            })()}
          </div>
        )}
      </div>
    </section>
  )
}
