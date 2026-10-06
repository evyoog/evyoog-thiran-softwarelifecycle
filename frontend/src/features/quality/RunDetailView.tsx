import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import {
  ApiError, api, type DefectSeverity, type Evidence, type FoundIn, type RunCase, type RunStep, type StepResult,
} from '@/shared/api/client'
import { Modal } from '@/shared/ui/Modal'
import {
  RESULTS, RESULT_CLASS, RESULT_GLYPH, RESULT_LABEL, STATUS_CLASS, STATUS_GLYPH, STATUS_LABEL, isStale, resultNeedsActual,
  runActions, runTitle, summaryText, validateResult,
} from './testRuns'

const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const FOUND_IN: FoundIn[] = ['DEV', 'QA', 'UAT', 'PRODUCTION']

function messageOf(e: unknown): string {
  return e instanceof ApiError ? (e.detail ?? e.title) : e instanceof Error ? e.message : 'Something went wrong.'
}

/**
 * VYB-0927 (F14): one manual run, to read and, for a Tester, to execute: start it, record a result and the actual
 * result on each step (or on a case that has no steps), complete it, retest what failed, raise a defect from a
 * failed step. Planning (plans, suites, steps) and uploading evidence are not here: they stay in the API for now.
 *
 * <p>Nothing here decides what a result means for a requirement: completing a run writes its verification records
 * on the server (VYB-0925), and a requirement's status is never touched. The buttons only show for a Tester or an
 * administrator; the server refuses anyone else with 403 and that message is shown if it happens.
 */
export function RunDetailView({
  runId, canRun, onBack, onOpenRun,
}: { runId: string; canRun: boolean; onBack: () => void; onOpenRun: (id: string) => void }) {
  const qc = useQueryClient()
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['test-run', runId], queryFn: () => api.testRun(runId) })
  const [defectFor, setDefectFor] = useState<{ kind: 'step' | 'case'; id: string; label: string } | null>(null)

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['test-run', runId] })
    void qc.invalidateQueries({ queryKey: ['test-runs'] })
    void qc.invalidateQueries({ queryKey: ['test-plans'] })
    void qc.invalidateQueries({ queryKey: ['pass-rates'] })
    void qc.invalidateQueries({ queryKey: ['evidence-summary'] })
  }
  const start = useMutation({ mutationFn: () => api.startTestRun(runId), onSuccess: refresh })
  const complete = useMutation({ mutationFn: () => api.completeTestRun(runId), onSuccess: refresh })
  const retest = useMutation({
    mutationFn: () => api.retestRun(runId),
    onSuccess: (d) => { refresh(); onOpenRun(d.run.id) },
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) {
    return (
      <div className="empty">
        <h4>Could not load this run</h4>
        <button className="btn" onClick={onBack}>Back to runs</button> <button className="btn" onClick={() => void refetch()}>Try again</button>
      </div>
    )
  }

  const { run, cases, summary } = data
  const actions = runActions(run.status, summary)
  const actionError = [start, complete, retest].map((m) => m.error).find(Boolean)

  return (
    <>
      <button className="btn" style={{ marginBottom: 12 }} onClick={onBack}><ArrowLeft size={14} /> Back to runs</button>

      <div className="card" style={{ marginBottom: 14 }}>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <h3 style={{ margin: 0 }}>{runTitle(run)}</h3>
          <span className={`badge ${STATUS_CLASS[run.status]}`}>
            <span aria-hidden="true">{STATUS_GLYPH[run.status]}</span> {STATUS_LABEL[run.status]}
          </span>
          {run.retestOf && (
            <button className="btn" style={{ fontSize: 11 }} onClick={() => onOpenRun(run.retestOf!)}>Retest of an earlier run</button>
          )}
        </div>
        <div className="hint muted" style={{ marginTop: 6 }}>
          Assigned to {run.assignedToName ?? 'no one'} · created {new Date(run.createdAt).toLocaleString()}
          {run.startedAt && ` · started ${new Date(run.startedAt).toLocaleString()}`}
          {run.completedAt && ` · completed ${new Date(run.completedAt).toLocaleString()}`}
        </div>
        <p role="status" aria-live="polite" style={{ margin: '8px 0 0' }}>{summaryText(summary)}</p>
        {run.status === 'COMPLETED' && (
          <p className="hint muted" style={{ margin: '4px 0 0' }}>
            Completing this run recorded {summary.verificationsRecorded} verification record{summary.verificationsRecorded === 1 ? '' : 's'},
            bound to the revision each requirement had when the run started. A requirement's status is never changed by a run.
          </p>
        )}

        {canRun && (
          <div style={{ display: 'flex', gap: 8, marginTop: 12, alignItems: 'center', flexWrap: 'wrap' }}>
            {actions.canStart && (
              <button className="btn pri" disabled={start.isPending} onClick={() => start.mutate()}>Start run</button>
            )}
            {run.status === 'IN_PROGRESS' && (
              <button className="btn pri" disabled={!actions.canComplete || complete.isPending}
                title={actions.completeBlockedReason} onClick={() => complete.mutate()}>Complete run</button>
            )}
            {actions.canRetest && (
              <button className="btn" disabled={retest.isPending} onClick={() => retest.mutate()}>Retest failed and blocked</button>
            )}
            {actions.completeBlockedReason && <span className="hint muted">{actions.completeBlockedReason}</span>}
            {actions.retestBlockedReason && <span className="hint muted">{actions.retestBlockedReason}</span>}
          </div>
        )}
        {!canRun && (
          <p className="hint muted" style={{ marginTop: 10 }}>Running tests needs the Tester role; you can read this run.</p>
        )}
        {actionError && <p className="err-text" role="alert" style={{ marginTop: 8 }}>{messageOf(actionError)}</p>}
      </div>

      {cases.map((c) => (
        <CaseCard key={c.id} runId={runId} c={c} canRecord={canRun && actions.canRecord}
          canRaise={canRun && run.status !== 'PLANNED'} onChanged={refresh}
          onRaise={(kind, id, label) => setDefectFor({ kind, id, label })} />
      ))}

      {defectFor && (
        <RaiseDefectModal runId={runId} target={defectFor} onClose={() => setDefectFor(null)}
          onRaised={() => { setDefectFor(null); refresh() }} />
      )}
    </>
  )
}

