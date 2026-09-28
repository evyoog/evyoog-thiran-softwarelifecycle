import { useMemo } from 'react'

/** A requirement to draw — `selected` distinguishes "you picked this one" from "pulled in because it's connected"; leave it undefined when that distinction doesn't apply (e.g. the Test Cases tab's own diagram view). */
export interface DiagramNode {
  id: string
  key: string
  title: string
  testCaseCount?: number
  selected?: boolean
}

export interface DiagramEdge {
  from: string
  to: string
  type: string
}

export interface DiagramCluster { nodes: DiagramNode[]; edges: DiagramEdge[] }

/**
 * VYB-0830: rounded nodes connected by "depends on" edges. Adapted from (not imported
 * from) `design/RelatedGraph.tsx`'s union-find clustering and BFS-from-hub layout —
 * copied rather than shared so a change here can never regress the Design screen, and
 * because this feature's node content (test-case count / selected-vs-pulled-in) differs
 * from that one's (status / hasTest). Reuses the same `.g-*`/`.rg-*` graph classes
 * `tokens.css` already defines for that reason: a node means the same thing wherever it
 * appears.
 */
export function DependencyDiagram({
  nodes, edges, onOpenNode,
}: { nodes: DiagramNode[]; edges: DiagramEdge[]; onOpenNode?: (id: string) => void }) {
  const clusters = useMemo(() => groupNodes(nodes, edges), [nodes, edges])

  if (clusters.length === 0) return null

  return (
    <div className="rg">
      {clusters.map((cluster) => (
        <ClusterGraph key={cluster.nodes[0].id} cluster={cluster} onOpen={onOpenNode} />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------

/** Connected components, by union-find — same algorithm as RelatedGraph.tsx's `group`. */
export function groupNodes(nodes: DiagramNode[], edges: DiagramEdge[]): DiagramCluster[] {
  const parent = new Map<string, string>(nodes.map((n) => [n.id, n.id]))
  const find = (x: string): string => {
    let root = x
    while (parent.get(root) !== root) root = parent.get(root)!
    let cur = x
    while (parent.get(cur) !== root) { const next = parent.get(cur)!; parent.set(cur, root); cur = next }
    return root
  }
  for (const e of edges) {
    if (!parent.has(e.from) || !parent.has(e.to)) continue
    const a = find(e.from), b = find(e.to)
    if (a !== b) parent.set(a, b)
  }

  const groups = new Map<string, DiagramCluster>()
  for (const n of nodes) {
    const root = find(n.id)
    const g = groups.get(root) ?? { nodes: [], edges: [] }
    g.nodes.push(n)
    groups.set(root, g)
  }
  for (const e of edges) {
    if (!parent.has(e.from) || !parent.has(e.to)) continue
    groups.get(find(e.from))!.edges.push(e)
  }

  return [...groups.values()].sort((a, b) => b.nodes.length - a.nodes.length)
}

/** Layered left to right by distance from the cluster's most-connected node — same as RelatedGraph.tsx's `layout`. */
export function layoutCluster(cluster: DiagramCluster) {
  const degree = new Map<string, number>()
  for (const e of cluster.edges) {
    degree.set(e.from, (degree.get(e.from) ?? 0) + 1)
    degree.set(e.to, (degree.get(e.to) ?? 0) + 1)
  }
  const hub = [...cluster.nodes].sort((a, b) => (degree.get(b.id) ?? 0) - (degree.get(a.id) ?? 0))[0]

  const neighbours = new Map<string, string[]>()
  for (const e of cluster.edges) {
    neighbours.set(e.from, [...(neighbours.get(e.from) ?? []), e.to])
    neighbours.set(e.to, [...(neighbours.get(e.to) ?? []), e.from])
  }

  const depth = new Map<string, number>([[hub.id, 0]])
  const queue = [hub.id]
  while (queue.length) {
    const cur = queue.shift()!
    for (const n of neighbours.get(cur) ?? []) {
      if (!depth.has(n)) { depth.set(n, depth.get(cur)! + 1); queue.push(n) }
    }
  }

  const columns = new Map<number, DiagramNode[]>()
  for (const n of cluster.nodes) {
    const d = depth.get(n.id) ?? 0
    columns.set(d, [...(columns.get(d) ?? []), n])
  }

  const COL = 190
  const ROW = 62
  const tallest = Math.max(...[...columns.values()].map((c) => c.length))
  const pos = new Map<string, { x: number; y: number }>()
  for (const [d, items] of columns) {
    items.forEach((n, i) => {
      const offset = (tallest - items.length) / 2
      pos.set(n.id, { x: 70 + d * COL, y: 40 + (offset + i) * ROW })
    })
  }

  return {
    pos,
    width: 70 + Math.max(...[...columns.keys()]) * COL + 90,
    height: 40 + tallest * ROW + 20,
  }
}

function ClusterGraph({ cluster, onOpen }: { cluster: DiagramCluster; onOpen?: (id: string) => void }) {
  const { pos, width, height } = useMemo(() => layoutCluster(cluster), [cluster])

  return (
    <section className="rg-c">
      <header className="rg-c-h">
        <span className="muted">
          {cluster.nodes.length} requirement{cluster.nodes.length === 1 ? '' : 's'} · {cluster.edges.length} link{cluster.edges.length === 1 ? '' : 's'}
        </span>
      </header>

      <div className="rg-c-s">
        <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="img"
             aria-label={`${cluster.nodes.length} related requirements`}>
          <defs>
            <marker id="dd-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0 0 L8 4 L0 8 z" fill="var(--line-2)" />
            </marker>
          </defs>

          {cluster.edges.map((e, i) => {
            const a = pos.get(e.from)!, b = pos.get(e.to)!
            const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
            return (
              <g key={i}>
                <path
                  d={`M${a.x + 58} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${b.x - 58} ${b.y}`}
                  className="g-edge" strokeWidth={1.2} fill="none" markerEnd="url(#dd-arrow)"
                />
                <text x={mx} y={my - 5} textAnchor="middle" fontSize="8.5" fontFamily="var(--f-mono)" fill="var(--tx-3)">
                  {e.type.toLowerCase()}
                </text>
              </g>
            )
          })}

          {cluster.nodes.map((n) => {
            const p = pos.get(n.id)!
            const nodeCls = n.selected ? 'g-root' : 'g-node'
            const textCls = n.selected ? 'g-root-t' : 'g-node-t'
            return (
              <g key={n.id} onClick={() => onOpen?.(n.id)} className={onOpen ? 'rg-n' : undefined}>
                <rect className={nodeCls} x={p.x - 58} y={p.y - 17} width="116" height="34" rx="10" />
                <text className={textCls} x={p.x} y={p.y - 3} textAnchor="middle" fontFamily="var(--f-mono)" fontSize="9.5">
                  {n.key}{n.testCaseCount !== undefined ? ` · ${n.testCaseCount}` : ''}
                </text>
                <text className={textCls} x={p.x} y={p.y + 9} textAnchor="middle" fontSize="8.5">
                  {n.title.length > 20 ? `${n.title.slice(0, 19)}…` : n.title}
                </text>
                <title>{n.key} — {n.title}{n.selected ? ' (selected)' : n.selected === false ? ' (pulled in — depends on / depended on by a selected requirement)' : ''}</title>
              </g>
            )
          })}
        </svg>
      </div>
    </section>
  )
}
