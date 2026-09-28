import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { api, type RequirementSuggestions, type TestCaseSuggestion } from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import { groupByCategory, SuggestionCard } from './SuggestionCard'
import { DependencyDiagram, type DiagramEdge, type DiagramNode } from './DependencyDiagram'

type PendingSuggestion = TestCaseSuggestion & { _id: number }
type PendingGroup = {
  requirementId: string
  requirementKey: string
  requirementTitle: string
  pulledInAsDependency: boolean
  suggestions: PendingSuggestion[]
}

/**
 * VYB-0826/0830: generating for a bulk selection also generates for the selection's
 * whole connected dependency component — {@code pulledInAsDependency} on each group
 * says which requirement was actually selected vs. only pulled in. Before any AI call
 * fires, this shows that full component as a "depends on" diagram, computed with no AI
 * call via {@code dependencyCluster} — the product owner's own example ("selecting only
 * req-2 pulls in req-0 through req-4 because they're all connected") is exactly what
 * that computation walks, and this is where it's shown before anyone commits to
 * generating anything. Same "AI proposes, human decides" discipline as the
 * single-requirement panel: nothing here is saved until reviewed and accepted, per card
 * or via "Accept all" once you're satisfied with the whole batch. A requirement that
 * ends up with at least one accepted test case is reported back via {@code onGenerated}
 * so the caller can drop it from a "needs a test case" list.
 */
