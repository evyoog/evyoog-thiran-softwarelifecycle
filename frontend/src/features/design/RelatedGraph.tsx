import { useMemo } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api, type Requirement, type TraceLink } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'

/**
 * Requirements that relate to each other, drawn as one graph per group.
 *
 * <p>The trace links already record which requirement derives from, refines, satisfies or
 * conflicts with which. What no screen showed was the shape those links add up to: open a
 * requirement and you see its own two or three links, never that it sits in the middle of
 * a chain of nine that has to be changed together. This groups requirements into connected
 * components — follow the links from any requirement and you reach everything in its
 * cluster — and draws each cluster on its own, because two clusters that share no link are
 * two separate things and drawing them on one canvas would imply a relationship that does
 * not exist.
 *
 * <p>Requirements with no links at all are counted rather than plotted. A page of isolated
 * dots is noise, but silently dropping them would misrepresent the scope (Principle 8), so
 * the number is stated.
 */
export function RelatedGraph({ applicationId }: { applicationId: string }) {
  const navigate = useNavigate()

  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', applicationId],
    queryFn: () => api.capabilities(applicationId),
    enabled: !!applicationId,
  })

  // One page per capability. The requirements endpoint filters by capability, not by
  // application, so this is what "every requirement in this app" costs.
  const reqQueries = useQueries({
    queries: (capabilities ?? []).map((c) => ({
      queryKey: ['requirements', 'by-capability', c.id],
      queryFn: () => api.requirements({ capabilityId: c.id, size: 200 }),
    })),
  })
  const requirements: Requirement[] = reqQueries.flatMap((q) => q.data?.content ?? [])

  // Links are per object, so this is one request each. Cheap enough at an application's
  // scale and it reuses whatever the detail pages already cached under the same key.
  const linkQueries = useQueries({
    queries: requirements.map((r) => ({
      queryKey: ['trace-links', r.id],
      queryFn: () => api.links('REQUIREMENT', r.id),
    })),
  })

  const loading = reqQueries.some((q) => q.isLoading) || linkQueries.some((q) => q.isLoading)

  const clusters = useMemo(() => {
    const byId = new Map(requirements.map((r) => [r.id, r]))

    // Undirected for grouping — "related" has no direction — but each edge keeps its own
    // direction and type for the arrow and the label.
    const edges: Edge[] = []
    const seen = new Set<string>()
    for (const q of linkQueries) {
      for (const l of [...(q.data?.outgoing ?? []), ...(q.data?.incoming ?? [])] as TraceLink[]) {
        if (l.fromType !== 'REQUIREMENT' || l.toType !== 'REQUIREMENT') continue
        if (!byId.has(l.fromId) || !byId.has(l.toId)) continue
        // Both directions of the same link come back — once as this requirement's
        // outgoing, once as the other's incoming — so it would be drawn twice.
        if (seen.has(l.id)) continue
        seen.add(l.id)
        edges.push({ from: l.fromId, to: l.toId, type: l.linkType })
      }
    }

    return group(requirements, edges)
  }, [requirements.length, linkQueries.map((q) => q.dataUpdatedAt).join()])

  if (!capabilities?.length) {
    return <Empty title="No capabilities in this application" desc="Requirements are grouped by the links between them; there are none to read yet." />
  }
  if (loading) return <p className="eyebrow">Reading requirements and their links…</p>

  const linked = clusters.filter((c) => c.nodes.length > 1)
  const isolated = clusters.length - linked.length

  if (linked.length === 0) {
    return (
      <Empty
        title="No requirement is linked to another yet"
        desc={`All ${requirements.length} requirements in this application stand alone. Add trace links on a requirement — derives, refines, satisfies, conflicts — and the groups they form appear here.`}
      />
    )
  }

  return (
    <div className="rg">
      <p className="hint muted rg-sum">
        {linked.length} group{linked.length === 1 ? '' : 's'} of related requirements ·
        {' '}{linked.reduce((n, c) => n + c.nodes.length, 0)} of {requirements.length} requirements linked ·
        {' '}{isolated} stand alone and {isolated === 1 ? 'is' : 'are'} not drawn
      </p>

      {linked.map((cluster, i) => (
        <Cluster key={cluster.nodes[0].id} cluster={cluster} index={i + 1} onOpen={(id) => navigate(`/requirements/${id}`)} />
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------

interface Edge { from: string; to: string; type: string }
interface Group { nodes: Requirement[]; edges: Edge[] }

/**
 * Connected components, by union-find. Two requirements are in the same group when a path
 * of links joins them, however long — which is the question worth answering here: "what
 * else moves if I change this one".
 */
function group(requirements: Requirement[], edges: Edge[]): Group[] {
  const parent = new Map<string, string>(requirements.map((r) => [r.id, r.id]))
  const find = (x: string): string => {
    let root = x
    while (parent.get(root) !== root) root = parent.get(root)!
    // Path compression, so a long chain does not re-walk on every lookup.
    let cur = x
    while (parent.get(cur) !== root) { const next = parent.get(cur)!; parent.set(cur, root); cur = next }
    return root
  }
  for (const e of edges) {
    const a = find(e.from), b = find(e.to)
    if (a !== b) parent.set(a, b)
  }

  const groups = new Map<string, Group>()
  for (const r of requirements) {
    const root = find(r.id)
    const g = groups.get(root) ?? { nodes: [], edges: [] }
    g.nodes.push(r)
    groups.set(root, g)
  }
  for (const e of edges) groups.get(find(e.from))!.edges.push(e)

  // Biggest first: the largest cluster is the one with the most at stake.
  return [...groups.values()].sort((a, b) => b.nodes.length - a.nodes.length)
}

/**
 * Layered left to right by distance from the cluster's most-connected requirement, which
 * reads as "this is the hub, these follow from it". A force layout would look organic and
 * settle differently on every render; this is deterministic, so the same cluster is in the
 * same shape every time you open it.
 */
function layout(cluster: Group) {
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

  const columns = new Map<number, Requirement[]>()
  for (const r of cluster.nodes) {
    const d = depth.get(r.id) ?? 0
    columns.set(d, [...(columns.get(d) ?? []), r])
  }

  const COL = 190
  const ROW = 62
  const tallest = Math.max(...[...columns.values()].map((c) => c.length))
  const pos = new Map<string, { x: number; y: number }>()
  for (const [d, items] of columns) {
    items.forEach((r, i) => {
      // Centred in its column, so a short column sits opposite the middle of a long one.
      const offset = (tallest - items.length) / 2
      pos.set(r.id, { x: 70 + d * COL, y: 40 + (offset + i) * ROW })
    })
  }

  return {
    pos,
    width: 70 + Math.max(...[...columns.keys()]) * COL + 90,
    height: 40 + tallest * ROW + 20,
  }
}

function Cluster({ cluster, index, onOpen }: { cluster: Group; index: number; onOpen: (id: string) => void }) {
  const { pos, width, height } = useMemo(() => layout(cluster), [cluster])
  const gaps = cluster.nodes.filter((r) => !r.hasTest).length

  return (
    <section className="rg-c">
      <header className="rg-c-h">
        <span className="eyebrow">Group {index}</span>
        <span className="muted">
          {cluster.nodes.length} requirements · {cluster.edges.length} link{cluster.edges.length === 1 ? '' : 's'}
          {gaps > 0 && ` · ${gaps} with no passing test`}
        </span>
      </header>

      <div className="rg-c-s">
        <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} role="img"
             aria-label={`${cluster.nodes.length} related requirements`}>
          <defs>
            <marker id="rg-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0 0 L8 4 L0 8 z" fill="var(--line-2)" />
            </marker>
          </defs>

          {cluster.edges.map((e, i) => {
            const a = pos.get(e.from)!, b = pos.get(e.to)!
            const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
            // A conflict is the one link type that means "these disagree", so it is the
            // one worth seeing without reading the label.
            const conflict = e.type === 'CONFLICTS'
            return (
              <g key={i}>
                <path
                  d={`M${a.x + 58} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${b.x - 58} ${b.y}`}
                  className={conflict ? 'g-brk' : 'g-edge'} strokeWidth={conflict ? 1.4 : 1.2}
                  strokeDasharray={conflict ? '5 4' : undefined}
                  fill="none" markerEnd="url(#rg-arrow)"
                />
                <text x={mx} y={my - 5} textAnchor="middle" fontSize="8.5"
                      fontFamily="var(--f-mono)" fill={conflict ? 'var(--crit)' : 'var(--tx-3)'}>
                  {e.type.toLowerCase()}
                </text>
              </g>
            )
          })}

          {cluster.nodes.map((r) => {
            const p = pos.get(r.id)!
            return (
              <g key={r.id} onClick={() => onOpen(r.id)} className="rg-n">
                <rect
                  className={r.hasTest ? 'g-ok' : 'g-node'}
                  x={p.x - 58} y={p.y - 17} width="116" height="34" rx="6"
                />
                <text className={r.hasTest ? 'g-ok-t' : 'g-node-t'} x={p.x} y={p.y - 3}
                      textAnchor="middle" fontFamily="var(--f-mono)" fontSize="9.5">{r.key}</text>
                <text className="g-node-t" x={p.x} y={p.y + 9} textAnchor="middle" fontSize="8.5">
                  {r.title.length > 20 ? `${r.title.slice(0, 19)}…` : r.title}
                </text>
                <title>{r.key} — {r.title}</title>
              </g>
            )
          })}
        </svg>
      </div>
    </section>
  )
}
