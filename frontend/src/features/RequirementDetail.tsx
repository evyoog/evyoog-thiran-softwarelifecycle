import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type ButtonHTMLAttributes } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Plus, Trash2, ArrowUp, ArrowDown, RefreshCw, Paperclip, Download, AlertTriangle, Bug, Edit2, History } from 'lucide-react'
import {
  api, ApiError, type DefectSeverity, type FoundIn, type RequirementPriority, type RequirementStatus,
  type RequirementType, type TraceLinkType,
} from '@/shared/api/client'
import { CapabilityPicker } from '@/shared/ui/CapabilityPicker'
import { StatusBadge, PriorityBadge, CoveragePips } from '@/shared/ui/Badges'
import { Modal } from '@/shared/ui/Modal'
import { UserPicker } from '@/shared/ui/UserPicker'

const TYPES: RequirementType[] = [
  'FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE', 'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE',
]
const PRIORITIES: RequirementPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const LINK_TYPES: TraceLinkType[] = ['SATISFIES', 'DERIVES', 'VERIFIES', 'IMPLEMENTS', 'REFINES', 'CONFLICTS']
const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const FOUND_IN: FoundIn[] = ['DEV', 'QA', 'UAT', 'PRODUCTION']

/**
 * What each move is called, keyed by where it comes from as well as where it goes.
 *
 * <p>Keying on the target alone would get "reopen" wrong: NEEDS_REVISION is reached
 * both by a decision maker sending REVIEWED back for changes, and by reopening a
 * REJECTED requirement — two different actions that both land on the same status.
 */
const MOVE_LABEL: Record<string, { button: string; sentence: string }> = {
  'DRAFT>IN_REVIEW':          { button: 'Submit for review',   sentence: 'submit this for review' },
  // VYB-0813 (D17): the manual review gate. The same decision maker who used to click
  // "Approve" straight from IN_REVIEW now clicks this first, then a separate decision
  // below — two actions, still one role, per the human-decides rule (no AI review yet).
  'IN_REVIEW>REVIEWED':       { button: 'Move to Reviewed',     sentence: 'mark this reviewed' },
  'IN_REVIEW>DRAFT':          { button: 'Withdraw to draft',    sentence: 'withdraw this back to draft' },
  'REVIEWED>APPROVED':        { button: 'Approve',              sentence: 'approve this' },
  'REVIEWED>REJECTED':        { button: 'Reject',               sentence: 'reject this' },
  'REVIEWED>NEEDS_REVISION':  { button: 'Send back for revision', sentence: 'send this back for revision' },
  'NEEDS_REVISION>IN_REVIEW': { button: 'Resubmit',             sentence: 'resubmit this for review' },
  // VYB-0813: a rejection reopens into NEEDS_REVISION, not DRAFT — the rejection
  // already carries the reason the author needs to act on.
  'REJECTED>NEEDS_REVISION':  { button: 'Reopen',               sentence: 'reopen this into needs revision' },
}

function moveLabel(from: string, target: string) {
  return MOVE_LABEL[`${from}>${target}`] ?? { button: target, sentence: `move this to ${target}` }
}

/**
 * VYB-0813 (D17): a full replacement of the prior five-state machine, not an
 * extension of it. Rejecting directly from IN_REVIEW is gone; NEEDS_REVISION is new;
 * REJECTED reopens into NEEDS_REVISION rather than DRAFT; APPROVED is fully terminal —
 * no move leaves it (editing an approved requirement is refused, and forking a new
 * version on such an edit is a deferred follow-up, not built yet).
 */
const NEXT_STATES: Record<RequirementStatus, RequirementStatus[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['DRAFT', 'REVIEWED'],
  REVIEWED: ['APPROVED', 'REJECTED', 'NEEDS_REVISION'],
  NEEDS_REVISION: ['IN_REVIEW'],
  APPROVED: [],
  REJECTED: ['NEEDS_REVISION'],
}

/**
 * Module scope, not inside the component. Two reasons: the modals at the foot of this file
 * are separate components and could not see a definition nested in RequirementDetail — and
 * a component defined inside another is a new function identity on every render, so React
 * unmounts and remounts it each time. Any input inside one loses focus mid-keystroke.
 */