export function BulkTestCaseReviewPanel({
  requirementIds, onClose, onGenerated,
}: { requirementIds: string[]; onClose: () => void; onGenerated: (touchedRequirementIds: string[]) => void }) {
  const [groups, setGroups] = useState<PendingGroup[] | null>(null)
  const [model, setModel] = useState('')
  const [touched, setTouched] = useState<Set<string>>(new Set())

  const cluster = useQuery({
    queryKey: ['dependency-cluster', requirementIds],
    queryFn: () => api.dependencyCluster(requirementIds),
  })

  const diagram = useMemo(() => {
    if (!cluster.data) return { nodes: [] as DiagramNode[], edges: [] as DiagramEdge[] }
    const idByKey = new Map(cluster.data.members.map((m) => [m.key, m.requirementId]))
    return {
      nodes: cluster.data.members.map((m): DiagramNode => ({
        id: m.requirementId, key: m.key, title: m.title, selected: m.selected,
      })),
      edges: cluster.data.edges.flatMap((e): DiagramEdge[] => {
        const from = idByKey.get(e.fromKey), to = idByKey.get(e.toKey)
        return from && to ? [{ from, to, type: e.linkType }] : []
      }),
    }
  }, [cluster.data])

  const suggest = useMutation({
    mutationFn: () => api.testCaseSuggestionsBulk(requirementIds),
    onSuccess: (data) => {
      setGroups(data.perRequirement.map((rs: RequirementSuggestions) => ({
        requirementId: rs.requirementId, requirementKey: rs.requirementKey, requirementTitle: rs.requirementTitle,
        pulledInAsDependency: rs.pulledInAsDependency,
        suggestions: rs.suggestions.map((s, i) => ({ ...s, _id: i })),
      })))
      setModel(data.model)
    },
  })

  const markTouched = (requirementId: string) => setTouched((t) => new Set(t).add(requirementId))

  const remove = (requirementId: string, suggestionId: number) =>
    setGroups((gs) => gs?.map((g) => g.requirementId === requirementId
      ? { ...g, suggestions: g.suggestions.filter((s) => s._id !== suggestionId) }
      : g) ?? null)

  const pendingCount = groups?.reduce((n, g) => n + g.suggestions.length, 0) ?? 0

  const acceptAll = useMutation({
    mutationFn: async () => {
      if (!groups) return
      // Sequential, not Promise.all: a failed row shouldn't leave a half-applied batch
      // racing with the ones still in flight, and the per-row toast below needs to
      // reflect exactly one failure at a time.
      for (const g of groups) {
        for (const s of g.suggestions) {
          await api.draftTestCase(s.title, s.description.trim() || undefined, s.category, g.requirementId)
          markTouched(g.requirementId)
          remove(g.requirementId, s._id)
        }
      }
    },
  })

  const handleClose = () => {
    onGenerated([...touched])
    onClose()
  }

  return (
    <Modal onClose={handleClose} title="Generate test cases in bulk" maxWidth={720}>
      <h3>Generate test cases in bulk</h3>
      <p className="hint muted">
        For {requirementIds.length} selected requirement{requirementIds.length === 1 ? '' : 's'} — also generates for
        every requirement it's connected to, however far the chain runs (shown below). Nothing is saved until you accept it.
      </p>

      {!groups && (
        <>
          {cluster.isLoading && <p className="eyebrow">Finding connected requirements…</p>}
          {cluster.isError && (
            <p className="hint" style={{ color: 'var(--crit)', fontSize: 11 }}>Could not read the dependency cluster.</p>
          )}
          {cluster.data && (
            <>
              <div className="eyebrow" style={{ margin: '10px 0 6px' }}>
                {cluster.data.members.length} requirement{cluster.data.members.length === 1 ? '' : 's'} in this cluster
                {cluster.data.capped && ' · stopped early, this cluster is unusually large'}
              </div>
              <DependencyDiagram nodes={diagram.nodes} edges={diagram.edges} />
            </>
          )}
          <button className="btn pri" style={{ marginTop: 12 }} disabled={suggest.isPending || cluster.isLoading} onClick={() => suggest.mutate()}>
            {suggest.isPending ? 'Asking AI…' : `Generate test cases for ${cluster.data?.members.length ?? requirementIds.length} requirement${(cluster.data?.members.length ?? requirementIds.length) === 1 ? '' : 's'}`}
          </button>
        </>
      )}
      {suggest.isError && (
        <p className="hint" style={{ color: 'var(--crit)', fontSize: 11, marginTop: 6 }}>
          Could not generate suggestions — the AI provider may be unreachable or not configured.
        </p>
      )}

      {groups && groups.length === 0 && (
        <p className="hint muted">No suggestions were produced.</p>
      )}

      {groups && groups.length > 0 && (
        <>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '14px 0' }}>
            <span className="eyebrow">{pendingCount} suggestion{pendingCount === 1 ? '' : 's'} to review{model && ` · ${model}`}</span>
            <button className="btn pri" disabled={pendingCount === 0 || acceptAll.isPending} onClick={() => acceptAll.mutate()}>
              {acceptAll.isPending ? 'Adding…' : 'Accept all'}
            </button>
          </div>
          {acceptAll.isError && <p className="err-text">Could not add every suggestion — retry the ones still listed below.</p>}

          {groups.map((g) => (
            <RequirementGroup key={g.requirementId} group={g}
              onDismiss={(sid) => remove(g.requirementId, sid)}
              onAdded={(sid) => { markTouched(g.requirementId); remove(g.requirementId, sid) }} />
          ))}
        </>
      )}

      <div className="actions">
        <button className="btn" onClick={handleClose}>Close</button>
      </div>
    </Modal>
  )
}

function RequirementGroup({
  group, onDismiss, onAdded,
}: { group: PendingGroup; onDismiss: (suggestionId: number) => void; onAdded: (suggestionId: number) => void }) {
  const { individual, dependency } = groupByCategory(group.suggestions)

  return (
    <section style={{ marginBottom: 18 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6 }}>
        <span className="mono muted" style={{ fontSize: 10 }}>{group.requirementKey}</span>
        <strong style={{ fontSize: 13 }}>{group.requirementTitle}</strong>
        {group.pulledInAsDependency && (
          <span className="badge" style={{ fontSize: 10 }}>pulled in — linked to a selected requirement</span>
        )}
      </div>
      {group.suggestions.length === 0 && <p className="hint muted">Nothing left to review here.</p>}
      {individual.map((s) => (
        <SuggestionCard key={s._id} suggestion={s} requirementId={group.requirementId}
          onDismiss={() => onDismiss(s._id)} onAdded={() => onAdded(s._id)} />
      ))}
      {dependency.map((s) => (
        <SuggestionCard key={s._id} suggestion={s} requirementId={group.requirementId}
          onDismiss={() => onDismiss(s._id)} onAdded={() => onAdded(s._id)} />
      ))}
    </section>
  )
}
