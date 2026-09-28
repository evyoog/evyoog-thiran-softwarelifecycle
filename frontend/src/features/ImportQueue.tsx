import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Upload, FileText, Loader2, Trash2 } from 'lucide-react'
import {
  ApiError, api, type Capability, type ImportCandidateInfo, type ImportCommitOutcome,
  type RequirementType, type UploadKind,
} from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog'
import { DocumentSummary, useDocumentAnalysis } from './importqueue/DocumentSummary'
import { PrdTable } from './importqueue/PrdTable'

/**
 * One kind: the standard template. Everything else the backend still knows about —
 * freeform, numbered specification, ReqIF, Word, and the generic spreadsheet reader — went
 * through the analysis agents, which meant tens of seconds per document and answers
 * inferred from prose. The template states the type, the priority and the acceptance
 * criteria in their own columns, so reading it is a parse: no model, no waiting, and the
 * values are the author's own rather than a reconstruction of them.
 *
 * The generic spreadsheet kind is the one worth naming, because it is the trap: it accepts
 * the same .xlsx and then reads only a statement column through the slow path, silently
 * discarding the other twenty-odd columns. Offering both put a worse import one click away
 * from the right one. Batches uploaded under any old kind still open and still read.
 */
const UPLOAD_KINDS: UploadKind[] = ['PRD_TEMPLATE']

/** The enum name is what the API wants; this is what the person choosing needs to read. */
const KIND_LABELS: Record<UploadKind, string> = {
  PRD_TEMPLATE: 'Requirements spreadsheet — .ods, .xlsx, .xls, .csv',
  EXCEL: 'Other spreadsheet — statement column only',
  FREEFORM: 'Freeform document',
  STANDARD_SPEC: 'Numbered specification',
  REQIF: 'ReqIF',
  WORD: 'Word (.docx)',
}

/** VYB-0666 AC1: the eight the register accepts — the same list the backend validates against. */
const REQUIREMENT_TYPES: RequirementType[] = [
  'FUNCTIONAL', 'NON_FUNCTIONAL', 'BUSINESS_RULE', 'INTERFACE',
  'DATA', 'REPORT', 'SECURITY', 'COMPLIANCE',
]

const KIND_ACCEPTS: Record<UploadKind, string | undefined> = {
  PRD_TEMPLATE: '.ods,.xlsx,.xls,.csv',
  FREEFORM: '.txt,.md,.pdf,.doc,.docx',
  STANDARD_SPEC: '.txt,.md,.pdf,.doc,.docx',
  REQIF: '.reqif,.reqifz,.xml',
  EXCEL: '.xlsx,.xls,.csv',
  WORD: '.docx',
}

function errorMessage(e: unknown, fallback: string): string {
  return e instanceof ApiError ? (e.detail ?? e.title) : fallback
}

function parseFlags(c: ImportCandidateInfo): Record<string, unknown> {
  if (!c.flags) return {}
  try {
    return JSON.parse(c.flags) as Record<string, unknown>
  } catch {
    return {}
  }
}

/**
 * Why a candidate has no brief, in the reviewer's words. The keys are the reasons
 * DocumentAnalysisService.Run declares; an unrecognised one falls through and is shown
 * verbatim rather than being flattened into a vague "unavailable".
 */
const READINESS_UNAVAILABLE: Record<string, string> = {
  'provider-unconfigured': 'the analysis provider is not configured',
  'per-run-ai-budget-reached': 'this run reached its AI call budget before reaching this one',
  'model-reply-not-usable': "the model's reply for this one failed the checks",
  'not-requested': 'this batch was extracted without the analysis step',
}

/** VYB-0637 AC2: a flagged duplicate needs a stated reason before it can commit. */
function needsImportReason(c: ImportCandidateInfo): boolean {
  const flags = parseFlags(c)
  return !!flags.duplicateOfRequirementId && !c.importReason
}

/**
 * VYB-0634 AC1: which capability control this candidate needs. Confirmation is a
 * deliberate human act, so it is always its own explicit button — never a side effect
 * of a `<select>` firing `onChange`. That earlier shape made confirmation unreachable
 * in two real cases: leaving a correctly pre-filled dropdown alone never fired, and an
 * application with a single capability had no second option to switch to at all, so
 * `capabilityConfirmed` could never become true and every commit skipped the candidate
 * with "capability not confirmed".
 *
 * Kept as a pure function so the gating is unit-testable without a DOM.
 */
