import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { X } from 'lucide-react'
import {
  ApiError, api, type Defect, type DefectDetail, type DefectSeverity, type FoundIn, type Me, type RootCause,
} from '@/shared/api/client'
import { UserPicker } from '@/shared/ui/UserPicker'
import {
  STATE_CLASS, STATE_GLYPH, STATE_LABEL, assigneeText, commentProblem, defectActions, linkText, reopenProblem, transitionText,
} from './defects'
import { runTitle } from './testRuns'

const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const FOUND_IN: FoundIn[] = ['DEV', 'QA', 'UAT', 'PRODUCTION']
const ROOT_CAUSES: RootCause[] = ['REQUIREMENT_AMBIGUITY', 'REQUIREMENT_OMISSION', 'CODING_ERROR', 'ENVIRONMENT', 'DATA', 'UNKNOWN']

function messageOf(e: unknown): string {
  return e instanceof ApiError ? (e.detail ?? e.title) : e instanceof Error ? e.message : 'Something went wrong.'
}

type Panel = 'reopen' | 'edit' | 'assign' | 'links' | null

/**
 * VYB-0931 (F15): one defect: its state and the history of its moves, who it is routed to, what it is linked to, and the
 * conversation about it. A defect's assigned developer marks it fixed; a Tester closes, reopens, edits, assigns and
 * links it (the buttons show only for those who may; the server enforces the same and its refusal is shown in words);
 * anyone signed in can comment. Comments are never edited or deleted.
 */
export function DefectDetailPanel({ defectId, me, onClose }: { defectId: string; me: Me | undefined; onClose: () => void }) {
  const qc = useQueryClient()
  const { data, isLoading, isError } = useQuery({ queryKey: ['defect-detail', defectId], queryFn: () => api.defect(defectId) })
  const [panel, setPanel] = useState<Panel>(null)
  const [error, setError] = useState<string | undefined>()

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['defect-detail', defectId] })
    void qc.invalidateQueries({ queryKey: ['defects'] })
    void qc.invalidateQueries({ queryKey: ['defect-split'] })
    void qc.invalidateQueries({ queryKey: ['defect-comments', defectId] })
  }
  const done = () => { setPanel(null); setError(undefined); refresh() }
  const fail = (e: unknown) => setError(messageOf(e))

  const fix = useMutation({ mutationFn: () => api.fixDefect(defectId), onSuccess: done, onError: fail })
  const close = useMutation({ mutationFn: () => api.closeDefect(defectId), onSuccess: done, onError: fail })
  const classify = useMutation({ mutationFn: (rc: RootCause) => api.classifyDefect(defectId, rc), onSuccess: done, onError: fail })

  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (isError || !data) {
    return (
      <div className="card"><p>Could not load this defect.</p><button className="btn" onClick={onClose}>Close</button></div>
    )
  }
  const d = data.defect
  const actions = defectActions(d, me)

  return (
    <section className="card" aria-label={`Defect ${d.key}`}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="mono muted">{d.key}</span>
        <strong style={{ flex: 1, minWidth: 160 }}>{d.title}</strong>
        <span className={`badge ${STATE_CLASS[d.state]}`}><span aria-hidden="true">{STATE_GLYPH[d.state]}</span> {STATE_LABEL[d.state]}</span>
        <button className="btn" style={{ padding: '0 6px' }} aria-label="Close this panel" onClick={onClose}><X size={14} /></button>
      </div>
      <p className="hint muted" style={{ margin: '6px 0' }}>
        {d.severity.toLowerCase()} severity · found in {d.foundIn.toLowerCase()} ·{' '}
        {d.untraced ? 'untraced' : <>against <span className="mono">{d.requirementKey ?? 'a requirement'}</span>{data.requirementTitle ? ` · ${data.requirementTitle}` : ''}</>}
        {' '}· {assigneeText(d)}
      </p>
      <Links data={data} />

      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', margin: '10px 0' }}>
        {actions.fix && <button className="btn pri" disabled={fix.isPending} onClick={() => fix.mutate()}>Mark fixed</button>}
        {(actions.close || actions.closeBlockedReason) && (
          <button className="btn" disabled={!actions.close || close.isPending} title={actions.closeBlockedReason} onClick={() => close.mutate()}>Close</button>
        )}
        {actions.reopen && <button className="btn" onClick={() => setPanel(panel === 'reopen' ? null : 'reopen')}>Reopen</button>}
        {actions.edit && <button className="btn" onClick={() => setPanel(panel === 'edit' ? null : 'edit')}>Edit</button>}
        {actions.assign && <button className="btn" onClick={() => setPanel(panel === 'assign' ? null : 'assign')}>Assign</button>}
        {actions.link && <button className="btn" onClick={() => setPanel(panel === 'links' ? null : 'links')}>Links</button>}
      </div>
      {actions.closeBlockedReason && <p className="hint muted" style={{ margin: '0 0 8px' }}>{actions.closeBlockedReason}</p>}
      {error && <p className="err-text" role="alert">{error}</p>}

      {actions.assign ? (  // classifying is the Tester's, at any state, like closing
        <div className="field" style={{ maxWidth: 280 }}>
          <label className="label" htmlFor={`rc-${d.id}`}>Root cause</label>
          <select id={`rc-${d.id}`} className="select" value={d.rootCause ?? ''} onChange={(e) => classify.mutate(e.target.value as RootCause)}>
            <option value="" disabled>— classify —</option>
            {ROOT_CAUSES.map((rc) => <option key={rc} value={rc}>{rc.replace(/_/g, ' ').toLowerCase()}</option>)}
          </select>
        </div>
      ) : (d.rootCause ? <p className="hint muted">Root cause: {d.rootCause.replace(/_/g, ' ').toLowerCase()}</p> : null)}

      {panel === 'reopen' && <ReopenForm id={d.id} onDone={done} onError={fail} />}
      {panel === 'edit' && <EditForm d={d} onDone={done} onError={fail} />}
      {panel === 'assign' && <AssignForm d={d} onDone={done} onError={fail} />}
      {panel === 'links' && <LinksForm data={data} onDone={done} onError={fail} />}

      <h4 className="section-h" style={{ marginTop: 12 }}>History</h4>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        <li className="hint muted">Raised {new Date(d.raisedAt).toLocaleString()}</li>
        {data.transitions.map((t) => (
          <li key={t.id} className="hint">{transitionText(t)} <span className="muted">· {new Date(t.changedAt).toLocaleString()}</span></li>
        ))}
      </ul>

      <Comments defectId={d.id} canComment={actions.comment} />
    </section>
  )
}

