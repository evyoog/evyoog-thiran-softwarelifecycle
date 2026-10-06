import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { ApiError, api, type RunStatus, type TestPlan, type TestSuite } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { useMe } from '@/shared/useMe'
import { RunDetailView } from './RunDetailView'
import { STATUS_CLASS, STATUS_GLYPH, STATUS_LABEL, canExecute, runTitle } from './testRuns'

type View = 'runs' | 'plans'

const STATUSES: RunStatus[] = ['PLANNED', 'IN_PROGRESS', 'COMPLETED']

/**
 * VYB-0927 (F14): manual test runs on the Quality screen. Runs (newest first, filterable by status) and Plans (each
 * with its suites and a button to create a run of a suite). Opening a run shows its cases and steps and, for a
 * Tester, lets them execute it. Creating and editing plans, suites and steps is not here yet; it stays in the API.
 */
export function TestRunsTab() {
  const { data: me } = useMe()
  const canRun = canExecute(me)
  const [view, setView] = useState<View>('runs')
  const [openRun, setOpenRun] = useState<string | null>(null)

  if (openRun) {
    return <RunDetailView runId={openRun} canRun={canRun} onBack={() => setOpenRun(null)} onOpenRun={setOpenRun} />
  }

  return (
    <>
      <p className="hint muted" style={{ marginBottom: 12 }}>
        A plan holds suites; a suite is an ordered group of test cases; a run executes one suite, step by step. Completing
        a run records verification evidence against each requirement it covers. It never changes a requirement's status.
      </p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }} role="group" aria-label="Test runs view">
        <button className={`btn${view === 'runs' ? ' pri' : ''}`} aria-pressed={view === 'runs'} onClick={() => setView('runs')}>Runs</button>
        <button className={`btn${view === 'plans' ? ' pri' : ''}`} aria-pressed={view === 'plans'} onClick={() => setView('plans')}>Plans</button>
      </div>
      {view === 'runs' ? <RunsList onOpen={setOpenRun} /> : <PlansView canRun={canRun} onOpenRun={setOpenRun} />}
    </>
  )
}