export function capabilityControl(c: ImportCandidateInfo, capabilities: Capability[]):
  'confirmed' | 'pick' | 'none-available' {
  if (c.capabilityConfirmed) return 'confirmed'
  if (capabilities.length === 0) return 'none-available'
  return 'pick'
}

/** The capability a freshly-rendered picker should start on: the proposal if there is one, else the first option. */
export function defaultCapabilityChoice(c: ImportCandidateInfo, capabilities: Capability[]): string {
  if (c.capabilityId && capabilities.some((cap) => cap.id === c.capabilityId)) return c.capabilityId
  return capabilities[0]?.id ?? ''
}

/** Mirrors ImportService#commit's own gates, client-side, so the pre-commit summary can't drift silently out of sync with what the backend will actually do — the real outcomes from the commit response are shown afterward regardless. */
export function wouldCommit(c: ImportCandidateInfo): boolean {
  if (c.committedRequirementId) return true
  if (!c.capabilityConfirmed) return false
  if (needsImportReason(c)) return false
  return true
}

/**
 * VYB-0666: the sheet's own "Depends On" column, matched against "Your Ref" — the two
 * columns the template uses to say one requirement needs another. A candidate ticked for
 * import whose dependency is a different row that is NOT ticked is importing half of a
 * relationship the author explicitly recorded. This finds those, so the confirm dialog
 * can say so before commit rather than after, when it is a second trip back into the
 * queue to fix.
 *
 * <p>Matched by tag — {@code c.tag} is the row's Your Ref where one was given — not by
 * capability or placement, because "Depends On" is a statement about one requirement
 * needing another, independent of where either currently sits.
 */
export function unselectedDependencies(c: ImportCandidateInfo, all: ImportCandidateInfo[]): ImportCandidateInfo[] {
  const flags = parseFlags(c)
  const refs = (flags.prdDependsOn as string[] | undefined) ?? []
  if (refs.length === 0) return []
  return refs
    .map((ref) => all.find((other) => other.tag === ref))
    .filter((other): other is ImportCandidateInfo => !!other && !other.selected && !other.committedRequirementId)
}

/**
 * VYB-0630–0638: upload → extract → review/fix/confirm each candidate → selective
 * commit. Import Queue lives as a sub-route of Requirements rather than its own
 * sidebar entry — the sidebar caps at 10 modules (see nav.ts).
 */
export function ImportQueue() {
  const qc = useQueryClient()
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [kind, setKind] = useState<UploadKind>('PRD_TEMPLATE')
  const [file, setFile] = useState<File | null>(null)
  const [batchId, setBatchId] = useState<string | null>(null)
  const [uploadError, setUploadError] = useState<string | null>(null)

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId,
  })
  const { data: batches } = useQuery({ queryKey: ['import-batches'], queryFn: api.importBatches })
  const [deletingBatch, setDeletingBatch] = useState<{ id: string; filename: string } | null>(null)
  const deleteBatch = useMutation({
    mutationFn: (id: string) => api.deleteImportBatch(id),
    onSuccess: (_r, id) => {
      if (batchId === id) setBatchId(null)
      setDeletingBatch(null)
      void qc.invalidateQueries({ queryKey: ['import-batches'] })
    },
  })

  const upload = useMutation({
    mutationFn: () => api.uploadImportBatch(file as File, applicationId, kind),
    onSuccess: (b) => {
      setUploadError(null)
      setFile(null)
      setBatchId(b.id)
      void qc.invalidateQueries({ queryKey: ['import-batches'] })
    },
    // VYB-0660: name the actual failure, not just "upload failed".
    onError: (e) => setUploadError(errorMessage(e, 'Upload failed')),
  })

  return (
    <Page
      title="Import queue"
      desc="Upload a spec, review what it extracted, fix or confirm each candidate, then commit only what you select. Nothing enters the register before commit."
    >
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="eyebrow" style={{ marginBottom: 8 }}>Upload a document</div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <select className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId('') }}>
            <option value="">Product…</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <select className="select" value={applicationId} onChange={(e) => setApplicationId(e.target.value)} disabled={!productId}>
            <option value="">Application…</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <select className="select" value={kind} onChange={(e) => { setKind(e.target.value as UploadKind); setFile(null) }}>
            {UPLOAD_KINDS.map((k) => <option key={k} value={k}>{KIND_LABELS[k]}</option>)}
          </select>
          <input type="file" accept={KIND_ACCEPTS[kind]} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <button
            className="btn pri" disabled={!file || !applicationId || upload.isPending}
            onClick={() => upload.mutate()}
          >
            <Upload /> Upload
          </button>
        </div>
        {uploadError && <p className="err-text" style={{ marginTop: 8 }}>Could not read this file: {uploadError}</p>}
        <p className="hint muted" style={{ marginTop: 8 }}>
          Reads your template's columns exactly as written — no AI, so extraction is instant and the type,
          priority and acceptance criteria you filled in are the ones you get. The Product, App and Capability
          columns place each row automatically; you can change any of them afterwards. Rows whose columns cannot
          be matched still import — they arrive unticked with the reason next to them.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '260px 1fr', gap: 16 }}>
        <div>
          <div className="eyebrow" style={{ marginBottom: 8 }}>Batches</div>
          {batches && batches.length === 0 && <Empty title="No batches yet" desc="Upload one above." />}
          {batches?.map((b) => (
            <div
              key={b.id} className="list-item" style={{ cursor: 'pointer', borderColor: b.id === batchId ? 'var(--brand)' : undefined }}
              onClick={() => setBatchId(b.id)}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{b.filename}</div>
                <div className="mono muted" style={{ fontSize: 10 }}>{b.uploadKind} · {b.state}</div>
              </div>
              <button
                className="icon-btn" title="Delete this batch"
                onClick={(e) => { e.stopPropagation(); setDeletingBatch({ id: b.id, filename: b.filename }) }}
              >
                <Trash2 />
              </button>
            </div>
          ))}
        </div>
        <div>
          {batchId
            ? <BatchPanel batchId={batchId} />
            : <Empty title="Pick a batch" desc="Select one on the left, or upload a new one above." />}
        </div>
      </div>

      {deletingBatch && (
        <ConfirmDialog
          title={`Delete "${deletingBatch.filename}"?`}
          description="Every candidate this batch extracted goes with it. Requirements already committed from it are untouched — this only removes the upload and its review queue, not anything it produced."
          confirmLabel="Delete batch"
          onConfirm={() => deleteBatch.mutate(deletingBatch.id)}
          onCancel={() => setDeletingBatch(null)}
        />
      )}
    </Page>
  )
}