function Links({ data }: { data: DefectDetail }) {
  return (
    <p className="hint muted" style={{ margin: '0 0 4px' }}>
      Test: {linkText(data.testCase?.label ?? data.raisedFromTestKey, 'test')}
      {' · '}Run: {linkText(data.testRun?.label, 'run')}
      {data.raisedFromRunId && ' (raised from a failed step of a run)'}
      {' · '}Release: {linkText(data.release?.label, 'release')}
    </p>
  )
}

function ReopenForm({ id, onDone, onError }: { id: string; onDone: () => void; onError: (e: unknown) => void }) {
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | undefined>()
  const reopen = useMutation({ mutationFn: () => api.reopenDefect(id, reason.trim()), onSuccess: onDone, onError })
  return (
    <div style={{ marginBottom: 10 }}>
      <label className="label" htmlFor={`reopen-${id}`}>Why is it being reopened? (required)</label>
      <input id={`reopen-${id}`} className="input" style={{ width: '100%' }} value={reason} onChange={(e) => { setReason(e.target.value); setProblem(undefined) }} />
      {problem && <p className="err-text" role="alert">{problem}</p>}
      <button className="btn pri" style={{ marginTop: 6 }} disabled={reopen.isPending}
        onClick={() => { const p = reopenProblem(reason); if (p) setProblem(p); else reopen.mutate() }}>Reopen defect</button>
    </div>
  )
}

function EditForm({ d, onDone, onError }: { d: Defect; onDone: () => void; onError: (e: unknown) => void }) {
  const [title, setTitle] = useState(d.title)
  const [severity, setSeverity] = useState<DefectSeverity>(d.severity)
  const [foundIn, setFoundIn] = useState<FoundIn>(d.foundIn)
  const edit = useMutation({ mutationFn: () => api.editDefect(d.id, { title: title.trim(), severity, foundIn }), onSuccess: onDone, onError })
  return (
    <div style={{ marginBottom: 10 }}>
      <div className="field"><label className="label" htmlFor={`et-${d.id}`}>Title</label>
        <input id={`et-${d.id}`} className="input" value={title} onChange={(e) => setTitle(e.target.value)} /></div>
      <div className="row">
        <div className="field"><label className="label" htmlFor={`es-${d.id}`}>Severity</label>
          <select id={`es-${d.id}`} className="select" value={severity} onChange={(e) => setSeverity(e.target.value as DefectSeverity)}>
            {SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}</select></div>
        <div className="field"><label className="label" htmlFor={`ef-${d.id}`}>Found in</label>
          <select id={`ef-${d.id}`} className="select" value={foundIn} onChange={(e) => setFoundIn(e.target.value as FoundIn)}>
            {FOUND_IN.map((f) => <option key={f} value={f}>{f}</option>)}</select></div>
      </div>
      <button className="btn pri" disabled={!title.trim() || edit.isPending} onClick={() => edit.mutate()}>Save changes</button>
    </div>
  )
}