function RunsList({ onOpen }: { onOpen: (id: string) => void }) {
  const [status, setStatus] = useState<RunStatus | ''>('')
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['test-runs', status], queryFn: () => api.testRuns({ status: status || undefined }),
  })

  return (
    <>
      <div className="field" style={{ maxWidth: 220 }}>
        <label className="label" htmlFor="tr-status">Status</label>
        <select id="tr-status" className="select" value={status} onChange={(e) => setStatus(e.target.value as RunStatus | '')}>
          <option value="">All</option>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      </div>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && (
        <div className="empty"><h4>Could not load runs</h4><button className="btn" onClick={() => void refetch()}>Try again</button></div>
      )}
      {data && data.length === 0 && (
        <Empty title={status ? `No ${STATUS_LABEL[status].toLowerCase()} runs` : 'No test runs yet'}
          desc="Create one from a suite under Plans." />
      )}
      {data && data.length > 0 && (
        <div className="tbl-wrap">
          <table className="tbl">
            <thead><tr><th>Run</th><th>Status</th><th>Cases</th><th>Assigned to</th><th>Created</th></tr></thead>
            <tbody>
              {data.map((r) => (
                <tr key={r.id} onClick={() => onOpen(r.id)}>
                  <td>
                    <button className="btn" style={{ fontSize: 12 }} onClick={(e) => { e.stopPropagation(); onOpen(r.id) }}>{runTitle(r)}</button>
                    {r.retestOf && <span className="hint muted"> · retest</span>}
                  </td>
                  <td>
                    <span className={`badge ${STATUS_CLASS[r.status]}`}>
                      <span aria-hidden="true">{STATUS_GLYPH[r.status]}</span> {STATUS_LABEL[r.status]}
                    </span>
                  </td>
                  <td className="mono">{r.caseCount}</td>
                  <td className="muted">{r.assignedToName ?? 'no one'}</td>
                  <td className="muted">{new Date(r.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function PlansView({ canRun, onOpenRun }: { canRun: boolean; onOpenRun: (id: string) => void }) {
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['test-plans'], queryFn: () => api.testPlans() })
  const [open, setOpen] = useState<string | null>(null)

  return (
    <>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && (
        <div className="empty"><h4>Could not load plans</h4><button className="btn" onClick={() => void refetch()}>Try again</button></div>
      )}
      {data && data.length === 0 && (
        <Empty title="No test plans yet" desc="Plans are created through the API for now; once one exists it appears here." />
      )}
      {data && data.length > 0 && (
        <div className="tbl-wrap">
          <table className="tbl">
            <thead><tr><th aria-label="Expand" /><th>Plan</th><th>Application</th><th>Suites</th><th>Runs</th></tr></thead>
            <tbody>
              {data.map((p) => (
                <PlanRow key={p.id} plan={p} expanded={open === p.id} canRun={canRun}
                  onToggle={() => setOpen(open === p.id ? null : p.id)} onOpenRun={onOpenRun} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function PlanRow({
  plan, expanded, canRun, onToggle, onOpenRun,
}: { plan: TestPlan; expanded: boolean; canRun: boolean; onToggle: () => void; onOpenRun: (id: string) => void }) {
  const panelId = `plan-${plan.id}`
  return (
    <Fragment>
      <tr onClick={onToggle}>
        <td style={{ width: 28 }}>
          <button className="btn" style={{ padding: '0 4px' }} aria-expanded={expanded} aria-controls={panelId}
            aria-label={`${expanded ? 'Hide' : 'Show'} suites of ${plan.key}`} onClick={(e) => { e.stopPropagation(); onToggle() }}>
            {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        </td>
        <td>
          <span className="mono muted" style={{ fontSize: 10 }}>{plan.key}</span> {plan.name}
          {plan.description && <div className="hint muted">{plan.description}</div>}
        </td>
        <td className="muted">{plan.applicationName}</td>
        <td className="mono">{plan.suiteCount}</td>
        <td className="mono">{plan.runCount}</td>
      </tr>
      {expanded && (
        <tr style={{ cursor: 'default' }}>
          <td colSpan={5} id={panelId}><Suites plan={plan} canRun={canRun} onOpenRun={onOpenRun} /></td>
        </tr>
      )}
    </Fragment>
  )
}

function Suites({ plan, canRun, onOpenRun }: { plan: TestPlan; canRun: boolean; onOpenRun: (id: string) => void }) {
  const qc = useQueryClient()
  const { data, isLoading, isError } = useQuery({ queryKey: ['test-suites', plan.id], queryFn: () => api.testSuites(plan.id) })
  const [error, setError] = useState<string | undefined>()
  const create = useMutation({
    mutationFn: (suite: TestSuite) => api.createTestRun(suite.id),
    onSuccess: (d) => {
      void qc.invalidateQueries({ queryKey: ['test-runs'] })
      void qc.invalidateQueries({ queryKey: ['test-plans'] })
      onOpenRun(d.run.id)
    },
    onError: (e) => setError(e instanceof ApiError ? (e.detail ?? e.title) : 'Could not create the run.'),
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) return <p className="hint muted">Could not load the suites of {plan.key}.</p>
  if (data.length === 0) return <p className="hint muted">{plan.key} has no suites yet.</p>
  return (
    <>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {data.map((s) => (
          <li key={s.id} className="list-item">
            <span className="mono muted" style={{ fontSize: 10 }}>{s.position}</span>
            <span style={{ flex: 1 }}>
              {s.name}
              {s.description && <span className="hint muted"> · {s.description}</span>}
            </span>
            <span className="muted">{s.caseCount} case{s.caseCount === 1 ? '' : 's'}</span>
            {canRun && (
              <button className="btn pri" style={{ fontSize: 11 }} disabled={s.caseCount === 0 || create.isPending}
                title={s.caseCount === 0 ? 'A suite with no cases cannot be run' : undefined} onClick={() => { setError(undefined); create.mutate(s) }}>
                New run
              </button>
            )}
          </li>
        ))}
      </ul>
      {error && <p className="err-text" role="alert">{error}</p>}
    </>
  )
}