function BatchPanel({ batchId }: { batchId: string }) {
  const qc = useQueryClient()
  const [extractError, setExtractError] = useState<string | null>(null)
  const [showSummary, setShowSummary] = useState(false)
  const { data: analysis } = useDocumentAnalysis(batchId)
  const [confirmingCommit, setConfirmingCommit] = useState(false)

  const { data: batch } = useQuery({ queryKey: ['import-batch', batchId], queryFn: () => api.importBatch(batchId) })
  const { data: candidates, isLoading } = useQuery({
    queryKey: ['import-candidates', batchId], queryFn: () => api.importCandidates(batchId),
  })
  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', batch?.applicationId],
    queryFn: () => api.capabilities(batch!.applicationId!),
    enabled: !!batch?.applicationId,
  })

  const invalidateCandidates = () => void qc.invalidateQueries({ queryKey: ['import-candidates', batchId] })
  const invalidateBatch = () => {
    void qc.invalidateQueries({ queryKey: ['import-batch', batchId] })
    void qc.invalidateQueries({ queryKey: ['import-batches'] })
  }

  const extract = useMutation({
    mutationFn: () => api.extractCandidates(batchId),
    onSuccess: () => {
      setExtractError(null)
      invalidateCandidates()
      invalidateBatch()
      // Extraction is what produces the document summary now, so it has to be refetched
      // with the candidates rather than only on mount.
      void qc.invalidateQueries({ queryKey: ['document-analysis', batchId] })
    },
    // VYB-0660/0631 AC1: name which rule the document failed, not a generic error.
    onError: (e) => setExtractError(errorMessage(e, 'Extraction failed')),
  })
  const lint = useMutation({ mutationFn: (id: string) => api.lintCandidate(id), onSuccess: invalidateCandidates })
  const proposeCap = useMutation({
    mutationFn: (id: string) => api.proposeCandidateCapability(id, batch!.applicationId!), onSuccess: invalidateCandidates,
  })
  const confirmCap = useMutation({
    mutationFn: ({ id, capabilityId }: { id: string; capabilityId: string }) => api.confirmCandidateCapability(id, capabilityId),
    onSuccess: invalidateCandidates,
  })
  // VYB-0666: a single visible error rather than per-card, since an unconfigured
  // provider fails every candidate for the same reason — no point repeating it.
  const confirmType = useMutation({
    mutationFn: ({ id, type }: { id: string; type: RequirementType }) => api.confirmCandidateType(id, type),
    onSuccess: invalidateCandidates,
  })
  const editText = useMutation({
    mutationFn: ({ id, statement }: { id: string; statement: string }) => api.editCandidateText(id, statement),
    onSuccess: invalidateCandidates,
  })
  const select = useMutation({
    mutationFn: ({ id, selected }: { id: string; selected: boolean }) => api.selectCandidate(id, selected),
    onSuccess: invalidateCandidates,
  })
  const setReason = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.setCandidateImportReason(id, reason),
    onSuccess: invalidateCandidates,
  })
  const commit = useMutation({
    mutationFn: () => api.commitImportBatch(batchId),
    onSuccess: () => { setConfirmingCommit(false); invalidateCandidates(); invalidateBatch() },
  })

  if (!batch) return <p className="eyebrow">Loading…</p>

  const needsExtract = batch.state === 'UPLOADED'
  const selected = candidates?.filter((c) => c.selected) ?? []

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <div>
          <strong>{batch.filename}</strong>
          <span className="mono muted" style={{ fontSize: 10, marginLeft: 8 }}>{batch.uploadKind} · {batch.state}</span>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {/* Only offered once a run exists — there is nothing to open before that, and a
              button that opens an empty panel is worse than no button. */}
          {analysis && (
            <button
              className="btn"
              onClick={() => setShowSummary((s) => !s)}
              aria-expanded={showSummary}
              title="What the agents read this document to be about"
            >
              <FileText /> Summary
            </button>
          )}
          {needsExtract ? (
            <button className="btn pri" disabled={extract.isPending} onClick={() => extract.mutate()}>
              {/* Extraction reads the whole document through four agents — on a long spec
                  that is tens of seconds. A disabled button with no motion is
                  indistinguishable from one that didn't register the click. */}
              {extract.isPending ? <Loader2 className="spin" /> : null}
              {extract.isPending ? 'Extracting…' : 'Extract candidates'}
            </button>
          ) : (
            <button
              className="btn pri"
              disabled={selected.length === 0 || commit.isPending}
              onClick={() => setConfirmingCommit(true)}
              /* A disabled button with no stated reason reads as a missing feature. */
              title={selected.length === 0
                ? 'Tick the requirements you want to import first'
                : `Import ${selected.length} into the register`}
            >
              {commit.isPending ? <Loader2 className="spin" /> : null}
              Import {selected.length} selected
            </button>
          )}
        </div>
      </div>
      {/* VYB-0660/0667: every reason extraction can fail lands here — a document that
          failed a validation rule, one with no readable text, or the analysis agents
          being unreachable or misconfigured. The message is the backend's own. */}
      {extractError && <p className="err-text">{extractError}</p>}

      {/* VYB-0667: what the agents read the document to be about, from the same run that
          produced the candidates below. Behind the header's Summary toggle — the
          candidates are what this screen is for, and the summary is read once. */}
      <DocumentSummary batchId={batchId} open={showSummary} onClose={() => setShowSummary(false)} />

      {/* VYB-0665: a summary of what commit will do, shown before it runs — computed
          the same way the backend gates each candidate, so nothing here is a surprise. */}
      {confirmingCommit && (
        <Modal onClose={() => setConfirmingCommit(false)} title="Confirm import">
            <h3>Import {selected.length} selected requirement{selected.length === 1 ? '' : 's'}?</h3>
            <p className="muted" style={{ fontSize: 12.5 }}>
              {selected.filter(wouldCommit).length} will become new requirements.{' '}
              {selected.length - selected.filter(wouldCommit).length > 0 &&
                `${selected.length - selected.filter(wouldCommit).length} will be skipped:`}
            </p>
            {selected.filter((c) => !wouldCommit(c)).map((c) => (
              <div key={c.id} className="mono muted" style={{ fontSize: 11 }}>
                {c.tag ?? c.id.slice(0, 8)} — {!c.capabilityConfirmed ? 'capability not confirmed' : 'flagged duplicate, no import reason yet'}
              </div>
            ))}

            {/* VYB-0666: the sheet's own Depends On, checked against what is ticked. Every
                dependency named here is one the author wrote into the template — importing
                past it silently would be the tool ignoring a relationship they recorded on
                purpose. */}
            {(() => {
              const withGaps = selected
                .map((c) => ({ c, missing: unselectedDependencies(c, candidates ?? []) }))
                .filter((x) => x.missing.length > 0)
              return withGaps.length > 0 ? (
                <div className="prd-depwarn">
                  <span className="eyebrow">Depends on requirements you have not ticked</span>
                  {withGaps.map(({ c, missing }) => (
                    <div key={c.id} className="prd-depwarn-row">
                      <span className="mono">{c.tag ?? c.id.slice(0, 8)}</span> depends on{' '}
                      {missing.map((m, i) => (
                        <span key={m.id}>
                          {i > 0 && ', '}
                          <span className="mono">{m.tag ?? m.id.slice(0, 8)}</span>
                          <button className="btn" style={{ marginLeft: 6, fontSize: 10.5, padding: '2px 7px' }}
                                  onClick={() => select.mutate({ id: m.id, selected: true })}>
                            Tick it too
                          </button>
                        </span>
                      ))}
                    </div>
                  ))}
                </div>
              ) : null
            })()}

            <div className="actions">
              <button className="btn" onClick={() => setConfirmingCommit(false)}>Cancel</button>
              <button className="btn pri" disabled={commit.isPending} onClick={() => commit.mutate()}>Import</button>
            </div>
        </Modal>
      )}

      {commit.data && <CommitSummary outcomes={commit.data} />}

      {isLoading && <p className="eyebrow">Loading candidates…</p>}
      {candidates && candidates.length === 0 && !needsExtract && (
        <Empty title="No candidates extracted" desc="Extraction ran but found nothing in this document." />
      )}
      {/* VYB-0666: a template import is a grid the author already filled in, so it is
          reviewed as one. The cards below are for the prose imports, where reading each
          candidate one at a time is the actual work. */}
      {candidates && candidates.length > 0 && batch.uploadKind === 'PRD_TEMPLATE' && (
        <PrdTable
          candidates={candidates}
          capabilities={capabilities ?? []}
          onSelect={(id, selected) => select.mutate({ id, selected })}
          actions={
            <>
              <span className="eyebrow">
                {selected.length} of {candidates.length} ticked
                {selected.length > 0 && ` · ${selected.filter(wouldCommit).length} will import`}
              </span>
              <button
                className="btn pri"
                disabled={selected.length === 0 || commit.isPending}
                onClick={() => setConfirmingCommit(true)}
                title={selected.length === 0
                  ? 'Tick the requirements you want to import first'
                  : `Import ${selected.length} into the register`}
              >
                {commit.isPending ? <Loader2 className="spin" /> : null}
                Import {selected.length} selected
              </button>
            </>
          }
          onConfirmCapability={(id, capabilityId) => confirmCap.mutate({ id, capabilityId })}
        />
      )}

      {batch.uploadKind !== 'PRD_TEMPLATE' && candidates?.map((c) => (
        <CandidateCard
          key={c.id} c={c} capabilities={capabilities ?? []}
          onLint={() => lint.mutate(c.id)}
          onProposeCapability={() => proposeCap.mutate(c.id)}
          onConfirmCapability={(capId) => confirmCap.mutate({ id: c.id, capabilityId: capId })}
          onConfirmType={(type) => confirmType.mutate({ id: c.id, type })}
          onEditText={(text) => editText.mutate({ id: c.id, statement: text })}
          onSelect={(sel) => select.mutate({ id: c.id, selected: sel })}
          onSetReason={(reason) => setReason.mutate({ id: c.id, reason })}
        />
      ))}
    </div>
  )
}