function AssignForm({ d, onDone, onError }: { d: Defect; onDone: () => void; onError: (e: unknown) => void }) {
  const [developer, setDeveloper] = useState(d.developerId ?? '')
  const [tester, setTester] = useState(d.testerId ?? '')
  const assign = useMutation({ mutationFn: () => api.assignDefect(d.id, developer || undefined, tester || undefined), onSuccess: onDone, onError })
  return (
    <div style={{ marginBottom: 10 }}>
      <p className="hint muted">Currently: {assigneeText(d)}. A person newly assigned is told; saving replaces both.</p>
      <div className="field">
        <label className="label">Developer</label>
        <UserPicker value={developer} onSelect={setDeveloper} placeholder="Search people" />
        {developer && <button className="btn" style={{ fontSize: 11, marginTop: 4 }} onClick={() => setDeveloper('')}>No developer</button>}
      </div>
      <div className="field">
        <label className="label">Tester</label>
        <UserPicker value={tester} onSelect={setTester} placeholder="Search people" />
        {tester && <button className="btn" style={{ fontSize: 11, marginTop: 4 }} onClick={() => setTester('')}>No tester</button>}
      </div>
      <button className="btn pri" disabled={assign.isPending} onClick={() => assign.mutate()}>Save assignment</button>
    </div>
  )
}

function LinksForm({ data, onDone, onError }: { data: DefectDetail; onDone: () => void; onError: (e: unknown) => void }) {
  const [testCaseId, setTestCaseId] = useState(data.testCase?.id ?? '')
  const [runId, setRunId] = useState(data.testRun?.id ?? '')
  const [releaseId, setReleaseId] = useState(data.release?.id ?? '')
  const [q, setQ] = useState('')
  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const { data: runs } = useQuery({ queryKey: ['test-runs', ''], queryFn: () => api.testRuns() })
  const { data: cases } = useQuery({ queryKey: ['defect-link-cases', q], queryFn: () => api.testCases({ q: q.trim() || undefined, size: 10 }) })
  const link = useMutation({
    mutationFn: () => api.linkDefect(data.defect.id, testCaseId || undefined, runId || undefined, releaseId || undefined),
    onSuccess: onDone, onError,
  })
  return (
    <div style={{ marginBottom: 10 }}>
      <p className="hint muted">One test case, one run and one release at most; saving replaces all three.</p>
      <div className="field">
        <label className="label" htmlFor="lk-case-q">Test case (search, then choose)</label>
        <input id="lk-case-q" className="input" placeholder="Key or title" value={q} onChange={(e) => setQ(e.target.value)} />
        <select className="select" aria-label="Test case" value={testCaseId} onChange={(e) => setTestCaseId(e.target.value)}>
          <option value="">{data.testCase && !testCaseId ? 'No test case' : '— none —'}</option>
          {data.testCase && <option value={data.testCase.id}>{data.testCase.label}</option>}
          {cases?.content.filter((c) => c.id !== data.testCase?.id).map((c) => <option key={c.id} value={c.id}>{c.key} {c.title}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="label" htmlFor="lk-run">Test run</label>
        <select id="lk-run" className="select" value={runId} onChange={(e) => setRunId(e.target.value)}>
          <option value="">— none —</option>
          {data.testRun && !runs?.some((r) => r.id === data.testRun!.id) && <option value={data.testRun.id}>{data.testRun.label}</option>}
          {runs?.map((r) => <option key={r.id} value={r.id}>{runTitle(r)}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="label" htmlFor="lk-release">Release</label>
        <select id="lk-release" className="select" value={releaseId} onChange={(e) => setReleaseId(e.target.value)}>
          <option value="">— none —</option>
          {releases?.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.state})</option>)}
        </select>
      </div>
      <button className="btn pri" disabled={link.isPending} onClick={() => link.mutate()}>Save links</button>
    </div>
  )
}

function Comments({ defectId, canComment }: { defectId: string; canComment: boolean }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['defect-comments', defectId], queryFn: () => api.defectComments(defectId) })
  const [body, setBody] = useState('')
  const [problem, setProblem] = useState<string | undefined>()
  const send = useMutation({
    mutationFn: () => api.commentOnDefect(defectId, body.trim()),
    onSuccess: () => { setBody(''); setProblem(undefined); void qc.invalidateQueries({ queryKey: ['defect-comments', defectId] }) },
    onError: (e) => setProblem(messageOf(e)),
  })
  return (
    <div>
      <h4 className="section-h" style={{ marginTop: 12 }}>Comments</h4>
      {data && data.length === 0 && <p className="hint muted">No comments yet.</p>}
      {data?.map((c) => (
        <div key={c.id} className="list-item" style={{ display: 'block' }}>
          <div className="hint muted">{c.authorName ?? 'someone'} · {new Date(c.createdAt).toLocaleString()}</div>
          <div style={{ whiteSpace: 'pre-wrap' }}>{c.body}</div>
        </div>
      ))}
      {canComment && (
        <div style={{ marginTop: 8 }}>
          <label className="label" htmlFor={`cm-${defectId}`}>Add a comment</label>
          <textarea id={`cm-${defectId}`} className="textarea" rows={2} style={{ width: '100%' }} value={body}
            onChange={(e) => { setBody(e.target.value); setProblem(undefined) }} />
          {problem && <p className="err-text" role="alert">{problem}</p>}
          <button className="btn pri" style={{ marginTop: 6 }} disabled={send.isPending}
            onClick={() => { const p = commentProblem(body); if (p) setProblem(p); else send.mutate() }}>Comment</button>
          <span className="hint muted"> Comments are kept as written and cannot be edited or deleted.</span>
        </div>
      )}
    </div>
  )
}