function CaseCard({
  runId, c, canRecord, canRaise, onChanged, onRaise,
}: {
  runId: string; c: RunCase; canRecord: boolean; canRaise: boolean; onChanged: () => void
  onRaise: (kind: 'step' | 'case', id: string, label: string) => void
}) {
  const stepless = c.steps.length === 0
  return (
    <section className="card tr-case" style={{ marginBottom: 12 }} aria-label={`${c.key} ${c.title}`}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="mono muted" style={{ fontSize: 11 }}>{c.key}</span>
        <strong style={{ flex: 1, minWidth: 180 }}>{c.title}</strong>
        <span className={`badge ${RESULT_CLASS[c.result]}`}>
          <span aria-hidden="true">{RESULT_GLYPH[c.result]}</span> {RESULT_LABEL[c.result]}
        </span>
        {c.defectKey && <span className="badge tr-none" title="A defect was raised from this failure">Defect {c.defectKey}</span>}
      </div>
      {c.description && <p className="hint muted" style={{ margin: '6px 0 0', whiteSpace: 'pre-wrap' }}>{c.description}</p>}
      {c.requirements.length > 0 && (
        <div className="hint muted" style={{ marginTop: 6 }}>
          Verifies{' '}
          {c.requirements.map((r) => (
            <span key={r.requirementId} style={{ marginRight: 10 }}>
              <span className="mono">{r.key}</span> at revision {r.testedRevision}
              {isStale(r) && <b> · edited since (now revision {r.currentRevision}): this result is stale for the new text</b>}
            </span>
          ))}
        </div>
      )}

      {stepless ? (
        <div style={{ marginTop: 10 }}>
          <p className="hint muted" style={{ margin: '0 0 6px' }}>This case has no steps, so it is judged as a whole.</p>
          {c.actualResult && <p style={{ margin: '0 0 6px' }}><span className="muted">Actual:</span> {c.actualResult}</p>}
          {c.executedByName && <p className="hint muted" style={{ margin: '0 0 6px' }}>Recorded by {c.executedByName}{c.executedAt && ` · ${new Date(c.executedAt).toLocaleString()}`}</p>}
          <Evidences items={c.evidence} />
          {canRecord && (
            <ResultControls current={c.result === 'NOT_RUN' ? undefined : (c.result as StepResult)} currentActual={c.actualResult}
              label={`case ${c.key}`}
              save={(result, actual) => api.recordCaseResult(runId, c.id, result, actual)} onSaved={onChanged} />
          )}
          {canRaise && c.result === 'FAIL' && !c.defectKey && (
            <button className="btn" style={{ marginTop: 8 }} onClick={() => onRaise('case', c.id, `${c.key} failed`)}>Raise a defect</button>
          )}
        </div>
      ) : (
        <div className="tbl-wrap" style={{ marginTop: 10 }}>
          <table className="tbl" aria-label={`Steps of ${c.key}`}>
            <thead><tr><th>#</th><th>Action</th><th>Expected</th><th>Result</th></tr></thead>
            <tbody>
              {c.steps.map((s) => (
                <StepRow key={s.id} runId={runId} c={c} s={s} canRecord={canRecord} canRaise={canRaise} onChanged={onChanged} onRaise={onRaise} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function StepRow({
  runId, c, s, canRecord, canRaise, onChanged, onRaise,
}: {
  runId: string; c: RunCase; s: RunStep; canRecord: boolean; canRaise: boolean; onChanged: () => void
  onRaise: (kind: 'step' | 'case', id: string, label: string) => void
}) {
  const shown = s.result ?? 'NOT_RUN'
  return (
    <tr style={{ cursor: 'default', verticalAlign: 'top' }}>
      <td className="mono muted">{s.position}</td>
      <td style={{ whiteSpace: 'pre-wrap' }}>{s.action}</td>
      <td style={{ whiteSpace: 'pre-wrap' }}>{s.expectedResult}</td>
      <td>
        <span className={`badge ${RESULT_CLASS[shown]}`}>
          <span aria-hidden="true">{RESULT_GLYPH[shown]}</span> {RESULT_LABEL[shown]}
        </span>
        {s.actualResult && <div style={{ marginTop: 4 }}><span className="muted">Actual:</span> {s.actualResult}</div>}
        {s.executedByName && (
          <div className="hint muted">Recorded by {s.executedByName}{s.executedAt && ` · ${new Date(s.executedAt).toLocaleString()}`}</div>
        )}
        <Evidences items={s.evidence} />
        {s.defectKey && <div className="hint muted" style={{ marginTop: 4 }}>Defect {s.defectKey} raised from this step</div>}
        {canRaise && s.result === 'FAIL' && !s.defectKey && (
          <button className="btn" style={{ marginTop: 6, fontSize: 11 }} onClick={() => onRaise('step', s.id, `${c.key} step ${s.position} failed`)}>
            Raise a defect
          </button>
        )}
        {canRecord && (
          <ResultControls current={s.result} currentActual={s.actualResult} label={`step ${s.position} of ${c.key}`}
            save={(result, actual) => api.recordStepResult(runId, s.id, result, actual)} onSaved={onChanged} />
        )}
      </td>
    </tr>
  )
}

/** Pass saves at once; Fail and Blocked ask what actually happened first, as the server requires. */
function ResultControls({
  current, currentActual, label, save, onSaved,
}: {
  current?: StepResult; currentActual?: string; label: string
  save: (result: StepResult, actual?: string) => Promise<unknown>; onSaved: () => void
}) {
  const [pending, setPending] = useState<StepResult | null>(null)
  const [actual, setActual] = useState(currentActual ?? '')
  const [error, setError] = useState<string | undefined>()
  const mutation = useMutation({
    mutationFn: (v: { result: StepResult; actual?: string }) => save(v.result, v.actual),
    onSuccess: () => { setPending(null); setError(undefined); onSaved() },
    onError: (e) => setError(messageOf(e)),
  })

  const choose = (result: StepResult) => {
    setError(undefined)
    if (resultNeedsActual(result)) { setPending(result); return }
    setPending(null)
    mutation.mutate({ result })
  }
  const submit = () => {
    if (!pending) return
    const problem = validateResult(pending, actual)
    if (problem) { setError(problem); return }
    mutation.mutate({ result: pending, actual: actual.trim() })
  }

  return (
    <div style={{ marginTop: 8 }}>
      <div role="group" aria-label={`Record a result for ${label}`} style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {RESULTS.map((r) => (
          <button key={r} className={`btn${(pending ?? current) === r ? ' pri' : ''}`} style={{ fontSize: 11 }}
            aria-pressed={(pending ?? current) === r} disabled={mutation.isPending} onClick={() => choose(r)}>
            <span aria-hidden="true">{RESULT_GLYPH[r]}</span> {RESULT_LABEL[r]}
          </button>
        ))}
      </div>
      {pending && (
        <div style={{ marginTop: 6 }}>
          <label className="label" htmlFor={`actual-${label}`}>What actually happened ({RESULT_LABEL[pending].toLowerCase()})</label>
          <textarea id={`actual-${label}`} className="textarea" rows={2} style={{ width: '100%' }} value={actual}
            onChange={(e) => setActual(e.target.value)} />
          <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
            <button className="btn pri" style={{ fontSize: 11 }} disabled={mutation.isPending} onClick={submit}>Save result</button>
            <button className="btn" style={{ fontSize: 11 }} onClick={() => { setPending(null); setError(undefined) }}>Cancel</button>
          </div>
        </div>
      )}
      {error && <p className="err-text" role="alert" style={{ margin: '4px 0 0' }}>{error}</p>}
    </div>
  )
}

/** Evidence is stored as an attachment of a requirement the case verifies; here it is listed and can be saved. Adding it stays in the API for now. */
function Evidences({ items }: { items: Evidence[] }) {
  const [error, setError] = useState<string | undefined>()
  if (items.length === 0) return null
  const save = async (e: Evidence) => {
    try {
      const blob = await api.downloadEvidence(e.requirementId, e.attachmentId, e.version)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = e.filename
      a.click()
      URL.revokeObjectURL(url)
      setError(undefined)
    } catch (err) {
      setError(messageOf(err))
    }
  }
  return (
    <div className="hint muted" style={{ marginTop: 4 }}>
      Evidence:{' '}
      {items.map((e) => (
        <button key={e.id} className="btn" style={{ fontSize: 11, marginRight: 6 }} onClick={() => void save(e)}
          title={`Attached to ${e.requirementKey}, version ${e.version}`}>
          {e.filename} (v{e.version})
        </button>
      ))}
      {error && <span className="err-text" role="alert"> {error}</span>}
    </div>
  )
}

/** The defect is prefilled from the failed step; the person may change any of it before it is raised. */
function RaiseDefectModal({
  runId, target, onClose, onRaised,
}: {
  runId: string; target: { kind: 'step' | 'case'; id: string; label: string }; onClose: () => void; onRaised: () => void
}) {
  const { data: draft, isLoading, isError } = useQuery({
    queryKey: ['defect-draft', runId, target.kind, target.id],
    queryFn: () => (target.kind === 'step' ? api.stepDefectDraft(runId, target.id) : api.caseDefectDraft(runId, target.id)),
  })
  const [title, setTitle] = useState<string | undefined>()
  const [severity, setSeverity] = useState<DefectSeverity | undefined>()
  const [foundIn, setFoundIn] = useState<FoundIn | undefined>()
  const [requirementId, setRequirementId] = useState<string | undefined>()
  const [error, setError] = useState<string | undefined>()
  const qc = useQueryClient()

  const raise = useMutation({
    mutationFn: () => {
      const body = {
        title: (title ?? draft!.title).trim(), severity: severity ?? draft!.severity, foundIn: foundIn ?? draft!.foundIn,
        requirementId: requirementId ?? draft!.requirementId,
      }
      return target.kind === 'step' ? api.raiseStepDefect(runId, target.id, body) : api.raiseCaseDefect(runId, target.id, body)
    },
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['defects'] }); onRaised() },
    onError: (e) => setError(messageOf(e)),
  })

  const needsChoice = !!draft && draft.candidates.length > 1 && !(requirementId ?? draft.requirementId)
  return (
    <Modal onClose={onClose} title="Raise a defect">
      <h3>Raise a defect</h3>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">Could not load the draft for {target.label}.</p>}
      {draft && (
        <>
          {draft.existingDefectKey ? (
            <p role="alert">A defect has already been raised from this failure: <b>{draft.existingDefectKey}</b>.</p>
          ) : (
            <>
              <div className="field">
                <label className="label" htmlFor="rd-title">Title</label>
                <input id="rd-title" className="input" value={title ?? draft.title} onChange={(e) => setTitle(e.target.value)} />
              </div>
              <div className="row">
                <div className="field">
                  <label className="label" htmlFor="rd-sev">Severity</label>
                  <select id="rd-sev" className="select" value={severity ?? draft.severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)}>
                    {SEVERITIES.map((v) => <option key={v} value={v}>{v}</option>)}
                  </select>
                </div>
                <div className="field">
                  <label className="label" htmlFor="rd-found">Found in</label>
                  <select id="rd-found" className="select" value={foundIn ?? draft.foundIn} onChange={(e) => setFoundIn(e.target.value as FoundIn)}>
                    {FOUND_IN.map((v) => <option key={v} value={v}>{v}</option>)}
                  </select>
                </div>
              </div>
              {draft.candidates.length > 1 && (
                <div className="field">
                  <label className="label" htmlFor="rd-req">Against requirement</label>
                  <select id="rd-req" className="select" value={requirementId ?? draft.requirementId ?? ''} onChange={(e) => setRequirementId(e.target.value)}>
                    <option value="" disabled>— choose one —</option>
                    {draft.candidates.map((r) => <option key={r.requirementId} value={r.requirementId}>{r.key}</option>)}
                  </select>
                  <span className="hint">This test case verifies several requirements.</span>
                </div>
              )}
              {draft.candidates.length === 1 && <p className="hint muted">Against <span className="mono">{draft.candidates[0].key}</span>.</p>}
              {draft.candidates.length === 0 && <p className="hint muted">This test case verified no requirement when the run started, so the defect will be untraced.</p>}
            </>
          )}

          <div className="hint muted" style={{ borderTop: '1px solid var(--line)', paddingTop: 8, marginTop: 4 }}>
            <div>Test <span className="mono">{draft.testKey}</span> · {draft.testTitle}</div>
            {draft.action && <div>Step {draft.stepPosition}: {draft.action}</div>}
            {draft.expectedResult && <div>Expected: {draft.expectedResult}</div>}
            {draft.actualResult && <div>Actual: {draft.actualResult}</div>}
            <div>{[draft.planName, draft.suiteName, draft.buildLabel].filter(Boolean).join(' / ')}</div>
          </div>
          {error && <p className="err-text" role="alert">{error}</p>}
          <div className="modal-actions" style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
            <button className="btn" onClick={onClose}>{draft.existingDefectKey ? 'Close' : 'Cancel'}</button>
            {!draft.existingDefectKey && (
              <button className="btn pri" disabled={raise.isPending || needsChoice || (title ?? draft.title).trim() === ''}
                onClick={() => raise.mutate()}>Raise defect</button>
            )}
          </div>
        </>
      )}
    </Modal>
  )
}