function CommitSummary({ outcomes }: { outcomes: ImportCommitOutcome[] }) {
  const imported = outcomes.filter((o) => o.imported)
  const skipped = outcomes.filter((o) => !o.imported)
  return (
    <div className="card" style={{ marginBottom: 12, background: 'var(--surface-2)' }}>
      <div className="eyebrow" style={{ marginBottom: 6 }}>Commit result</div>
      <p style={{ fontSize: 12.5, margin: '0 0 6px' }}>{imported.length} imported, {skipped.length} skipped.</p>
      {skipped.map((o) => (
        <div key={o.candidateId} className="mono muted" style={{ fontSize: 11 }}>
          {o.candidateId.slice(0, 8)} — {o.reason}
        </div>
      ))}
    </div>
  )
}

function CandidateCard({
  c, capabilities, onLint, onProposeCapability, onConfirmCapability,
  onConfirmType, onEditText, onSelect, onSetReason,
}: {
  c: ImportCandidateInfo
  capabilities: Capability[]
  onLint: () => void
  onProposeCapability: () => void
  onConfirmCapability: (capabilityId: string) => void
  onConfirmType: (type: RequirementType) => void
  onEditText: (text: string) => void
  onSelect: (selected: boolean) => void
  onSetReason: (reason: string) => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(c.statement)
  const [showOriginal, setShowOriginal] = useState(false)
  // Null until the user actively picks, so a proposal arriving later (from "Propose")
  // still moves the picker — without a useEffect syncing two sources of truth.
  const [picked, setPicked] = useState<string | null>(null)
  const capabilityChoice = picked ?? defaultCapabilityChoice(c, capabilities)
  const setCapabilityChoice = setPicked

  // Same null-until-picked shape as the capability picker: an extraction proposal
  // arriving later still moves the control, with no effect syncing two sources of truth.
  const [pickedType, setPickedType] = useState<RequirementType | null>(null)
  const typeChoice = pickedType ?? (c.proposedType ?? 'FUNCTIONAL')
  const setTypeChoice = setPickedType

  const flags = parseFlags(c)
  const ambiguousTerms = (flags.ambiguousTerms as string[] | undefined) ?? []
  const ambiguousTermFixes = (flags.ambiguousTermFixes as Record<string, string> | undefined) ?? {}
  const suggestedFixText = flags.suggestedFixText as string | undefined
  const duplicateKey = flags.duplicateOfKey as string | undefined
  const duplicateSimilarity = flags.duplicateSimilarity as number | undefined
  const capabilityBasis = flags.capabilityBasis as string | undefined
  // VYB-0667: what the analysis agents read this candidate to be — a stated rule, a
  // problem, a constraint. The document never had to label it; this is the reading, and
  // the verbatim sentence it came from sits under "Show original text" as always.
  const analysisCategory = flags.analysisCategory as string | undefined
  const analysisImportance = flags.analysisImportance as string | undefined

  // The brief (agent 4): what this asks for, what building it involves, and what the
  // document never answers — the three things a build decision actually turns on.
  const briefTitle = flags.briefTitle as string | undefined
  const briefPriority = flags.briefPriority as string | undefined
  const briefCriteria = (flags.briefAcceptanceCriteria as string[] | undefined) ?? []
  const briefDescription = flags.briefDescription as string | undefined
  const briefEntails = (flags.briefEntails as string[] | undefined) ?? []
  const briefDependsOn = (flags.briefDependsOn as string[] | undefined) ?? []
  const briefOpenQuestions = (flags.briefOpenQuestions as string[] | undefined) ?? []
  const briefReadiness = flags.briefReadiness as string | undefined
  const briefUnavailable = flags.briefUnavailable as string | undefined
  // VYB-0666: the deterministic PRD-template import. Never amber and never inside the
  // brief block — none of this is model output (Principle 5). A problem here is something
  // the spreadsheet says wrongly, which is the author's to correct, not a proposal.
  const prdProblems = (flags.prdProblems as string[] | undefined) ?? []
  const prdSourceRow = flags.prdSourceRow as number | undefined
  // Columns the template asks for that the register has nowhere to put yet. Shown rather
  // than hidden: the author filled them in, and swallowing them silently would make the
  // template look like it collects more than it does.
  const prdParked = ([
    ['Verification', flags.prdVerificationMethod],
    ['Requested by', flags.prdRequestedBy],
    ['Owner', flags.prdOwnerName],
    ['Release', flags.prdTargetRelease],
    ['Regulatory', flags.prdRegulatoryReference],
    ['Parent', flags.prdParentRef],
  ] as [string, unknown][])
    .filter((e): e is [string, string] => typeof e[1] === 'string' && e[1].length > 0)
  const prdTags = (flags.prdTags as string[] | undefined) ?? []

  // VYB-0661: attention-needed at a glance — anything that would either fail commit
  // or is likely to need a human look before it should.
  const needsAttention = ambiguousTerms.length > 0 || !!duplicateKey || !c.capabilityConfirmed ||
    c.criteriaCount === 0 || (c.qualityScore != null && c.qualityScore < 70)

  return (
    <div className="card" style={{ marginBottom: 10, borderColor: needsAttention ? 'var(--high-bd)' : undefined }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
        <input type="checkbox" checked={c.selected} onChange={(e) => onSelect(e.target.checked)} />
        {needsAttention && (
          <span className="badge" style={{ background: 'var(--high-dim)', color: 'var(--high)' }}>Needs attention</span>
        )}
        {c.tag && <span className="mono muted" style={{ fontSize: 10 }}>{c.tag}</span>}
        {/* The register's own Priority, as the document supported it. Absent when the
            document gave no signal — the analyst is told to leave it rather than pick one. */}
        {briefPriority && <span className={`badge pr-${briefPriority.toLowerCase()}`}>{briefPriority}</span>}
        {analysisCategory && (
          <span className="badge sev-ai">
            {analysisCategory.replace(/_/g, ' ').toLowerCase()}
            {analysisImportance === 'HIGH' ? ' · high' : ''}
          </span>
        )}
        <span className="mono muted" style={{ fontSize: 10, marginLeft: 'auto' }}>
          {c.criteriaCount} criteria · quality {c.qualityScore ?? '—'}
        </span>
      </div>

      {editing ? (
        <div style={{ marginBottom: 8 }}>
          <textarea className="textarea" rows={3} value={draft} onChange={(e) => setDraft(e.target.value)} />
          <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
            <button className="btn pri" onClick={() => { onEditText(draft); setEditing(false) }}>Save</button>
            <button className="btn" onClick={() => { setDraft(c.statement); setEditing(false) }}>Cancel</button>
          </div>
        </div>
      ) : (
        <>
          {/* The title this commits into the register. Before extraction produced one,
              every AI-extracted requirement landed titled "f1", "f2", "f3". */}
          {briefTitle && <p style={{ fontSize: 13.5, fontWeight: 600, margin: '0 0 4px' }}>{briefTitle}</p>}
          <p style={{ fontSize: 13, margin: '0 0 8px', whiteSpace: 'pre-wrap' }}>{c.statement}</p>
        </>
      )}

      {/* VYB-0666: what the sheet says that stops this row importing. Stated on the card
          rather than as a count elsewhere, because the fix is a cell in the user's own
          spreadsheet and they need to know which one. */}
      {prdProblems.length > 0 && (
        <div className="prd-prob">
          <span className="eyebrow">
            Needs fixing{prdSourceRow ? ` — row ${prdSourceRow} of your sheet` : ''}
          </span>
          <ul className="prd-prob-l">
            {prdProblems.map((p) => <li key={p}>{p}</li>)}
          </ul>
        </div>
      )}

      {(prdParked.length > 0 || prdTags.length > 0) && (
        <div className="prd-parked">
          <span className="eyebrow">From your sheet</span>
          <div className="prd-parked-r">
            {prdParked.map(([label, value]) => (
              <span key={label} className="pill pill-plan">{label}: {value}</span>
            ))}
            {prdTags.map((t) => <span key={t} className="pill pill-plan">{t}</span>)}
          </div>
        </div>
      )}

      {/* Amber throughout, because every word of it is model output (Principle 3), and a
          proposal for a person to act on rather than anything already applied. */}
      {briefDescription && !editing && (
        <div className="brief">
          <div className="brief-h">
            <span className="eyebrow">What this asks for</span>
            {briefReadiness && <span className="badge sev-ai">{briefReadiness.replace(/_/g, ' ').toLowerCase()}</span>}
          </div>
          <p className="brief-tx">{briefDescription}</p>

          {briefCriteria.length > 0 && (
            <>
              <div className="brief-row">
                <span className="brief-l">Criteria</span>
                <span>{briefCriteria.length} checkable condition{briefCriteria.length === 1 ? '' : 's'}</span>
              </div>
              <ul className="brief-q brief-ac">
                {briefCriteria.map((ac) => <li key={ac}>{ac}</li>)}
              </ul>
            </>
          )}

          {briefEntails.length > 0 && (
            <div className="brief-row">
              <span className="brief-l">Entails</span>
              <span>{briefEntails.join(' · ')}</span>
            </div>
          )}
          {briefDependsOn.length > 0 && (
            <div className="brief-row">
              <span className="brief-l">Depends on</span>
              <span>{briefDependsOn.join(' · ')}</span>
            </div>
          )}

          {briefOpenQuestions.length > 0 && (
            <>
              <div className="brief-row">
                <span className="brief-l">Open</span>
                <span>{briefOpenQuestions.length} unanswered by the document</span>
              </div>
              <ul className="brief-q">
                {briefOpenQuestions.map((q) => <li key={q}>{q}</li>)}
              </ul>
            </>
          )}
        </div>
      )}

      {/* Principle 8: a candidate the analyst never got to says so, rather than looking
          like one it read and had nothing to raise about. */}
      {briefUnavailable && !editing && (
        <p className="hint muted" style={{ fontSize: 11, margin: '0 0 8px' }}>
          No analysis of what this would involve — {READINESS_UNAVAILABLE[briefUnavailable] ?? briefUnavailable}.
          The text above is the extractor's own reading.
        </p>
      )}

      {c.originalText && c.originalText !== c.statement && (
        <div style={{ marginBottom: 8 }}>
          <button className="btn" style={{ fontSize: 11 }} onClick={() => setShowOriginal((s) => !s)}>
            {showOriginal ? 'Hide' : 'Show'} original text
          </button>
          {showOriginal && (
            <p className="muted mono" style={{ fontSize: 11, whiteSpace: 'pre-wrap', marginTop: 6 }}>{c.originalText}</p>
          )}
        </div>
      )}

      {ambiguousTerms.length > 0 && !editing && (
        <div style={{ marginBottom: 8 }}>
          {ambiguousTerms.map((term) => (
            <p key={term} style={{ fontSize: 11.5, color: 'var(--high)', margin: '0 0 4px' }}>
              <strong>'{term}'</strong> — {ambiguousTermFixes[term]}
            </p>
          ))}
          {/* VYB-0663 AC1: shows the fix before applying — opens the editor pre-filled with the annotated version, distinct from "import as written." */}
          {suggestedFixText && (
            <button className="btn" style={{ fontSize: 11 }} onClick={() => { setDraft(suggestedFixText); setEditing(true) }}>
              Accept with fix
            </button>
          )}
        </div>
      )}
      {duplicateKey && (
        <p style={{ fontSize: 11.5, color: 'var(--high)', margin: '0 0 6px' }}>
          {duplicateSimilarity != null ? `${Math.round(duplicateSimilarity * 100)}% similar` : 'Similar'} to existing{' '}
          {duplicateKey} — importing anyway needs a reason below.
        </p>
      )}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
        <span className="muted" style={{ fontSize: 11.5 }}>{c.sourceLocation ?? 'source location unknown'}</span>
        {capabilityControl(c, capabilities) === 'confirmed' ? (
          <span className="badge">Capability confirmed</span>
        ) : capabilityControl(c, capabilities) === 'none-available' ? (
          <span className="hint muted" style={{ fontSize: 11 }}>
            This application has no capabilities yet — add one before importing.
          </span>
        ) : (
          <>
            {/* VYB-0634 AC1: pick, then confirm. The picker is always offered, whether or
                not a proposal exists, so a candidate the word-overlap proposer couldn't
                match is still importable. Confirming is the separate button — see
                capabilityControl's note on why onChange alone could never work. */}
            <select
              className="select" value={capabilityChoice}
              onChange={(e) => setCapabilityChoice(e.target.value)}
            >
              {capabilities.map((cap) => <option key={cap.id} value={cap.id}>{cap.name}</option>)}
            </select>
            <button className="btn pri" onClick={() => onConfirmCapability(capabilityChoice)}>
              Confirm capability
            </button>
            {!c.capabilityId && (
              <button className="btn" onClick={onProposeCapability}>Propose</button>
            )}
            {capabilityBasis && <span className="hint muted" style={{ fontSize: 10.5 }}>{capabilityBasis}</span>}
          </>
        )}
      </div>

      {/* VYB-0666: pick, then confirm — the same shape as the capability block above,
          and now for the same two reasons. The picker is always offered whether or not
          extraction read a type, so a candidate it couldn't classify is still typeable;
          removing the AI classify button would otherwise have left those with no control
          at all. And confirming is its own button because onChange alone can never fire
          for a user who agrees with a correctly pre-filled dropdown — the exact trap
          capabilityControl's own note documents. */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
        {c.typeConfirmed ? (
          <span className="badge">Type: {c.proposedType}</span>
        ) : (
          <>
            <select className="select" value={typeChoice} onChange={(e) => setTypeChoice(e.target.value as RequirementType)}>
              {REQUIREMENT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
            <button className="btn" onClick={() => onConfirmType(typeChoice)}>Confirm type</button>
            <span className="hint muted" style={{ fontSize: 10.5 }}>
              {c.proposedType
                ? `Extraction read this as ${c.proposedType}.`
                : 'Extraction read no type from the document — pick one.'}
            </span>
          </>
        )}
      </div>

      {duplicateKey && (
        <input
          className="input" placeholder="Reason to import despite the duplicate flag" defaultValue={c.importReason ?? ''}
          onBlur={(e) => { const v = e.target.value.trim(); if (v && v !== c.importReason) onSetReason(v) }}
          style={{ marginBottom: 8 }}
        />
      )}

      {c.committedRequirementId && (
        <p className="hint" style={{ fontSize: 11.5 }}>Committed as requirement {c.committedRequirementId.slice(0, 8)}</p>
      )}

      <div style={{ display: 'flex', gap: 8 }}>
        {!editing && <button className="btn" onClick={() => setEditing(true)}>Edit text</button>}
        <button className="btn" onClick={onLint}>{c.qualityScore == null ? 'Lint' : 'Re-lint'}</button>
      </div>
    </div>
  )
}