function Btn({ primary, className, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { primary?: boolean }) {
  return (
    <button
      {...props}
      className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-md text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap ${
        primary
          ? 'bg-brand text-on-brand hover:bg-brand-hi hover:shadow-md active:scale-[0.98]'
          : 'bg-panel-3 text-tx border border-line-2 hover:bg-panel-3 hover:border-line-3 active:scale-[0.98]'
      } ${className ?? ''}`}
    />
  )
}

function IconBtn({ danger, className, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { danger?: boolean }) {
  return (
    <button
      {...props}
      className={`p-1.5 rounded hover:bg-panel-3 text-tx-3 transition-all disabled:opacity-30 disabled:cursor-not-allowed ${
        danger ? 'hover:bg-crit-dim hover:text-crit' : ''
      } ${className ?? ''}`}
    />
  )
}

export function RequirementDetail() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const { data: req, isLoading } = useQuery({
    queryKey: ['requirement', id],
    queryFn: () => api.requirement(id),
  })
  const { data: criteria } = useQuery({
    queryKey: ['acceptance-criteria', id],
    queryFn: () => api.acceptanceCriteria(id),
  })
  const { data: links } = useQuery({
    queryKey: ['trace-links', id],
    queryFn: () => api.links('REQUIREMENT', id),
  })
  const { data: comments } = useQuery({
    queryKey: ['comments', id],
    queryFn: () => api.comments(id),
  })
  const { data: attachments } = useQuery({
    queryKey: ['attachments', id],
    queryFn: () => api.attachments(id),
  })
  const { data: clarifications } = useQuery({
    queryKey: ['clarifications', id],
    queryFn: () => api.clarifications(id),
  })
  const { data: lifecycle } = useQuery({
    queryKey: ['lifecycle', id],
    queryFn: () => api.lifecycle(id),
  })

  const invalidateReq = () => {
    void qc.invalidateQueries({ queryKey: ['requirement', id] })
    void qc.invalidateQueries({ queryKey: ['requirements'] })
  }

  const [title, setTitle] = useState('')
  const [statement, setStatement] = useState('')
  const [type, setType] = useState<RequirementType>('FUNCTIONAL')
  const [priority, setPriority] = useState<RequirementPriority>('MEDIUM')
  const [conflict, setConflict] = useState<{ attempted: number; current: number } | null>(null)
  const [mergeDraft, setMergeDraft] = useState('')
  const { data: theirs } = useQuery({
    queryKey: ['requirement-conflict', id, conflict?.current],
    queryFn: () => api.requirement(id),
    enabled: !!conflict,
  })
  const [changeRequestId, setChangeRequestId] = useState('')

  useEffect(() => {
    if (req) {
      setTitle(req.title); setStatement(req.statement); setType(req.type); setPriority(req.priority)
    }
  }, [req?.id, req?.revision])

  const [capabilityId, setCapabilityId] = useState<string | null>('')
  const capabilityChanged = capabilityId !== ''
  const dirty = !!req && (title !== req.title || statement !== req.statement
    || type !== req.type || priority !== req.priority || capabilityChanged)
  const locked = req?.status === 'APPROVED'

  const save = useMutation({
    mutationFn: () => {
      if (!req) throw new Error('not loaded')
      return api.updateRequirement(id, {
        revision: req.revision, title, statement, type, priority,
        capabilityId: capabilityChanged ? (capabilityId ?? undefined) : req.capabilityId,
      }, locked ? changeRequestId.trim() || undefined : undefined)
    },
    onSuccess: () => { setConflict(null); invalidateReq() },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setConflict({
          attempted: Number(e.extra.attemptedRevision), current: Number(e.extra.currentRevision),
        })
      }
    },
  })

  const resolveConflict = useMutation({
    mutationFn: (resolved: { title: string; statement: string; type: RequirementType; priority: RequirementPriority }) => {
      if (!theirs || !conflict) throw new Error('nothing to resolve')
      return api.updateRequirement(id, { revision: conflict.current, ...resolved, capabilityId: theirs.capabilityId })
    },
    onSuccess: () => { setConflict(null); invalidateReq() },
  })

  const transition = useMutation({
    mutationFn: ({ target, reason }: { target: RequirementStatus; reason?: string }) => {
      if (!req) throw new Error('not loaded')
      return api.transitionRequirement(id, req.revision, target, reason)
    },
    onSuccess: invalidateReq,
  })
  const [confirmingTransition, setConfirmingTransition] = useState<RequirementStatus | null>(null)
  const [showVerifyChoice, setShowVerifyChoice] = useState(false)

  const addCriterion = useMutation({
    mutationFn: (text: string) => api.addAcceptanceCriterion(id, text),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['acceptance-criteria', id] }),
  })
  const removeCriterion = useMutation({
    mutationFn: (criterionId: string) => api.removeAcceptanceCriterion(criterionId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['acceptance-criteria', id] }),
  })
  const reorderCriteria = useMutation({
    mutationFn: (orderedIds: string[]) => api.reorderAcceptanceCriteria(id, orderedIds),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['acceptance-criteria', id] }),
  })
  const editCriterion = useMutation({
    mutationFn: ({ criterionId, text }: { criterionId: string; text: string }) => api.editAcceptanceCriterion(criterionId, text),
    onSuccess: () => { setEditingCriterionId(null); void qc.invalidateQueries({ queryKey: ['acceptance-criteria', id] }) },
  })
  const [editingCriterionId, setEditingCriterionId] = useState<string | null>(null)
  const [editCriterionDraft, setEditCriterionDraft] = useState('')

  const [newCriterion, setNewCriterion] = useState('')
  const [linkTargetId, setLinkTargetId] = useState('')
  const [linkDirection, setLinkDirection] = useState<'upstream' | 'downstream'>('upstream')
  const [linkType, setLinkType] = useState<TraceLinkType>('SATISFIES')

  const createLink = useMutation({
    mutationFn: () => api.createLink(
      linkDirection === 'upstream'
        ? { fromType: 'REQUIREMENT', fromId: linkTargetId, toType: 'REQUIREMENT', toId: id, linkType }
        : { fromType: 'REQUIREMENT', fromId: id, toType: 'REQUIREMENT', toId: linkTargetId, linkType },
    ),
    onSuccess: () => { setLinkTargetId(''); void qc.invalidateQueries({ queryKey: ['trace-links', id] }) },
  })
  const deleteLink = useMutation({
    mutationFn: (linkId: string) => api.deleteLink(linkId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['trace-links', id] }),
  })
  const reviewLink = useMutation({
    mutationFn: (linkId: string) => api.reviewLink(linkId),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['trace-links', id] }),
  })

  const [newComment, setNewComment] = useState('')
  const addComment = useMutation({
    mutationFn: (body: string) => api.addComment(id, body),
    onSuccess: () => { setNewComment(''); void qc.invalidateQueries({ queryKey: ['comments', id] }) },
  })

  const [newClarification, setNewClarification] = useState('')
  const [clarificationAssignee, setClarificationAssignee] = useState('')
  const [clarificationBlocks, setClarificationBlocks] = useState(true)
  const raiseClarification = useMutation({
    mutationFn: () => api.raiseClarification(id, {
      question: newClarification, assignedTo: clarificationAssignee.trim() || undefined, blocksTask: clarificationBlocks,
    }),
    onSuccess: () => {
      setNewClarification(''); setClarificationAssignee('')
      void qc.invalidateQueries({ queryKey: ['clarifications', id] })
    },
  })
  const [answerDrafts, setAnswerDrafts] = useState<Record<string, string>>({})
  const [raiseCrOnAnswer, setRaiseCrOnAnswer] = useState<Record<string, boolean>>({})
  const answerClarification = useMutation({
    mutationFn: ({ clarificationId, answer, asChangeRequest }: { clarificationId: string; answer: string; asChangeRequest: boolean }) =>
      api.answerClarification(clarificationId, {
        answer,
        changeRequestTitle: asChangeRequest ? `Change from clarification on ${req?.key}` : undefined,
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['clarifications', id] }),
  })

  const [showRaiseDefect, setShowRaiseDefect] = useState(false)
  const [defectTitle, setDefectTitle] = useState('')
  const [defectSeverity, setDefectSeverity] = useState<DefectSeverity>('MEDIUM')
  const [defectFoundIn, setDefectFoundIn] = useState<FoundIn>('QA')
  const raiseDefect = useMutation({
    mutationFn: () => api.raiseDefect({ title: defectTitle, severity: defectSeverity, foundIn: defectFoundIn, requirementId: id }),
    onSuccess: () => { setDefectTitle(''); setShowRaiseDefect(false) },
  })

  const fileInput = useRef<HTMLInputElement>(null)
  const [downloadError, setDownloadError] = useState<string | null>(null)
  const [expandedAttachmentId, setExpandedAttachmentId] = useState<string | null>(null)
  const uploadAttachment = useMutation({
    mutationFn: (file: File) => api.uploadAttachment(id, file),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['attachments', id] }),
  })
  const { data: attachmentVersions } = useQuery({
    queryKey: ['attachment-versions', id, expandedAttachmentId],
    queryFn: () => api.attachmentVersions(id, expandedAttachmentId as string),
    enabled: !!expandedAttachmentId,
  })
  const downloadFile = async (attachmentId: string, filename: string, version?: number) => {
    setDownloadError(null)
    try {
      const blob = await api.downloadAttachment(id, attachmentId, version)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = filename; a.click()
      URL.revokeObjectURL(url)
    } catch {
      setDownloadError('Could not download that file — it may not be reachable from here yet.')
    }
  }

  if (isLoading || !req) return (
    <div className="flex flex-col items-center justify-center min-h-[400px] gap-4">
      <div className="w-10 h-10 border-2 border-line border-t-brand rounded-full animate-spin" />
      <p className="text-tx-3 text-sm">Loading requirement...</p>
    </div>
  )

  const moveCriterion = (index: number, dir: -1 | 1) => {
    if (!criteria) return
    const next = [...criteria]
    const swapWith = index + dir
    if (swapWith < 0 || swapWith >= next.length) return
    ;[next[index], next[swapWith]] = [next[swapWith], next[index]]
    reorderCriteria.mutate(next.map((c) => c.id))
  }

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-8 py-6">
      {/* Header */}
      <header className="border-b border-line pb-5 mb-2">
        <div className="flex flex-wrap justify-between items-start gap-4">
          <div className="flex items-center gap-3 min-w-0 flex-1">
            <button onClick={() => navigate('/requirements')} className="text-tx-3 hover:text-tx hover:bg-panel-3 px-2 py-1 rounded transition whitespace-nowrap text-sm">
              ← Back
            </button>
            <div className="flex flex-col min-w-0">
              <span className="font-mono text-xs font-medium text-brand tracking-wide">{req.key}</span>
              <h1 className="text-2xl font-semibold text-tx leading-tight break-words">{req.title}</h1>
            </div>
          </div>
          <div className="flex items-center gap-3 flex-shrink-0 flex-wrap">
            <div className="flex items-center gap-2">
              <StatusBadge status={req.status} />
              <PriorityBadge priority={req.priority} />
              <CoveragePips req={req} />
            </div>
            <Btn primary onClick={() => setShowRaiseDefect(true)}>
              <Bug size={16} /> Raise Defect
            </Btn>
          </div>
        </div>
        <p className="text-xs text-tx-3 mt-2">Editing bumps the revision only when content is materially different.</p>
      </header>

      {/* Conflict Banner */}
      {conflict && (
        <div className="bg-high-dim border border-high-bd rounded-lg p-4 mb-5">
          <div className="flex items-center gap-2 text-high font-medium">
            <AlertTriangle size={20} /> Conflict Detected
          </div>
          <p className="text-sm text-tx-2 mt-1 mb-3">
            You were editing revision {conflict.attempted}; current is {conflict.current}. Both versions shown below.
          </p>
          {theirs && (
            <>
              <div className="grid md:grid-cols-2 gap-3 mb-3">
                <div className="bg-panel p-3 rounded border border-brand-bd">
                  <div className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Yours</div>
                  <p className="text-sm whitespace-pre-wrap mt-1">{statement}</p>
                </div>
                <div className="bg-panel p-3 rounded border border-line-2">
                  <div className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Theirs (rev {conflict.current})</div>
                  <p className="text-sm whitespace-pre-wrap mt-1">{theirs.statement}</p>
                </div>
              </div>
              <div className="flex flex-wrap gap-2">
                <Btn onClick={() => resolveConflict.mutate({ title, statement, type, priority })}>Keep Mine</Btn>
                <Btn onClick={() => { setTitle(theirs.title); setStatement(theirs.statement); setType(theirs.type); setPriority(theirs.priority); setConflict(null); invalidateReq() }}>
                  Keep Theirs
                </Btn>
                <Btn onClick={() => setMergeDraft(`${statement}\n\n--- their version (revision ${conflict.current}) ---\n${theirs.statement}`)}>
                  Start Merge
                </Btn>
              </div>
              {mergeDraft && (
                <div className="mt-3 pt-3 border-t border-line">
                  <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Merged Statement</label>
                  <textarea className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition resize-y" rows={6} value={mergeDraft} onChange={(e) => setMergeDraft(e.target.value)} />
                  <Btn primary className="mt-2" onClick={() => resolveConflict.mutate({ title, statement: mergeDraft, type, priority })}>
                    Save Merged
                  </Btn>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Locked Banner */}
      {locked && (
        <div className="bg-panel-2 border border-line border-l-4 border-l-high rounded-lg p-4 mb-5">
          <div className="flex items-center gap-2 text-high font-medium">
            <AlertTriangle size={20} /> Approved — Change Request Required
          </div>
          <p className="text-sm text-tx-2 mt-1">Content on approved/verified requirements only changes through an approved change request. Paste its ID below before saving.</p>
          <input className="w-full mt-2 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" placeholder="Approved change request ID" value={changeRequestId} onChange={(e) => setChangeRequestId(e.target.value)} />
        </div>
      )}

      {/* Main Grid */}
      <div className="grid lg:grid-cols-3 gap-8 mt-6">
        <div className="lg:col-span-2 space-y-8">
          {/* Requirement */}
          <section className="border-b border-line pb-6">
            <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-4 border-b border-line">Requirement</h2>
            <div className="space-y-3">
              <div className="grid sm:grid-cols-2 gap-3">
                <div>
                  <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Type</label>
                  <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={type} onChange={(e) => setType(e.target.value as RequirementType)}>
                    {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
                <div>
                  <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Priority</label>
                  <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={priority} onChange={(e) => setPriority(e.target.value as RequirementPriority)}>
                    {PRIORITIES.map((p) => <option key={p} value={p}>{p}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Title</label>
                <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={title} onChange={(e) => setTitle(e.target.value)} />
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Statement</label>
                <textarea className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition resize-y" rows={4} value={statement} onChange={(e) => setStatement(e.target.value)} />
              </div>
            </div>
            <div className="flex items-center gap-3 mt-4">
              <Btn primary disabled={!dirty || save.isPending || (locked && !changeRequestId.trim())} onClick={() => save.mutate()}>
                Save {dirty && `(rev ${req.revision} → ${req.revision + 1})`}
              </Btn>
              {save.isError && !conflict && <span className="text-sm text-crit">Could not save{locked ? ' — check change request' : ''}.</span>}
            </div>
          </section>

          {/* Acceptance Criteria */}
          <section className="border-b border-line pb-6">
            <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-4 border-b border-line">Acceptance Criteria</h2>
            {criteria?.length === 0 && (
              <div className="text-center py-8 bg-panel-2 rounded-lg border border-dashed border-line-2">
                <p className="font-medium text-tx-2 text-sm">No acceptance criteria</p>
                <p className="text-sm text-tx-3">Add at least one criterion.</p>
              </div>
            )}
            {criteria?.map((c, i) => (
              <div key={c.id} className={`flex items-start gap-2 p-2 rounded hover:bg-panel-2 ${editingCriterionId === c.id ? 'bg-panel-2' : ''}`}>
                <span className="font-mono text-xs text-tx-3 pt-0.5 min-w-[24px]">{c.ordinal}</span>
                {editingCriterionId === c.id ? (
                  <div className="flex-1 space-y-2">
                    <textarea className="w-full p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition resize-y" rows={2} value={editCriterionDraft} onChange={(e) => setEditCriterionDraft(e.target.value)} />
                    <div className="flex gap-2">
                      <Btn primary onClick={() => editCriterion.mutate({ criterionId: c.id, text: editCriterionDraft })}>Save</Btn>
                      <Btn onClick={() => setEditingCriterionId(null)}>Cancel</Btn>
                    </div>
                  </div>
                ) : (
                  <div className="flex-1 flex items-center gap-2">
                    <span className="text-sm flex-1">{c.text}</span>
                    <div className="flex gap-0.5">
                      <IconBtn onClick={() => { setEditingCriterionId(c.id); setEditCriterionDraft(c.text) }}><Edit2 size={14} /></IconBtn>
                      <IconBtn disabled={i === 0} onClick={() => moveCriterion(i, -1)}><ArrowUp size={14} /></IconBtn>
                      <IconBtn disabled={i === criteria.length - 1} onClick={() => moveCriterion(i, 1)}><ArrowDown size={14} /></IconBtn>
                      <IconBtn danger onClick={() => removeCriterion.mutate(c.id)}><Trash2 size={14} /></IconBtn>
                    </div>
                  </div>
                )}
              </div>
            ))}
            <div className="flex gap-2 mt-3">
              <input className="flex-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" placeholder="New criterion" value={newCriterion} onChange={(e) => setNewCriterion(e.target.value)} />
              <Btn primary disabled={!newCriterion.trim()} onClick={() => { addCriterion.mutate(newCriterion); setNewCriterion('') }}>
                <Plus size={16} /> Add
              </Btn>
            </div>
          </section>

          {/* Trace Links */}
          <section className="border-b border-line pb-6">
            <div className="flex justify-between items-center mb-4">
              <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Trace Links</h2>
              <Btn onClick={() => navigate(`/requirements/trace-graph?type=REQUIREMENT&id=${id}`)}>View Graph →</Btn>
            </div>
            {links?.outgoing.length === 0 && links?.incoming.length === 0 && (
              <div className="text-center py-8 bg-panel-2 rounded-lg border border-dashed border-line-2">
                <p className="font-medium text-tx-2 text-sm">No trace links</p>
                <p className="text-sm text-tx-3">Nothing links to or from this requirement yet.</p>
              </div>
            )}
            {links?.outgoing.length === 0 && <p className="text-sm text-high bg-high-dim p-2 rounded border-l-2 border-high-bd mb-2">No downstream link — a gap.</p>}
            {links?.incoming.map((l) => (
              <div key={l.id} className="flex items-center gap-2 p-2 rounded hover:bg-panel-2">
                <span className="text-xs font-semibold uppercase tracking-wide bg-panel-3 px-2 py-0.5 rounded">{l.linkType}</span>
                <span className="text-xs text-tx-3 font-mono flex-1">from {l.fromType} {l.fromId.slice(0, 8)}…</span>
                <IconBtn onClick={() => reviewLink.mutate(l.id)}><RefreshCw size={14} /></IconBtn>
                <IconBtn danger onClick={() => deleteLink.mutate(l.id)}><Trash2 size={14} /></IconBtn>
              </div>
            ))}
            {links?.outgoing.map((l) => (
              <div key={l.id} className="flex items-center gap-2 p-2 rounded hover:bg-panel-2">
                <span className="text-xs font-semibold uppercase tracking-wide bg-panel-3 px-2 py-0.5 rounded">{l.linkType}</span>
                <span className="text-xs text-tx-3 font-mono flex-1">to {l.toType} {l.toId.slice(0, 8)}…</span>
                <IconBtn danger onClick={() => deleteLink.mutate(l.id)}><Trash2 size={14} /></IconBtn>
              </div>
            ))}
            <div className="grid sm:grid-cols-3 gap-2 mt-3">
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Direction</label>
                <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={linkDirection} onChange={(e) => setLinkDirection(e.target.value as 'upstream' | 'downstream')}>
                  <option value="upstream">Upstream</option>
                  <option value="downstream">Downstream</option>
                </select>
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Link Type</label>
                <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={linkType} onChange={(e) => setLinkType(e.target.value as TraceLinkType)}>
                  {LINK_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Other ID</label>
                <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition font-mono" placeholder="Paste ID" value={linkTargetId} onChange={(e) => setLinkTargetId(e.target.value)} />
              </div>
            </div>
            <Btn primary className="mt-3" disabled={!linkTargetId.trim() || createLink.isPending} onClick={() => createLink.mutate()}>
              <Plus size={16} /> Add Link
            </Btn>
            {createLink.isError && <p className="text-sm text-crit mt-1">Could not create link.</p>}
          </section>

          {/* Comments */}
          <section className="border-b border-line pb-6">
            <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-4 border-b border-line">Comments</h2>
            {comments?.length === 0 && (
              <div className="text-center py-8 bg-panel-2 rounded-lg border border-dashed border-line-2">
                <p className="font-medium text-tx-2 text-sm">No comments yet</p>
                <p className="text-sm text-tx-3">@mention a teammate.</p>
              </div>
            )}
            {comments?.map((c) => (
              <div key={c.id} className="py-3 border-b border-line last:border-0">
                <div className="flex gap-3 text-xs text-tx-3">
                  <span className="font-mono font-medium">{c.authorId.slice(0, 8)}…</span>
                  <span>{new Date(c.createdAt).toLocaleString()}</span>
                </div>
                <p className="text-sm mt-1">{c.body}</p>
              </div>
            ))}
            <div className="flex gap-2 mt-3">
              <input className="flex-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" placeholder="Write a comment — @mention" value={newComment} onChange={(e) => setNewComment(e.target.value)} />
              <Btn primary disabled={!newComment.trim() || addComment.isPending} onClick={() => addComment.mutate(newComment)}>
                <Plus size={16} /> Comment
              </Btn>
            </div>
            {addComment.isError && <p className="text-sm text-crit mt-1">Could not post comment.</p>}
          </section>

          {/* Clarifications */}
          <section>
            <h2 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-4 border-b border-line">Clarifications</h2>
            {clarifications?.length === 0 && (
              <div className="text-center py-8 bg-panel-2 rounded-lg border border-dashed border-line-2">
                <p className="font-medium text-tx-2 text-sm">No clarifications</p>
                <p className="text-sm text-tx-3">Raise one if something is ambiguous.</p>
              </div>
            )}
            {clarifications?.map((c) => (
              <div key={c.id} className="py-3 border-b border-line last:border-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`text-xs font-semibold uppercase px-2 py-0.5 rounded ${c.state === 'OPEN' ? 'bg-high-dim text-high' : 'bg-ok-dim text-ok'}`}>{c.state}</span>
                  {c.blocksTask && c.state === 'OPEN' && <span className="text-xs font-medium text-crit bg-crit-dim px-2 py-0.5 rounded">Blocking</span>}
                  <span className="text-sm flex-1">{c.question}</span>
                </div>
                {c.answer && (
                  <div className="mt-2 p-2 bg-panel-2 rounded border-l-2 border-brand-bd text-sm">
                    {c.answer}
                    {c.resultedInChangeRequestId && (
                      <div className="text-xs font-mono text-tx-3 mt-1">→ change request {c.resultedInChangeRequestId.slice(0, 8)}…</div>
                    )}
                  </div>
                )}
                {c.state === 'OPEN' && (
                  <div className="flex flex-wrap gap-2 mt-2">
                    <input className="flex-1 min-w-[180px] p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" placeholder="Answer..." value={answerDrafts[c.id] ?? ''} onChange={(e) => setAnswerDrafts((prev) => ({ ...prev, [c.id]: e.target.value }))} />
                    <label className="flex items-center gap-1 text-xs text-tx-2 whitespace-nowrap">
                      <input type="checkbox" checked={raiseCrOnAnswer[c.id] ?? false} onChange={(e) => setRaiseCrOnAnswer((prev) => ({ ...prev, [c.id]: e.target.checked }))} />
                      Changes meaning
                    </label>
                    <Btn primary disabled={!(answerDrafts[c.id] ?? '').trim() || answerClarification.isPending} onClick={() => answerClarification.mutate({ clarificationId: c.id, answer: answerDrafts[c.id] ?? '', asChangeRequest: raiseCrOnAnswer[c.id] ?? false })}>
                      Answer
                    </Btn>
                  </div>
                )}
              </div>
            ))}
            {answerClarification.isError && <p className="text-sm text-crit mt-1">Only assignee may answer.</p>}
            <div className="grid sm:grid-cols-2 gap-2 mt-3">
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">New Clarification</label>
                <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={newClarification} onChange={(e) => setNewClarification(e.target.value)} />
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Assignee (optional)</label>
                <UserPicker value={clarificationAssignee} onSelect={setClarificationAssignee} placeholder="Search..." />
              </div>
            </div>
            <label className="flex items-center gap-1.5 text-sm text-tx-2 mt-2">
              <input type="checkbox" checked={clarificationBlocks} onChange={(e) => setClarificationBlocks(e.target.checked)} />
              Blocks derived work until answered
            </label>
            <Btn primary className="mt-2" disabled={!newClarification.trim() || raiseClarification.isPending} onClick={() => raiseClarification.mutate()}>
              <Plus size={16} /> Raise Clarification
            </Btn>
            {raiseClarification.isError && <p className="text-sm text-crit mt-1">Could not raise clarification.</p>}
          </section>
        </div>

        {/* Sidebar */}
        <div className="space-y-4">
          {/* Capability */}
          <section className="bg-panel p-4 rounded-[10px] border border-line shadow-[0_1px_2px_rgb(0_0_0/.04)]">
            <h3 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-3 border-b border-line">Capability</h3>
            <p className="text-sm text-tx-2 mb-3">{req.capabilityId ? 'Currently assigned.' : 'Not assigned.'}</p>
            <CapabilityPicker value={capabilityId} onChange={setCapabilityId} allowUnassign={!!req.capabilityId} />
          </section>

          {/* Lifecycle */}
          <section className="bg-panel p-4 rounded-[10px] border border-line shadow-[0_1px_2px_rgb(0_0_0/.04)]">
            <h3 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-3 border-b border-line">Lifecycle</h3>
            <div className="flex flex-wrap gap-1.5 mb-3">
              {/* VYB-0811: IN_REVIEW's move to REVIEWED is "Verify", not a plain transition
                  button — it opens a choice between the human path (read the requirement
                  on the Design screen, then verify it there) and the AI path (not built
                  yet). Every other legal move still applies directly. */}
              {req.status === 'IN_REVIEW' && (
                <Btn className="text-xs px-3 py-1" onClick={() => setShowVerifyChoice(true)}>
                  Verify
                </Btn>
              )}
              {NEXT_STATES[req.status].filter((target) => !(req.status === 'IN_REVIEW' && target === 'REVIEWED')).map((target) => (
                <Btn key={target} className="text-xs px-3 py-1" disabled={transition.isPending} onClick={() => setConfirmingTransition(target)}>
                  {moveLabel(req.status, target).button}
                </Btn>
              ))}
              {transition.isError && <span className="text-xs text-crit w-full">Transition refused.</span>}
            </div>
            {showVerifyChoice && (
              <VerifyChoiceModal
                onManual={() => { setShowVerifyChoice(false); navigate(`/design?requirementId=${req.id}`) }}
                onClose={() => setShowVerifyChoice(false)}
              />
            )}
            {confirmingTransition && (
              <TransitionConfirmModal
                from={req.status}
                target={confirmingTransition}
                /* VYB-0813: sending a reviewed requirement back for revision needs a
                   reason exactly like a rejection does — reopening a REJECTED one
                   into NEEDS_REVISION does not, since the rejection already carries one. */
                reasonRequired={confirmingTransition === 'REJECTED'
                  || (confirmingTransition === 'NEEDS_REVISION' && req.status === 'REVIEWED')
                  || (confirmingTransition === 'IN_REVIEW' && (criteria?.length ?? 0) === 0)}
                reasonContext={confirmingTransition === 'NEEDS_REVISION' && req.status === 'REVIEWED'
                  ? 'What needs to change before this can be resubmitted.'
                  : undefined}
                pending={transition.isPending}
                onCancel={() => setConfirmingTransition(null)}
                onConfirm={(reason) => { transition.mutate({ target: confirmingTransition, reason }); setConfirmingTransition(null) }}
              />
            )}
            <div className="flex flex-wrap items-center gap-1 pt-3 border-t border-line">
              {lifecycle?.length === 0 && <span className="text-xs text-tx-3">No stages yet.</span>}
              {lifecycle?.map((s, i) => (
                <div key={s.stage} className="flex items-center gap-1">
                  {i > 0 && <span className="text-tx-3 text-xs">→</span>}
                  <span className="text-xs bg-panel px-2 py-0.5 rounded border border-line font-mono text-tx-2" title={new Date(s.occurredAt).toLocaleString()}>
                    {s.stage}{s.actorId ? ` · ${s.actorId.slice(0, 8)}` : ''}
                  </span>
                </div>
              ))}
            </div>
          </section>

          {/* Attachments */}
          <section className="bg-panel p-4 rounded-[10px] border border-line shadow-[0_1px_2px_rgb(0_0_0/.04)]">
            <h3 className="text-[11.5px] font-semibold uppercase tracking-[.05em] text-tx-3 pb-2.5 mb-3 border-b border-line">Attachments</h3>
            {attachments?.length === 0 && (
              <div className="text-center py-4 text-sm text-tx-3">No attachments</div>
            )}
            {attachments?.map((a) => (
              <div key={a.id} className="mb-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm flex-1 truncate">{a.filename}</span>
                  <span className="text-xs font-mono text-tx-3">v{a.currentVersion}</span>
                  {a.currentVersion > 1 && (
                    <IconBtn onClick={() => setExpandedAttachmentId(expandedAttachmentId === a.id ? null : a.id)}><History size={14} /></IconBtn>
                  )}
                  <IconBtn onClick={() => downloadFile(a.id, a.filename)}><Download size={14} /></IconBtn>
                </div>
                {expandedAttachmentId === a.id && (
                  <div className="ml-4 mt-1 border-l-2 border-line pl-3">
                    {attachmentVersions?.map((v) => (
                      <div key={v.id} className="flex items-center gap-2 py-0.5 text-xs">
                        <span className="font-mono text-tx-3">v{v.version}</span>
                        <span className="flex-1 text-tx-3">{new Date(v.uploadedAt).toLocaleString()}</span>
                        <IconBtn onClick={() => downloadFile(a.id, a.filename, v.version)}><Download size={12} /></IconBtn>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {downloadError && <p className="text-xs text-crit mb-2">{downloadError}</p>}
            <div className="mt-2">
              <input ref={fileInput} type="file" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadAttachment.mutate(f); e.target.value = '' }} />
              <Btn onClick={() => fileInput.current?.click()} disabled={uploadAttachment.isPending}>
                <Paperclip size={14} /> Upload
              </Btn>
              {uploadAttachment.isError && <p className="text-xs text-crit mt-1">Upload failed.</p>}
            </div>
          </section>
        </div>
      </div>

      {/* Defect Modal */}
      {showRaiseDefect && (
        <Modal onClose={() => setShowRaiseDefect(false)} title={`Raise Defect against ${req.key}`}>
          <div className="space-y-4">
            <p className="text-sm text-tx-2">Pre-linked to this requirement.</p>
            <div>
              <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Title</label>
              <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={defectTitle} onChange={(e) => setDefectTitle(e.target.value)} />
            </div>
            <div className="grid sm:grid-cols-2 gap-3">
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Severity</label>
                <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={defectSeverity} onChange={(e) => setDefectSeverity(e.target.value as DefectSeverity)}>
                  {SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <div>
                <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Found in</label>
                <select className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={defectFoundIn} onChange={(e) => setDefectFoundIn(e.target.value as FoundIn)}>
                  {FOUND_IN.map((f) => <option key={f} value={f}>{f}</option>)}
                </select>
              </div>
            </div>
            {raiseDefect.isError && <p className="text-sm text-crit">Could not raise defect.</p>}
            <div className="flex gap-2 pt-2 border-t border-line">
              <Btn onClick={() => setShowRaiseDefect(false)}>Cancel</Btn>
              <Btn primary disabled={!defectTitle.trim() || raiseDefect.isPending} onClick={() => raiseDefect.mutate()}>Raise</Btn>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function TransitionConfirmModal({ from, target, reasonRequired, reasonContext, pending, onCancel, onConfirm }: {
  from: string
  target: string
  reasonRequired: boolean
  reasonContext?: string
  pending: boolean
  onCancel: () => void
  onConfirm: (reason?: string) => void
}) {
  const [reason, setReason] = useState('')
  const label = moveLabel(from, target).sentence

  return (
    <Modal onClose={onCancel} title="Confirm Status Change">
      <div className="space-y-4">
        <p className="font-medium">Are you sure you want to {label}?</p>
        <p className="text-sm text-tx-2">{from} → {target}</p>
        {reasonContext && <p className="text-sm text-high bg-high-dim p-2 rounded">{reasonContext}</p>}
        <div>
          <label className="text-[10.5px] font-semibold uppercase tracking-[.05em] text-tx-3">Reason {reasonRequired ? '(required)' : '(optional)'}</label>
          <input className="w-full mt-1 p-2 bg-panel border border-line-2 rounded text-sm text-tx outline-none focus:border-brand focus:ring-3 focus:ring-brand-dim transition" value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
        </div>
        <div className="flex gap-2 pt-2 border-t border-line">
          <Btn onClick={onCancel}>Cancel</Btn>
          <Btn primary disabled={pending || (reasonRequired && !reason.trim())} onClick={() => onConfirm(reason.trim() || undefined)}>
            Confirm
          </Btn>
        </div>
      </div>
    </Modal>
  )
}

/**
 * VYB-0811: "Verify" on an IN_REVIEW requirement is a choice, not a direct transition —
 * who reads the requirement and vouches for it. Manual is real today: it sends the
 * reviewer to the Design screen, where they read the full requirement and record the
 * actual IN_REVIEW → REVIEWED move from there. AI is not — per Rule 6 (AI proposes, the
 * human decides), there is no AI reviewer yet, so this names that plainly instead of
 * hiding the option or pretending it works.
 */
function VerifyChoiceModal({ onManual, onClose }: { onManual: () => void; onClose: () => void }) {
  const [aiChosen, setAiChosen] = useState(false)

  return (
    <Modal onClose={onClose} title="Verify this requirement">
      <div className="space-y-4">
        <p className="text-sm text-tx-2">Who is verifying this — a person, or AI?</p>
        {aiChosen ? (
          <p className="text-sm text-high bg-high-dim p-2 rounded">
            AI-assisted verification is not implemented yet — a human performs this review today. Coming soon.
          </p>
        ) : (
          <div className="flex flex-col gap-2">
            <Btn primary onClick={onManual}>
              Manual verify — read it on the Design screen
            </Btn>
            <Btn onClick={() => setAiChosen(true)}>
              AI verify
            </Btn>
          </div>
        )}
        <div className="flex gap-2 pt-2 border-t border-line">
          <Btn onClick={onClose}>Close</Btn>
        </div>
      </div>
    </Modal>
  )
}