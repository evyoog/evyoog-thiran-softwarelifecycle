import { describe, it, expect } from 'vitest'
import type { DiagramEdge, DiagramNode } from './DependencyDiagram'
import { groupNodes, layoutCluster } from './DependencyDiagram'

function node(over: Partial<DiagramNode> & { id: string }): DiagramNode {
  return { key: over.id, title: over.id, ...over }
}

describe('VYB-0830 — dependency diagram clustering', () => {
  it('VYB0830_AC3_theFiveRequirementExampleFormsOneCluster', () => {
    // req-2 depends on req-1, req-1 depends on req-0, req-3/req-4 also depend on req-1.
    const nodes = ['req-0', 'req-1', 'req-2', 'req-3', 'req-4'].map((id) => node({ id }))
    const edges: DiagramEdge[] = [
      { from: 'req-2', to: 'req-1', type: 'DERIVES' },
      { from: 'req-1', to: 'req-0', type: 'DERIVES' },
      { from: 'req-3', to: 'req-1', type: 'DERIVES' },
      { from: 'req-4', to: 'req-1', type: 'DERIVES' },
    ]
    const clusters = groupNodes(nodes, edges)
    expect(clusters).toHaveLength(1)
    expect(clusters[0].nodes.map((n) => n.id).sort()).toEqual(['req-0', 'req-1', 'req-2', 'req-3', 'req-4'])
    expect(clusters[0].edges).toHaveLength(4)
  })

  it('VYB0830_AC3_disconnectedNodesFormSeparateClusters', () => {
    const nodes = [node({ id: 'a' }), node({ id: 'b' }), node({ id: 'c' }), node({ id: 'd' })]
    const edges: DiagramEdge[] = [{ from: 'a', to: 'b', type: 'DERIVES' }]
    const clusters = groupNodes(nodes, edges)
    expect(clusters).toHaveLength(3)
    expect(clusters[0].nodes).toHaveLength(2)
  })

  it('VYB0830_AC3_edgesReferencingAnUnknownNodeAreIgnored', () => {
    const nodes = [node({ id: 'a' }), node({ id: 'b' })]
    const edges: DiagramEdge[] = [{ from: 'a', to: 'ghost', type: 'DERIVES' }]
    const clusters = groupNodes(nodes, edges)
    expect(clusters).toHaveLength(2)
    expect(clusters.every((c) => c.edges.length === 0)).toBe(true)
  })

  it('VYB0830_AC3_layoutPlacesEveryNodeAndSizesToTheWidestChain', () => {
    const cluster = groupNodes(
      ['req-0', 'req-1', 'req-2'].map((id) => node({ id })),
      [{ from: 'req-2', to: 'req-1', type: 'DERIVES' }, { from: 'req-1', to: 'req-0', type: 'DERIVES' }],
    )[0]
    const { pos, width, height } = layoutCluster(cluster)
    expect(pos.size).toBe(3)
    expect(width).toBeGreaterThan(0)
    expect(height).toBeGreaterThan(0)
  })
})
