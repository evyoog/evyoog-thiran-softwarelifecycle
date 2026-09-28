import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, type TraceObjectType } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'

// need→requirement→design→code→test, left to right — the order the ticket names the
// graph after. RELEASE/CLAUSE aren't part of that spine but are real trace-graph node
// types too (TraceObjectType), so they get trailing columns rather than being dropped.
const COLUMNS: TraceObjectType[] = ['NEED', 'REQUIREMENT', 'DESIGN_NODE', 'CODE', 'TEST', 'RELEASE', 'CLAUSE']
const COLUMN_LABEL: Record<TraceObjectType, string> = {
  NEED: 'Need', REQUIREMENT: 'Requirement', DESIGN_NODE: 'Design', CODE: 'Code', TEST: 'Test',
  RELEASE: 'Release', CLAUSE: 'Clause',
}
const COL_WIDTH = 210
const ROW_HEIGHT = 56
const NODE_W = 176
const NODE_H = 40
const PAD = 30

/**
 * VYB-0215: the visual need→requirement→design→code→test graph. Hand-rolled SVG, not
 * a graphing library — frontend/package.json has none installed (d3/reactflow/etc.),
 * and one node+edge set at bounded depth doesn't need one. A swimlane layout (one
 * column per node type, in spine order) rather than a force-directed one: this graph
 * is inherently typed and directional, so columns say more than a generic layout would.
 */
export function TraceGraphPage() {
  const [params] = useSearchParams()
  const urlType = params.get('type') as TraceObjectType | null
  const urlId = params.get('id')

  const [rootType, setRootType] = useState<TraceObjectType>(urlType ?? 'REQUIREMENT')
  const [rootId, setRootId] = useState(urlId ?? '')
  const [pickerQuery, setPickerQuery] = useState('')

  const { data: candidates } = useQuery({
    queryKey: ['requirements', 'trace-graph-picker', pickerQuery],
    queryFn: () => api.requirements({ title: pickerQuery || undefined, size: 8 }),
    enabled: !rootId,
  })

  const { data: graph, isLoading, isError } = useQuery({
    queryKey: ['trace-graph', rootType, rootId],
    queryFn: () => api.traceGraph(rootType, rootId, 6),
    enabled: !!rootId,
  })

  const layout = useMemo(() => {
    if (!graph) return null
    const byColumn = new Map<TraceObjectType, typeof graph.nodes>()
    for (const t of COLUMNS) byColumn.set(t, [])
    for (const n of graph.nodes) byColumn.get(n.type)?.push(n)

    const positions = new Map<string, { x: number; y: number }>()
    const activeColumns = COLUMNS.filter((t) => (byColumn.get(t)?.length ?? 0) > 0)
    activeColumns.forEach((t, colIdx) => {
      const nodes = byColumn.get(t) ?? []
      nodes.forEach((n, rowIdx) => {
        positions.set(`${n.type}:${n.id}`, { x: PAD + colIdx * COL_WIDTH, y: PAD + rowIdx * ROW_HEIGHT })
      })
    })
    const maxRows = Math.max(1, ...activeColumns.map((t) => byColumn.get(t)?.length ?? 0))
    return {
      activeColumns,
      byColumn,
      positions,
      width: PAD * 2 + activeColumns.length * COL_WIDTH,
      height: PAD * 2 + maxRows * ROW_HEIGHT,
    }
  }, [graph])

  return (
    <Page
      title="Trace graph"
      desc="Need → requirement → design → code → test, around one node — bounded to 6 hops each direction. Nodes with no backing record (need/code) are conceptual, not fabricated."
    >
      {!rootId && (
        <div className="card" style={{ maxWidth: 480 }}>
          <div className="eyebrow" style={{ marginBottom: 8 }}>Pick a requirement to start from</div>
          <input
            className="input" placeholder="Search by title…" value={pickerQuery}
            onChange={(e) => setPickerQuery(e.target.value)}
          />
          <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
            {candidates?.content.map((r) => (
              <div
                key={r.id} className="list-item" style={{ cursor: 'pointer' }}
                onClick={() => { setRootType('REQUIREMENT'); setRootId(r.id) }}
              >
                <span className="mono muted" style={{ fontSize: 10 }}>{r.key}</span>
                <span style={{ flex: 1 }}>{r.title}</span>
              </div>
            ))}
            {candidates && candidates.content.length === 0 && <p className="hint muted">No matches.</p>}
          </div>
        </div>
      )}

      {rootId && (
        <>
          <button className="btn" style={{ marginBottom: 12 }} onClick={() => setRootId('')}>← Pick a different root</button>
          {isLoading && <p className="eyebrow">Loading…</p>}
          {isError && <Empty title="Could not load the graph" desc="The root node may not exist, or depth exceeded the server's bound." />}
          {graph && graph.nodes.length <= 1 && (
            <Empty title="No connections yet" desc="This node has no trace links, design coverage, or reachable neighbours within 6 hops." />
          )}
          {layout && graph && graph.nodes.length > 1 && (
            <div className="tbl-wrap" style={{ overflow: 'auto', padding: 16 }}>
              <svg width={layout.width} height={layout.height} style={{ minWidth: '100%' }}>
                {layout.activeColumns.map((t, i) => (
                  <text key={t} x={PAD + i * COL_WIDTH + NODE_W / 2} y={14} textAnchor="middle"
                        fontSize={11} fill="var(--tx-3)" fontWeight={700} style={{ textTransform: 'uppercase', letterSpacing: '.04em' }}>
                    {COLUMN_LABEL[t]}
                  </text>
                ))}
                {graph.edges.map((e, i) => {
                  const from = layout.positions.get(`${e.fromType}:${e.fromId}`)
                  const to = layout.positions.get(`${e.toType}:${e.toId}`)
                  if (!from || !to) return null
                  const x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2
                  const x2 = to.x, y2 = to.y + NODE_H / 2
                  const mx = (x1 + x2) / 2
                  return (
                    <g key={i}>
                      <path d={`M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`}
                            fill="none" stroke="var(--bd-2)" strokeWidth={1.5} />
                      <text x={mx} y={(y1 + y2) / 2 - 4} textAnchor="middle" fontSize={9} fill="var(--tx-3)">
                        {e.linkType}
                      </text>
                    </g>
                  )
                })}
                {graph.nodes.map((n) => {
                  const p = layout.positions.get(`${n.type}:${n.id}`)
                  if (!p) return null
                  return (
                    <g key={`${n.type}:${n.id}`}
                       style={{ cursor: n.type === 'REQUIREMENT' ? 'pointer' : 'default' }}
                       onClick={() => { if (n.type === 'REQUIREMENT') { setRootType('REQUIREMENT'); setRootId(n.id) } }}>
                      <rect x={p.x} y={p.y} width={NODE_W} height={NODE_H} rx={6}
                            fill={n.root ? 'var(--ai-dim)' : 'var(--bg-2)'}
                            stroke={n.root ? 'var(--ai)' : 'var(--bd-2)'} strokeWidth={n.root ? 2 : 1} />
                      <title>{n.label}</title>
                      <text x={p.x + 8} y={p.y + NODE_H / 2 + 4} fontSize={11} fill="var(--tx-1)">
                        {n.label.length > 24 ? n.label.slice(0, 23) + '…' : n.label}
                      </text>
                    </g>
                  )
                })}
              </svg>
            </div>
          )}
        </>
      )}
    </Page>
  )
}
