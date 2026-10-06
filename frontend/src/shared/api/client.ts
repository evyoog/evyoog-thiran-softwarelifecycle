const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

let tokenProvider: () => string | undefined = () => undefined

/**
 * Turns a failed response into an ApiError, reading the RFC 9457 problem+json body the
 * API sends. Shared rather than inlined in `request`, because the multipart uploads
 * cannot use `request` (they must not set a JSON content type) and were throwing
 * `new ApiError(res.status, res.statusText)` instead — which discarded the server's
 * explanation and showed the reader the bare HTTP reason phrase. An .ods rejected with
 * a paragraph saying exactly what to do about it surfaced as "Could not read this
 * file: Conflict".
 */
async function problemFrom(res: Response): Promise<ApiError> {
  let title = res.statusText
  let detail: string | undefined
  let extra: Record<string, unknown> = {}
  try {
    const problem = await res.json()
    title = problem.title ?? title
    detail = problem.detail
    const { type: _t, title: _ti, status: _s, detail: _d, instance: _i, ...rest } = problem
    extra = rest
  } catch {
    /* non-JSON error body */
  }
  return new ApiError(res.status, title, detail, extra)
}

/** Called once by AuthProvider so the client can read the current access token. */
export function setTokenProvider(fn: () => string | undefined) {
  tokenProvider = fn
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail?: string,
    /** Extra RFC 9457 properties — e.g. attemptedRevision/currentRevision on a 409. */
    readonly extra: Record<string, unknown> = {},
  ) {
    super(detail ?? title)
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = tokenProvider()
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  })

  if (!res.ok) throw await problemFrom(res)

  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const usp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined) usp.set(k, String(v))
  }
  const s = usp.toString()
  return s ? `?${s}` : ''
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface MeGrant {
  role: string
  scopeType: string
  scopeId?: string
}

export interface Me {
  id: string
  email: string
  displayName: string
  /** VYB-0765 AC2: the one coarse signal nav/palette gating needs this session — see RoleCapabilityRegistry. */
  platformAdministrator: boolean
  activeGrants: MeGrant[]
}

// ── Portfolio ────────────────────────────────────────────────────────────────

export type ProductLifecycleStatus = 'IN_DEVELOPMENT' | 'LIVE' | 'MAINTENANCE' | 'DEPRECATED' | 'RETIRED'

/** VYB-0788: the fixed, CHECK-constrained icon-key set — see V011 and MARKS in Portfolio.tsx. */
export type ProductMark =
  | 'box' | 'sun' | 'shuffle' | 'calendar' | 'database' | 'target' | 'layers'
  | 'bar-chart' | 'git-branch' | 'shield' | 'bookmark' | 'file-text'

export interface Product {
  id: string
  key: string
  name: string
  vertical?: string
  purpose?: string
  ownerId?: string
  lifecycleStatus: ProductLifecycleStatus
  mark: ProductMark
  archived: boolean
}

export interface ProductAppSummary {
  id: string
  name: string
  reqCount: number
  gapCount: number
  verifiedCount: number
}

export interface ProductSummary {
  id: string
  key: string
  name: string
  vertical?: string
  purpose?: string
  ownerId?: string
  ownerName?: string
  lifecycleStatus: ProductLifecycleStatus
  mark: ProductMark
  reqCount: number
  appCount: number
  gapCount: number
  verifiedRatio: number
  apps: ProductAppSummary[]
}

/** One capability's rollup for the app detail cards — same definitions as ProductAppSummary. */
export interface CapabilitySummary {
  id: string
  name: string
  code?: string
  reqCount: number
  gapCount: number
  verifiedCount: number
}

export interface ProductDashboard {
  totals: { productCount: number; appCount: number; reqCount: number; gapCount: number; appsBelowThreshold: number }
  products: ProductSummary[]
}

export interface Application {
  id: string
  productId: string
  name: string
  description?: string
  archived: boolean
}

export interface Capability {
  id: string
  applicationId: string
  name: string
  code?: string
  description?: string
  archived: boolean
}

// ── Requirements ─────────────────────────────────────────────────────────────

// VYB-0802: REVIEWED is the manual review gate between IN_REVIEW and APPROVED.
export type RequirementStatus = 'DRAFT' | 'IN_REVIEW' | 'REVIEWED' | 'NEEDS_REVISION' | 'APPROVED' | 'REJECTED'
export type RequirementType =
  | 'FUNCTIONAL' | 'NON_FUNCTIONAL' | 'BUSINESS_RULE' | 'INTERFACE'
  | 'DATA' | 'REPORT' | 'SECURITY' | 'COMPLIANCE'
export type RequirementPriority = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'

export interface Requirement {
  id: string
  key: string
  capabilityId?: string
  type: RequirementType
  status: RequirementStatus
  priority: RequirementPriority
  title: string
  statement: string
  rationale?: string
  revision: number
  qualityScore?: number
  /** When it was created — what the grid sorts by, newest first, out of the box. */
  createdAt?: string
  hasUpstream: boolean
  hasDesign: boolean
  hasCode: boolean
  hasTest: boolean
  /** VYB-0666: zero means nothing can be tested against it — it is kept out of the grid. */
  criteriaCount: number
  /** VYB-0813 (D17): the most recent transition's metadata — null until the first one. */
  previousStatus?: RequirementStatus
  /** How many times this has entered NEEDS_REVISION. */
  revisionCount: number
  /** The reason given for the most recent transition, if any. */
  reason?: string
  changedBy?: string
  changedAt?: string
  /** Reserved for the deferred fork-a-new-version-on-editing-APPROVED mechanism — always 1 today. */
  version: number
}

export interface CreateRequirement {
  title: string
  statement: string
  type?: RequirementType
  priority?: RequirementPriority
  capabilityId?: string
}

export interface UpdateRequirement {
  revision: number
  title: string
  statement: string
  type: RequirementType
  priority: RequirementPriority
  capabilityId?: string
}

export interface AcceptanceCriterion {
  id: string
  ordinal: number
  text: string
}

export interface RequirementComment {
  id: string
  authorId: string
  body: string
  createdAt: string
  mentionedUserIds: string[]
}

export interface SimilarMatch {
  id: string
  key: string
  title: string
  statement: string
  score: number
}

export interface RowOutcome {
  id: string
  applied: boolean
  reason?: string
}

export interface BulkEditResult {
  batchId: string
  outcomes: RowOutcome[]
}

export interface BulkEditRequest {
  ids: string[]
  status?: RequirementStatus
  priority?: string
  type?: string
  touchCapability: boolean
  /** The rejection / no-criteria override the single-transition endpoint also demands. */
  reason?: string
  capabilityId?: string
  ownerId?: string
}

// ── Lifecycle history and live authoring signals (VYB-0119/0193/0202/0203) ──────────

export interface LifecycleStage {
  stage: string
  actorId?: string
  occurredAt: string
}

export interface GapPreviewItem {
  ruleKey: string
  reason: string
}

export interface AuthoringSignals {
  qualityScore: number
  qualityBreakdown: Record<string, number>
  avoidableGaps: GapPreviewItem[]
  expectedGaps: GapPreviewItem[]
}

/** VYB-0794 (Part 2): a real AI-generated rewrite alongside the deterministic quality score/breakdown above. */
export interface RewriteSuggestion {
  rewrittenStatement: string
  changes: string[]
  model: string
}

/** VYB-0824: INDIVIDUAL validates the requirement alone; DEPENDENCY validates it together with something it's trace-linked to. */
export type TestCaseSuggestionCategory = 'INDIVIDUAL' | 'DEPENDENCY'

/** An AI-proposed test case — never itself saved; accepting one calls draftTestCase like a manual entry. */
export interface TestCaseSuggestion {
  category: TestCaseSuggestionCategory
  title: string
  description: string
  rationale: string
}

/** A requirement directly trace-linked to the one suggestions were requested for. */
export interface RelatedRequirement {
  key: string
  title: string
  direction: 'UPSTREAM' | 'DOWNSTREAM'
}

/** VYB-0826: one requirement's suggestions within a bulk-generation result. */
export interface RequirementSuggestions {
  requirementId: string
  requirementKey: string
  requirementTitle: string
  /** false = you selected this one directly; true = pulled in only because it's a direct dependency of one you selected. */
  pulledInAsDependency: boolean
  suggestions: TestCaseSuggestion[]
  relatedRequirements: RelatedRequirement[]
}

export interface BulkTestCaseSuggestions {
  perRequirement: RequirementSuggestions[]
  model: string
}

/** VYB-0830: one requirement in a computed dependency cluster — `selected` false means it was pulled in only because it's connected to a selected one. */
export interface ClusterMember {
  requirementId: string
  key: string
  title: string
  selected: boolean
}

/** VYB-0830: one direct REQUIREMENT↔REQUIREMENT trace link between two members of the same cluster. */
export interface ClusterEdge {
  fromKey: string
  toKey: string
  linkType: TraceLinkType
}

/** VYB-0830: the full connected dependency component for a selection — no AI call, the cheap preview a generation flow shows before anyone commits to generating. */
export interface DependencyCluster {
  members: ClusterMember[]
  edges: ClusterEdge[]
  capped: boolean
}

// ── Coverage matrix (VYB-0147/0213) ──────────────────────────────────────────────

export type CoverageCellState = 'VERIFIED' | 'LINKED_NOT_RUN' | 'SUSPECT'

export interface CoverageCell {
  requirementId: string
  requirementKey: string
  testCaseId: string
  testCaseKey: string
  state: CoverageCellState
}

export interface CoverageTestTotal {
  testCaseId: string
  testCaseKey: string
  verified: number
  linkedNotRun: number
  suspect: number
}

export interface CoverageMatrix {
  cells: CoverageCell[]
  testTotals: CoverageTestTotal[]
}

// ── Documents (VYB-0210–0215) ────────────────────────────────────────────────────

export interface VyoogDocument {
  id: string
  key: string
  title: string
  productId?: string
  revision: number
  state: string
  itemCount: number
  gapCount: number
}

export interface DocumentRequirementSummary {
  id: string
  key: string
  title: string
  statement: string
  capabilityId?: string
}

// ── Teams (VYB-0464) ──────────────────────────────────────────────────────────────

export interface Team {
  id: string
  name: string
  memberIds: string[]
}

export interface AttachmentInfo {
  id: string
  filename: string
  currentVersion: number
}

export interface AttachmentVersionInfo {
  id: string
  version: number
  contentType?: string
  sizeBytes?: number
  uploadedAt: string
}

// ── Trace ────────────────────────────────────────────────────────────────────

export type TraceObjectType = 'NEED' | 'REQUIREMENT' | 'DESIGN_NODE' | 'CODE' | 'TEST' | 'RELEASE' | 'CLAUSE'
export type TraceLinkType = 'SATISFIES' | 'DERIVES' | 'VERIFIES' | 'IMPLEMENTS' | 'REFINES' | 'CONFLICTS'

export interface TraceLink {
  id: string
  fromType: TraceObjectType
  fromId: string
  toType: TraceObjectType
  toId: string
  linkType: TraceLinkType
  reviewedAtRevision?: number
}

export interface TraceHop {
  type: TraceObjectType
  id: string
}

export interface Reachability {
  type: TraceObjectType
  id: string
  depth: number
  path: TraceHop[]
}

export interface GraphNode {
  type: TraceObjectType
  id: string
  label: string
  root: boolean
}

export interface GraphEdge {
  fromType: TraceObjectType
  fromId: string
  toType: TraceObjectType
  toId: string
  linkType: string
}

export interface TraceGraph {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

// ── Findings ─────────────────────────────────────────────────────────────────

export type FindingState = 'OPEN' | 'ACCEPTED' | 'DISMISSED' | 'RESOLVED'

export interface Finding {
  id: string
  ruleKey: string
  objectType: string
  objectId: string
  severity: string
  title: string
  detail?: string
  suggestion?: string
  state: FindingState
  dismissReason?: string
  /** VYB-0615/0616: set only for the five AI-derived detectors — never cleared by dismissal. */
  confidence?: number
  /** "model@prompt-version" as one string — see VYB-0616. */
  model?: string
  /** VYB-0654: the pairwise findings ('dup'/'conflict') encode the "other side" here. */
  discriminator?: string
}

export interface SweepSummary {
  ruleKey: string
  opened: number
  refreshed: number
  reopened: number
  resolved: number
  /** VYB-0603: true means this detector couldn't run this cycle — its existing findings were left untouched, not "found nothing." */
  unavailable: boolean
}

// ── Lint ─────────────────────────────────────────────────────────────────────

export interface LintFinding {
  term: string
  suggestion: string
}

// ── Glossary ─────────────────────────────────────────────────────────────────

export interface GlossaryTerm {
  id: string
  term: string
  definition: string
  ownerId?: string
}

export interface GlossaryVariant {
  definition: string
  applicationIds: string[]
}

export interface GlossaryConflict {
  termId: string
  term: string
  variants: GlossaryVariant[]
}

// ── Rules ────────────────────────────────────────────────────────────────────

export interface Rule {
  key: string
  name: string
  technique: string
  severity: string
  description: string
  phase: number
  enabled: boolean
  threshold?: number
  totalFindings: number
  /** VYB-0617: computed from real dismissals, not a placeholder. */
  dismissalRate: number
}

export interface DismissalReason {
  reason: string
  count: number
}

export interface NoisyDetectorResult {
  ruleKey: string
  rate: number
  disabled: boolean
}

// ── Clauses (VYB-0611) ───────────────────────────────────────────────────────────

export interface Clause {
  id: string
  standard: string
  section?: string
  text: string
}

// ── Import queue (VYB-0630–0638) ─────────────────────────────────────────────────

export type UploadKind = 'FREEFORM' | 'STANDARD_SPEC' | 'REQIF' | 'EXCEL' | 'WORD' | 'PRD_TEMPLATE'

export interface ImportBatchInfo {
  id: string
  filename: string
  applicationId?: string
  uploadKind: UploadKind
  uploadedAt: string
  state: string
}

export interface ImportCandidateInfo {
  id: string
  batchId: string
  tag?: string
  statement: string
  originalText?: string
  sourceLocation?: string
  capabilityId?: string
  capabilityConfirmed: boolean
  criteriaCount: number
  qualityScore?: number
  flags?: string
  selected: boolean
  importReason?: string
  committedRequirementId?: string
  proposedType?: RequirementType
  typeConfirmed: boolean
  // The API has always returned these; they were missing from this interface, so the
  // acceptance criteria a candidate carried were invisible to every screen.
  acceptedCriteria?: string
  acceptedTraceLinks?: string
  productId?: string
  applicationId?: string
  placementLevel?: 'PRODUCT' | 'APPLICATION' | 'CAPABILITY' | 'UNPLACED'
}

/**
 * VYB-0667: one run of the document analysis pipeline. `findings`, `themes` and
 * `unsupportedClaims` arrive as raw JSON strings (jsonb columns), the same convention
 * `ImportCandidateInfo.flags` already uses — parse with `parseAnalysisFindings` below
 * rather than trusting the shape.
 */
export interface DocumentAnalysisInfo {
  id: string
  batchId: string
  state: 'PROPOSED' | 'ACCEPTED' | 'DISMISSED'
  description: string
  findings?: string
  themes?: string
  unsupportedClaims?: string
  chunksTotal: number
  chunksAnalysed: number
  findingsKept: number
  findingsRejected: number
  noiseBlocksDiscarded: number
  revisionRan: boolean
  /** True when the per-run AI budget stopped the run before the whole document was read. */
  partial: boolean
  model: string
  promptVersion: string
  aiCalls: number
  createdAt: string
  decidedAt?: string
  dismissReason?: string
}

export interface DocumentAnalysisFinding {
  category: string
  statement: string
  evidence: string
  sourceLocation: string
  importance: 'HIGH' | 'MEDIUM' | 'LOW'
}

export function parseAnalysisFindings(a: DocumentAnalysisInfo): DocumentAnalysisFinding[] {
  if (!a.findings) return []
  try {
    const parsed: unknown = JSON.parse(a.findings)
    return Array.isArray(parsed) ? (parsed as DocumentAnalysisFinding[]) : []
  } catch {
    return []
  }
}

export function parseAnalysisStrings(raw: string | undefined): string[] {
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

export interface ImportCommitOutcome {
  candidateId: string
  imported: boolean
  reason?: string
  requirementId?: string
}

// ── Review rounds (VYB-0300–0307) ───────────────────────────────────────────────

export type ReviewParticipantRole = 'REVIEWER' | 'APPROVER' | 'OBSERVER'
export type ReviewState = 'OPEN' | 'CLOSED' | 'BLOCKED'

export interface ReviewParticipant {
  userId: string
  role: ReviewParticipantRole
  signedAt?: string
  signatureAcr?: string
}

export interface Review {
  id: string
  title: string
  scopeRef?: string
  state: ReviewState
  openedAt: string
  closedAt?: string
  closesAt?: string
  stale: boolean
  requirementIds: string[]
  participants: ReviewParticipant[]
}

export interface ReviewComment {
  id: string
  requirementId?: string
  authorId: string
  body: string
  createdAt: string
}

export interface OpenReviewRequest {
  title: string
  scopeRef?: string
  requirementIds: string[]
  participants?: { userId: string; role: ReviewParticipantRole }[]
}

// ── Test evidence (VYB-0310–0316) ────────────────────────────────────────────────

export interface EvidenceSummary {
  testCaseCount: number
  unverifiedCount: number
  staleCount: number
}

export interface RequirementRef {
  id: string
  key: string
  title: string
}

/** VYB-0363: a human-drafted proposal, distinct from a CI-ingested test case (VYB-0310). */
export interface TestCase {
  id: string
  key: string
  title: string
  description?: string
  /** VYB-0827: only ever set for an accepted AI suggestion — null for manual drafts and every ingested row. */
  category?: TestCaseSuggestionCategory
  status: 'DRAFT' | 'INGESTED'
  requirementId: string
}

/** VYB-0825: one row of the browsable test-case list — the requirement it verifies, found via its VERIFIES trace link, is null only if no link was ever created for it. */
export interface TestCaseListItem {
  id: string
  key: string
  title: string
  description?: string
  category?: TestCaseSuggestionCategory
  status: 'DRAFT' | 'INGESTED'
  requirementId?: string
  requirementKey?: string
  requirementTitle?: string
}

/** VYB-0827: one requirement that has at least one test case, with counts by category — the "Test cases" tab's top level. */
export interface RequirementTestCaseSummary {
  requirementId: string
  requirementKey: string
  requirementTitle: string
  individualCount: number
  dependencyCount: number
  otherCount: number
  totalCount: number
}

export interface VerificationRecord {
  id: string
  requirementRevision: number
  testCaseId?: string
  result: 'PASS' | 'FAIL'
  verifiedAt: string
}

// ── Manual test management (VYB-0923 to VYB-0927) ───────────────────────────────

export type RunStatus = 'PLANNED' | 'IN_PROGRESS' | 'COMPLETED'
/** What a person records on a step (or on a case that has no steps). */
export type StepResult = 'PASS' | 'FAIL' | 'BLOCKED'
/** A case's result is derived from its steps and is never null: NOT_RUN until every step has one. */
export type CaseResult = StepResult | 'NOT_RUN'

export interface TestPlan {
  id: string
  key: string
  name: string
  description?: string
  applicationId: string
  applicationName: string
  releaseId?: string
  createdAt: string
  suiteCount: number
  runCount: number
}

export interface TestSuite {
  id: string
  planId: string
  name: string
  description?: string
  position: number
  caseCount: number
}

export interface SuiteCase {
  testCaseId: string
  key: string
  title: string
  position: number
}

export interface TestRun {
  id: string
  suiteId?: string
  suiteName?: string
  planId?: string
  planName?: string
  kind: 'CI' | 'MANUAL'
  status: RunStatus
  buildLabel?: string
  assignedTo?: string
  assignedToName?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
  caseCount: number
  /** The run this one retests, when it is a retest. */
  retestOf?: string
}

/** One file backing a result; download it through the requirement's attachment endpoints. */
export interface Evidence {
  id: string
  attachmentId: string
  version: number
  requirementId: string
  requirementKey: string
  filename: string
  contentType?: string
  sizeBytes?: number
  addedBy?: string
  addedAt: string
}

/** A requirement a case verifies, frozen when the run started; currentRevision above testedRevision means it was edited since. */
export interface TestedRequirement {
  requirementId: string
  key: string
  testedRevision: number
  currentRevision: number
}

export interface RunStep {
  id: string
  position: number
  action: string
  expectedResult: string
  result?: StepResult
  actualResult?: string
  executedBy?: string
  executedByName?: string
  executedAt?: string
  evidence: Evidence[]
  defectId?: string
  defectKey?: string
}

export interface RunCase {
  id: string
  position: number
  testCaseId?: string
  key: string
  title: string
  description?: string
  steps: RunStep[]
  /** Derived from the steps; for a case with no steps, what was recorded on the case itself. */
  result: CaseResult
  /** Set only for a case with no steps. */
  actualResult?: string
  executedBy?: string
  executedByName?: string
  executedAt?: string
  evidence: Evidence[]
  requirements: TestedRequirement[]
  defectId?: string
  defectKey?: string
}

export interface RunSummary {
  total: number
  passed: number
  failed: number
  blocked: number
  notRun: number
  /** The verification rows written when the run was completed; 0 until then. */
  verificationsRecorded: number
}

export interface TestRunDetail {
  run: TestRun
  cases: RunCase[]
  summary: RunSummary
}

/** What a defect raised from a failed step would be prefilled with, and the read-only context behind it. */
export interface DefectDraft {
  title: string
  severity: DefectSeverity
  foundIn: FoundIn
  requirementId?: string
  candidates: TestedRequirement[]
  runId: string
  buildLabel?: string
  planName?: string
  suiteName?: string
  testKey: string
  testTitle: string
  stepPosition?: number
  action?: string
  expectedResult?: string
  actualResult?: string
  existingDefectId?: string
  existingDefectKey?: string
}

export interface RaisedDefect {
  id: string
  key: string
  title: string
  severity: DefectSeverity
  requirementId?: string
  untraced: boolean
  foundIn: FoundIn
  state: DefectState
  runId: string
  runStepId?: string
  runCaseId?: string
}

/**
 * VYB-0927: per requirement that has a test case. Each case counts once, by its latest result at the
 * requirement's current revision (CI and manual together). `passRate` is passed of those with a result, and null
 * (not zero) when none has one; stale and not-run are not failures.
 */
export interface PassRate {
  requirementId: string
  key: string
  title: string
  status: string
  revision: number
  cases: number
  passed: number
  failed: number
  stale: number
  notRun: number
  passRate?: number
  lastResultAt?: string
}

export interface RaiseRunDefectBody {
  title?: string
  severity?: DefectSeverity
  foundIn?: FoundIn
  requirementId?: string
}

// ── Defects (VYB-0320–0323) ──────────────────────────────────────────────────────

export type DefectSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
export type FoundIn = 'DEV' | 'QA' | 'UAT' | 'PRODUCTION'
export type RootCause =
  | 'REQUIREMENT_AMBIGUITY' | 'REQUIREMENT_OMISSION' | 'CODING_ERROR' | 'ENVIRONMENT' | 'DATA' | 'UNKNOWN'
export type DefectState = 'OPEN' | 'FIXED' | 'CLOSED'

export interface Defect {
  id: string
  key: string
  title: string
  severity: DefectSeverity
  requirementId?: string
  untraced: boolean
  foundIn: FoundIn
  rootCause?: RootCause
  developerId?: string
  /** VYB-0322 AC2: recorded, not only notified once. */
  testerId?: string
  state: DefectState
  raisedAt: string
}

export interface RootCauseSplit {
  rootCause: string
  count: number
}

// ── Clarifications (VYB-0330–0334/0380–0382) ─────────────────────────────────────

export type ClarificationState = 'OPEN' | 'ANSWERED' | 'WITHDRAWN'

export interface Clarification {
  id: string
  requirementId: string
  question: string
  blocksTask: boolean
  raisedBy: string
  raisedAt: string
  assignedTo?: string
  answer?: string
  answeredBy?: string
  answeredAt?: string
  state: ClarificationState
  resultedInChangeRequestId?: string
  /** VYB-0334: recorded, not only notified once. */
  escalatedAt?: string
  escalatedTo?: string
  /** VYB-0372: derived (raisedAt + the configured escalation window) — null once resolved. */
  dueAt?: string
}

// ── Derived tasks / My Work (VYB-0340–0349/0370–0374) ────────────────────────────

export type TaskKind =
  | 'AUTHOR_WORDING' | 'REVIEWER_PENDING' | 'APPROVER_AWAITING'
  | 'DEVELOPER_IMPLEMENT' | 'DEVELOPER_REIMPLEMENT' | 'TESTER_VERIFY' | 'TESTER_REVERIFY'

export interface Task {
  kind: TaskKind
  objectId: string
  objectLabel: string
  reason: string
  since?: string
  blocked: boolean
  blockedByQuestion?: string
  blockedOwedBy?: string
}

export interface StalledRequirement {
  id: string
  key: string
  status: string
  daysInStage: number
  thresholdDays: number
}

/**
 * VYB-0838 (D19): a person dismissed one derived task once, at the object's revision
 * at the time — not a task itself. `objectLabel` is the requirement key or review
 * title, resolved server-side, since a completion outlives the task it dismissed (the
 * task itself may no longer be derivable by the time this is read back).
 */
export interface CompletedTask {
  kind: TaskKind
  objectId: string
  objectLabel: string
  completedAt: string
}

// ── Notifications (VYB-0355) ─────────────────────────────────────────────────────

export interface AppNotification {
  id: string
  tone: string
  /** VYB-0357: the coalescing key — repeats of the same kind collapse into occurrenceCount rather than flooding the inbox. */
  kind?: string
  title: string
  subtitle?: string
  link?: string
  read: boolean
  occurrenceCount: number
  createdAt: string
}

// ── Change requests (VYB-0390–0393) ──────────────────────────────────────────────

export type ChangeRequestState = 'OPEN' | 'APPROVED' | 'REJECTED' | 'APPLIED'

export interface ChangeRequest {
  id: string
  key: string
  title: string
  rationale: string
  raisedBy?: string
  state: ChangeRequestState
  impactRequirements: number
  impactApps: number
  scope: string[]
}

export interface ChangeImpact {
  requirements: number
  tests: number
  applications: number
  briefs: number
}

// ── Briefs (VYB-0450–0457) ────────────────────────────────────────────────────────

export type BriefTarget = 'CLAUDE_CODE' | 'CODEX' | 'CURSOR' | 'HUMAN'

/**
 * The optional parts of a brief. Omitting the field entirely means every section — an
 * empty array would too, so the UI always sends what is actually ticked.
 */
export type BriefSection =
  | 'CONTEXT' | 'CATEGORY_REVIEW' | 'ACCEPTANCE_CRITERIA' | 'OPEN_QUESTIONS'
  | 'QUALITY_APPENDIX' | 'DEFINITION_OF_DONE' | 'COMMIT_TRAILER'

export interface Brief {
  id: string
  applicationId: string
  /** VYB-0836: the application's own name — needed once history can span more than one application (no application picked). */
  applicationName: string
  target: BriefTarget
  developerId: string
  content: string
  generatedAt: string
  stale: boolean
  staleBecause: string[]
}

// ── Scope signals (VYB-0460–0465) ─────────────────────────────────────────────────

/**
 * D10: where a requirement's due date came from. EXPLICIT is a person's commitment,
 * RELEASE is borrowed from the release it is committed to, DERIVED is the configured
 * stage threshold rather than anybody's promise, and NONE means nothing knows.
 */
/** A person's own named grid filters. Every field is one GET /requirements accepts. */
export interface SavedViewRow {
  id: string
  name: string
  status?: RequirementStatus
  priority?: RequirementPriority
  type?: RequirementType
  titleContains?: string
  capabilityId?: string
}

export interface ScopeSummary {
  level: string
  id?: string
  name?: string
  total: number
  planned: number
  unplanned: number
  atRisk: number
  overdue: number
  notReady: number
  developers: number
}

export interface AssignOutcome { requirementId: string; assigned: boolean; reason?: string }

/** A team has leads and members; only a lead may assign work to that team's people. */
export type TeamRole = 'LEAD' | 'MEMBER'

export interface TeamMember {
  userId: string
  displayName: string
  email: string
  role: TeamRole
}

/** VYB-0374: an environment and what is actually in it. Nulls mean nothing deployed. */
export interface EnvironmentSummary {
  id: string
  name: string
  ordinal: number
  buildLabel?: string
  deployedAt?: string
  requirementCount: number
  succeeded: boolean
}

export interface RecentDeployment {
  id: string
  environmentName: string
  buildLabel: string
  deployedAt: string
  succeeded: boolean
  requirementCount: number
}

/** Approved, but with no passing test at the current revision — Principle 3 as a gate. */
export interface ReleaseBlocker {
  requirementId: string
  key: string
  title: string
  reason: string
}


export interface PlanningAssignment {
  id: string
  requirementId: string
  requirementKey: string
  assignedBy: string
  assignedByName: string
  assignedTo: string
  assignedToName: string
  teamId?: string
  /** The assigner's role when they assigned — recorded, not resolved later. */
  assignedByRole?: string
  assignedAt: string
  reason?: string
  comment?: string
}

export interface ScopeSignals {
  requirementCount: number
  acceptanceCriteriaCount: number
  dependencyDepth: number
  crossApplicationReach: number
  ambiguityLoad: number
  openGaps: number
  changeRate: number
  novelty: number
  computedAt: string
  queries: Record<string, string>
}

export interface ImpactVolume {
  requirements: number
  tests: number
  /** VYB-0507: of `tests`, how many are CI-ingested ("borrowed" from the CI integration) rather than drafted in Vyoog. */
  testsBorrowed: number
  applications: number
  capabilities: number
  owners: number
  teams: number
  briefs: number
}

// ── Baselines and variants (VYB-0470–0473) ───────────────────────────────────────

export interface Baseline {
  id: string
  name: string
  releaseId?: string
  frozenAt: string
  frozenBy?: string
  gapsAtFreeze: number
}

export interface BaselineItem {
  requirementId: string
  key: string
  revision: number
}

export interface BaselineChangedItem {
  requirementId: string
  key: string
  fromRevision: number
  toRevision: number
}

export interface BaselineDiff {
  added: BaselineItem[]
  removed: BaselineItem[]
  changed: BaselineChangedItem[]
}

export interface Variant {
  id: string
  name: string
}

export interface VariantMatrixRow {
  requirementId: string
  key: string
  variantIds: string[]
}

// ── Releases (VYB-0474–0476/0483/0484) ───────────────────────────────────────────

export type ReleaseState = 'PLANNED' | 'OPEN' | 'FROZEN' | 'RELEASED'

export interface VyoogRelease {
  id: string
  name: string
  state: ReleaseState
  /** VYB-0372: the one genuinely plannable date this app has — set explicitly, never inferred. */
  targetDate?: string
}

export interface ScopeMovementView {
  requirementId: string
  direction: 'IN' | 'OUT'
  reason?: string
  movedBy?: string
  movedAt: string
}

export interface ReleaseReadiness {
  committed: number
  verified: number
  verifiedRatio: number
  criticalOpenGaps: number
}

export interface BlockedItem {
  requirementId: string
  key: string
  reason: string
}

/** VYB-0928/0929: one readiness gate evaluated now, in words. */
export interface GateResult {
  gate: string
  passed: boolean
  detail: string
}

/** A move a release can make from its current state, with each enabled readiness gate evaluated. */
export interface ReleaseMove {
  to: ReleaseState
  /** A reopen needs a written reason. */
  needsReason: boolean
  /** Moving into FROZEN or RELEASED is a signature event: it needs step-up authentication. */
  signatureRequired: boolean
  ready: boolean
  gates: GateResult[]
}

/** VYB-0929: the release being prepared (OPEN or FROZEN, earliest target date first) and what stands in its way. */
export interface CurrentRelease {
  id: string
  name: string
  state: ReleaseState
  targetDate?: string
  moves: ReleaseMove[]
  blocked: BlockedItem[]
}

export interface ReleaseNoteItem {
  requirementId: string
  key: string
  title: string
  capabilityName: string
}

export interface ReleaseNotes {
  approvedByCapability: Record<string, ReleaseNoteItem[]>
  held: ReleaseNoteItem[]
}

// ── Environments and deployment (VYB-0480–0482) ──────────────────────────────────

export interface VyoogEnvironment {
  id: string
  name: string
  ordinal: number
}

export interface Deployment {
  id: string
  environmentId: string
  buildLabel: string
  deployedAt: string
  succeeded: boolean
}

export interface PresenceRow {
  requirementId: string
  key: string
  revision: number
}

// ── Design layer (VYB-0490–0495) ─────────────────────────────────────────────────

/** VYB-0816 adds 'testing'/'deployment': the two shared pipeline milestones a generate run ends in. */
export type DesignNodeKind = 'start' | 'step' | 'decision' | 'integration' | 'end' | 'testing' | 'deployment'

export interface DesignFlow {
  id: string
  applicationId: string
}

export interface DesignNode {
  id: string
  flowId: string
  kind: DesignNodeKind
  label: string
  note?: string
  requirementIds: string[]
}

export interface DesignEdge {
  id: string
  flowId: string
  fromNode: string
  toNode: string
  label?: string
}

export interface UncoveredRequirement {
  id: string
  key: string
  title: string
}

export interface OrphanNode {
  id: string
  label: string
  kind: string
}

/** VYB-0537: real percentages with their denominators, computed server-side. */
export interface DesignCoverageSummary {
  totalRequirements: number
  requirementsWithDesign: number
  requirementCoveragePct: number
  totalNodes: number
  nodesWithRequirement: number
  nodeCoveragePct: number
}

/**
 * VYB-0816: what the "Testing" and "Deployment" milestone nodes actually mean — real
 * evidence (a passing test at the requirement's current revision; presence in a
 * recorded deployment), never the requirement's own status flag.
 */
export interface DesignFlowProgress {
  totalRequirements: number
  verifiedRequirements: number
  verifiedPct: number
  deployedRequirements: number
  deployedPct: number
}

// ── Administration: users, grants, service accounts (VYB-0700–0713/0750–0753) ───

export type AccessRoleName =
  | 'VIEWER' | 'BUSINESS_ANALYST' | 'REVIEWER' | 'APPROVER' | 'DEVELOPER'
  | 'TESTER' | 'COMPLIANCE_LEAD' | 'ARCHITECT' | 'ADMINISTRATOR'
export type ScopeTypeName = 'PLATFORM' | 'PRODUCT' | 'APP' | 'CAPABILITY' | 'RELEASE'

export interface AppUserView {
  id: string
  email: string
  displayName: string
  source: string
  status: string
  statusChangedAt: string
  mfaEnrolled: boolean
  lastSeenAt?: string
  activeGrantCount: number
  /** VYB-0750 AC2: a departed user still holding a grant, flagged on the row itself. */
  departedButActive: boolean
  delegateId?: string
  /** VYB-0334/0792: who this user's clarification escalations go to before falling back to the capability owner. */
  managerId?: string
}

/** VYB-0502: the real picker — id/name/email only, open to any authenticated user, not gated like the full directory. */
export interface UserPickerRow {
  id: string
  displayName: string
  email: string
}

export interface Grant {
  id: string
  userId: string
  role: AccessRoleName
  scopeType: ScopeTypeName
  scopeId?: string
  grantedBy?: string
  grantedAt: string
  expiresAt?: string
  revokedAt?: string
  active: boolean
}

export interface RoleCapability {
  role: AccessRoleName
  description: string
  enforced: boolean
  enforcedBy: string
}

export interface ServiceAccountView {
  id: string
  name: string
  purpose?: string
  clientId: string
  scopes: string[]
  keyIssuedAt: string
  keyAgeDays: number
  everUsed: boolean
  lastUsedAt?: string
  previousClientId?: string
  keyRotationOverlapUntil?: string
  rotatedAt?: string
}

// ── Administration: security, audit (VYB-0704/0705/0713/0721/0722/0754/0755) ────

export interface SecurityFindingSummary {
  id: string
  title: string
  detail?: string
  suggestion?: string
  objectId: string
}

export interface ExpiringGrant {
  grantId: string
  userId: string
  role: string
  expiresAt: string
}

export interface SecurityReport {
  separationOfDuties: SecurityFindingSummary[]
  departedAccounts: SecurityFindingSummary[]
  staleKeys: SecurityFindingSummary[]
  expiringExternalGrants: ExpiringGrant[]
}

export interface AuditEventView {
  id: string
  occurredAt: string
  actorId?: string
  actorType: string
  action: string
  objectType?: string
  objectId?: string
  before?: string
  after?: string
  requestId?: string
  ip?: string
}

// ── Administration: integrations (VYB-0740–0743/0756) ────────────────────────────

export interface IntegrationView {
  key: string
  connected: boolean
  owns?: string
  direction: string
  failureCount: number
  lastError?: string
  lastErrorAt?: string
  degraded: boolean
  webhookConfigured: boolean
  /** VYB-0465/0507: JSON text — e.g. `{"pushUrl": "https://..."}`  for an OUTBOUND connection. */
  config?: string
}

// ── Administration: connector health (VYB-0917) ──────────────────────────────────

export type ConnectorState = 'NOT_CONNECTED' | 'HEALTHY' | 'DEGRADED'
export type ConnectorSyncStatus = 'IN_PROGRESS' | 'SUCCEEDED' | 'FAILED'

/** One operation sent (or being sent) through a connection. `error` came from the receiver: shortened, secrets removed. */
export interface ConnectorSyncEntry {
  id: string
  operation: string
  idempotencyKey: string
  status: ConnectorSyncStatus
  attempts: number
  httpStatus?: number
  error?: string
  payloadBytes: number
  startedAt: string
  finishedAt?: string
  durationMs?: number
}

/** Holds no configuration and no secret. `notConfiguredReason` is why nothing can be sent yet; absent when the configuration is usable. */
export interface ConnectorStatus {
  key: string
  state: ConnectorState
  notConfiguredReason?: string
  owns?: string
  direction: string
  connectorDescription?: string
  operations: string[]
  consecutiveFailures: number
  lastError?: string
  lastErrorAt?: string
  lastSuccessAt?: string
  lastSync?: ConnectorSyncEntry
}

// ── Administration: settings (VYB-0730–0734/0757) ─────────────────────────────────

export interface AppConfigView {
  reqKeyPrefix: string
  stageStallThresholdDays: Record<string, number>
  maxExternalGrantDays: number
  staleKeyAgeDays: number
  keyRotationOverlapDays: number
  auditRetentionDays: number
  suspended: boolean
  suspendedReason?: string
  embeddingModel: string
  aiCallsPerRunLimit: number
  noisyDetectorDismissalCeiling: number
}

// ── Global search (VYB-0766) ──────────────────────────────────────────────────────

export type SearchResultKind = 'REQUIREMENT' | 'CAPABILITY' | 'GLOSSARY_TERM' | 'FINDING'

export interface SearchResult {
  kind: SearchResultKind
  id: string
  label: string
  detail?: string
}

export interface TenantExportManifest {
  generatedAt: string
  tables: { table: string; rowCount: number; rows: Record<string, unknown>[] }[]
  totalRows: number
}

/** VYB-0723: a real Postgres partition of audit_event, not a simulated bucket. */
export interface AuditPartitionInfo {
  name: string
  rangeStart?: string
  rangeEnd?: string
  archived: boolean
  rowCount: number
}

/** VYB-0733, reframed — see the backend TenantHardResetService's own note: a single-tenant hard reset, not multi-tenant deletion. */
export interface ResetPreview {
  tables: { table: string; rowCount: number }[]
  totalRows: number
}

/** VYB-0800: no refreshToken field — it never reaches this file. See AuthController's Javadoc for where it actually goes. */
export interface AuthTokenResponse {
  accessToken: string
  expiresInSeconds: number
}

export const api = {
  me: () => request<Me>('/me'),

  // VYB-0048b: unauthenticated by design — this is how a token is obtained.
  // VYB-0800: `credentials: 'include'` on all three — the refresh token rides an
  // HttpOnly cookie the backend sets/reads/clears; the browser only attaches or
  // accepts it on requests that opt in explicitly (this app's own CORS config
  // already allows credentials for its named origins, so this works whether the
  // frontend and API share an origin or not).
  authLogin: (username: string, password: string) =>
    request<AuthTokenResponse>('/auth/login', {
      method: 'POST', body: JSON.stringify({ username, password }), credentials: 'include' }),
  authRefresh: () =>
    request<AuthTokenResponse>('/auth/refresh', { method: 'POST', credentials: 'include' }),
  // Cross-app SSO bridge (see AuthController#session's own doc): own refresh
  // cookie if it works, else the shared vyoog_sso bridge against the other
  // three Vyoog apps, else "not authenticated". authPing is the same check,
  // used by the cross-tab polling loop.
  authSession: () =>
    request<AuthTokenResponse>('/auth/session', { credentials: 'include' }),
  authPing: () =>
    request<AuthTokenResponse>('/auth/ping', { credentials: 'include' }),
  authLogout: () =>
    request<void>('/auth/logout', { method: 'POST', credentials: 'include' }),

  // Portfolio
  products: () => request<Product[]>('/products'),
  productDashboard: () => request<ProductDashboard>('/products/dashboard'),
  capabilitySummary: (applicationId: string) =>
    request<CapabilitySummary[]>(`/products/applications/${applicationId}/capability-summary`),
  productDashboardReport: async (): Promise<Blob> => {
    const token = tokenProvider()
    const res = await fetch(`${BASE}/products/dashboard/report.csv`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },
  createProduct: (body: {
    key: string; name: string; vertical?: string; purpose?: string
    ownerId?: string; lifecycleStatus?: ProductLifecycleStatus; mark?: ProductMark
  }) => request<Product>('/products', { method: 'POST', body: JSON.stringify(body) }),
  updateProduct: (id: string, body: {
    name: string; vertical?: string; purpose?: string
    ownerId?: string; lifecycleStatus?: ProductLifecycleStatus; mark?: ProductMark
  }) => request<Product>(`/products/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  archiveProduct: (id: string) => request<Product>(`/products/${id}/archive`, { method: 'POST' }),

  applications: (productId: string) => request<Application[]>(`/products/${productId}/applications`),
  createApplication: (productId: string, body: { name: string; description?: string }) =>
    request<Application>(`/products/${productId}/applications`, { method: 'POST', body: JSON.stringify(body) }),
  updateApplication: (productId: string, id: string, body: { name: string; description?: string }) =>
    request<Application>(`/products/${productId}/applications/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  archiveApplication: (productId: string, id: string) =>
    request<Application>(`/products/${productId}/applications/${id}/archive`, { method: 'POST' }),

  capabilities: (applicationId: string) => request<Capability[]>(`/applications/${applicationId}/capabilities`),
  createCapability: (applicationId: string, body: { name: string; code?: string; description?: string }) =>
    request<Capability>(`/applications/${applicationId}/capabilities`, { method: 'POST', body: JSON.stringify(body) }),
  updateCapability: (applicationId: string, id: string, body: { name: string; code?: string; description?: string }) =>
    request<Capability>(`/applications/${applicationId}/capabilities/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  archiveCapability: (applicationId: string, id: string) =>
    request<Capability>(`/applications/${applicationId}/capabilities/${id}/archive`, { method: 'POST' }),

  // Requirements
  requirements: (params: {
    status?: RequirementStatus; capabilityId?: string; type?: string; priority?: string; ownerId?: string
    title?: string; page?: number; size?: number
    /** VYB-0666: true = only requirements that have criteria, false = only those that don't. */
    hasCriteria?: boolean
    /** VYB-0831: true = only requirements with at least one test case, false = only those without. */
    hasTestCase?: boolean
    /** VYB-0170–0180: one or more `field,dir` pairs — each becomes its own repeated
     * `sort=` query param (Spring's Pageable accumulates repeated params into one
     * multi-property Sort; a single string joined with "&sort=" would just be one
     * mangled, percent-encoded value, not multiple params — this must stay an array). */
    sort?: string[]
  }) => {
    const base = query({
      status: params.status, capabilityId: params.capabilityId, type: params.type, priority: params.priority,
      ownerId: params.ownerId, title: params.title, page: params.page, size: params.size,
      // VYB-0666: was declared on the params type but never actually reached the URL —
      // every call silently ran with no criteria filter at all, which is why the "needs
      // acceptance criteria" strip listed every requirement instead of only the ones
      // genuinely missing them.
      hasCriteria: params.hasCriteria,
      hasTestCase: params.hasTestCase,
    })
    const sortParams = (params.sort ?? []).map((s) => `sort=${encodeURIComponent(s)}`).join('&')
    const sep = base ? '&' : '?'
    return request<Page<Requirement>>(`/requirements${base}${sortParams ? sep + sortParams : ''}`)
  },
  requirement: (id: string) => request<Requirement>(`/requirements/${id}`),
  createRequirement: (body: CreateRequirement, idempotencyKey?: string) =>
    request<Requirement>('/requirements', {
      method: 'POST', body: JSON.stringify(body),
      headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {},
    }),
  updateRequirement: (id: string, body: UpdateRequirement, changeRequestId?: string) =>
    request<Requirement>(`/requirements/${id}${query({ changeRequestId })}`, {
      method: 'PATCH', body: JSON.stringify(body),
    }),
  /** VYB-0666: soft delete — the row and its history survive, every read path hides it. */
  deleteRequirement: (id: string, reason?: string) =>
    request<void>(`/requirements/${id}${query({ reason })}`, { method: 'DELETE' }),
    transitionRequirement: (id: string, revision: number, target: RequirementStatus, reason?: string) =>
    request<Requirement>(`/requirements/${id}/transition${query({ revision })}`, {
      method: 'POST',
      body: JSON.stringify({ target, reason }),
    }),
  similarRequirements: (statement: string) =>
    request<SimilarMatch[]>(`/requirements/similar${query({ statement })}`),

  acceptanceCriteria: (requirementId: string) =>
    request<AcceptanceCriterion[]>(`/requirements/${requirementId}/acceptance-criteria`),
  addAcceptanceCriterion: (requirementId: string, text: string) =>
    request<AcceptanceCriterion>(`/requirements/${requirementId}/acceptance-criteria`, {
      method: 'POST', body: JSON.stringify({ text }),
    }),
  reorderAcceptanceCriteria: (requirementId: string, orderedIds: string[]) =>
    request<AcceptanceCriterion[]>(`/requirements/${requirementId}/acceptance-criteria/order`, {
      method: 'PUT', body: JSON.stringify({ orderedIds }),
    }),
  removeAcceptanceCriterion: (id: string) =>
    request<void>(`/acceptance-criteria/${id}`, { method: 'DELETE' }),
  editAcceptanceCriterion: (id: string, text: string) =>
    request<AcceptanceCriterion>(`/requirements/acceptance-criteria/${id}`, { method: 'PATCH', body: JSON.stringify({ text }) }),

  // Lifecycle history and live authoring signals
  lifecycle: (requirementId: string) => request<LifecycleStage[]>(`/requirements/${requirementId}/lifecycle`),
  lifecycleSpine: () => request<{ stage: string; count: number }[]>('/requirements/lifecycle-spine'),
  authoringSignals: (body: { statement: string; criteriaCount: number; hasCapability: boolean; hasUpstream: boolean }) =>
    request<AuthoringSignals>('/requirements/authoring-signals', { method: 'POST', body: JSON.stringify(body) }),
  /** VYB-0794: an explicit "Suggest rewrite" action — never called automatically on a keystroke, unlike authoringSignals. */
  rewriteSuggestion: (body: { statement: string; criteriaCount: number; hasUpstream: boolean }) =>
    request<RewriteSuggestion>('/requirements/rewrite-suggestion', { method: 'POST', body: JSON.stringify(body) }),
  /** VYB-0826/0830: also generates for the selection's whole connected dependency component — `pulledInAsDependency` on each result tells a selected requirement apart from one only pulled in. */
  testCaseSuggestionsBulk: (requirementIds: string[]) =>
    request<BulkTestCaseSuggestions>('/requirements/test-case-suggestions/bulk', {
      method: 'POST', body: JSON.stringify({ requirementIds }),
    }),
  /** VYB-0830: no AI call — the full connected dependency component for a selection, shown before anyone commits to generating. */
  dependencyCluster: (requirementIds: string[]) =>
    request<DependencyCluster>('/requirements/dependency-cluster', {
      method: 'POST', body: JSON.stringify({ requirementIds }),
    }),

  // Coverage matrix
  coverageMatrix: (applicationId: string) => request<CoverageMatrix>(`/trace/coverage-matrix/${applicationId}`),

  // Documents
  documents: () => request<VyoogDocument[]>('/documents'),
  createDocument: (body: { key: string; title: string; productId?: string }) =>
    request<VyoogDocument>('/documents', { method: 'POST', body: JSON.stringify(body) }),
  documentRequirements: (id: string) => request<DocumentRequirementSummary[]>(`/documents/${id}/requirements`),
  addDocumentRequirement: (id: string, requirementId: string) =>
    request<void>(`/documents/${id}/requirements/${requirementId}`, { method: 'POST' }),
  removeDocumentRequirement: (id: string, requirementId: string) =>
    request<void>(`/documents/${id}/requirements/${requirementId}`, { method: 'DELETE' }),
  reorderDocumentRequirements: (id: string, orderedRequirementIds: string[]) =>
    request<void>(`/documents/${id}/requirements/order`, { method: 'PUT', body: JSON.stringify({ orderedRequirementIds }) }),
  downloadDocumentWord: async (id: string): Promise<Blob> => {
    const token = tokenProvider()
    const res = await fetch(`${BASE}/documents/${id}/export/word`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },
  downloadDocumentReqIf: async (id: string): Promise<Blob> => {
    const token = tokenProvider()
    const res = await fetch(`${BASE}/documents/${id}/export/reqif`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },

  // Teams
  teams: () => request<Team[]>('/teams'),
  createTeam: (name: string) => request<void>('/teams', { method: 'POST', body: JSON.stringify({ name }) }),
  addTeamMember: (teamId: string, userId: string) => request<void>(`/teams/${teamId}/members/${userId}`, { method: 'PUT' }),
  removeTeamMember: (teamId: string, userId: string) => request<void>(`/teams/${teamId}/members/${userId}`, { method: 'DELETE' }),
  teamMembers: (teamId: string) => request<TeamMember[]>(`/teams/${teamId}/members`),
  setTeamMemberRole: (teamId: string, userId: string, role: TeamRole) =>
    request<void>(`/teams/${teamId}/members/${userId}/role`, { method: 'PUT', body: JSON.stringify({ role }) }),

  // AI usage and tenant bootstrap
  aiUsage: () => request<{ used: number; limit: number }>('/ai/usage'),
  bootstrapStatus: () => request<{ bootstrapped: boolean }>('/settings/bootstrap'),
  bootstrapTenant: (firstAdministratorUserId: string) =>
    request<void>('/settings/bootstrap', { method: 'POST', body: JSON.stringify({ firstAdministratorUserId }) }),

  // Bulk edit
  bulkEdit: (body: BulkEditRequest) =>
    request<BulkEditResult>('/requirements/bulk-edit', { method: 'POST', body: JSON.stringify(body) }),
  undoBulkEdit: (batchId: string) =>
    request<BulkEditResult>(`/requirements/bulk-edit/${batchId}/undo`, { method: 'POST' }),

  // Comments
  comments: (requirementId: string) =>
    request<RequirementComment[]>(`/requirements/${requirementId}/comments`),
  addComment: (requirementId: string, body: string) =>
    request<RequirementComment>(`/requirements/${requirementId}/comments`, {
      method: 'POST', body: JSON.stringify({ body }),
    }),

  // Attachments
  attachments: (requirementId: string) =>
    request<AttachmentInfo[]>(`/requirements/${requirementId}/attachments`),
  attachmentVersions: (requirementId: string, attachmentId: string) =>
    request<AttachmentVersionInfo[]>(`/requirements/${requirementId}/attachments/${attachmentId}/versions`),
  uploadAttachment: async (requirementId: string, file: File): Promise<AttachmentInfo> => {
    const form = new FormData()
    form.append('file', file)
    const token = tokenProvider()
    const res = await fetch(`${BASE}/requirements/${requirementId}/attachments`, {
      method: 'POST', body: form,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw await problemFrom(res)
    return res.json()
  },
  // Auth needs a real fetch, not a plain <a href> — the browser won't attach the
  // bearer token to a bare navigation.
  downloadAttachment: async (requirementId: string, attachmentId: string, version?: number): Promise<Blob> => {
    const path = `/requirements/${requirementId}/attachments/${attachmentId}` +
      (version ? `/versions/${version}/download` : '/download')
    const token = tokenProvider()
    const res = await fetch(`${BASE}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },

  // Glossary
  glossaryTerms: () => request<GlossaryTerm[]>('/glossary'),
  createGlossaryTerm: (term: string, definition: string) =>
    request<GlossaryTerm>('/glossary', { method: 'POST', body: JSON.stringify({ term, definition }) }),
  recordGlossaryUsage: (termId: string, applicationId: string, definition?: string) =>
    request<void>(`/glossary/${termId}/usage/${applicationId}`, { method: 'PUT', body: JSON.stringify({ definition }) }),
  glossaryConflicts: () => request<GlossaryConflict[]>('/glossary/conflicts'),

  // Trace
  createLink: (body: { fromType: TraceObjectType; fromId: string; toType: TraceObjectType; toId: string; linkType: TraceLinkType }) =>
    request<TraceLink>('/trace/links', { method: 'POST', body: JSON.stringify(body) }),
  deleteLink: (id: string) => request<void>(`/trace/links/${id}`, { method: 'DELETE' }),
  reviewLink: (id: string) => request<TraceLink>(`/trace/links/${id}/review`, { method: 'POST' }),
  links: (type: TraceObjectType, id: string) =>
    request<{ outgoing: TraceLink[]; incoming: TraceLink[] }>(`/trace/${type}/${id}/links`),
  upstream: (type: TraceObjectType, id: string, depth = 12) =>
    request<Reachability[]>(`/trace/${type}/${id}/upstream${query({ depth })}`),
  downstream: (type: TraceObjectType, id: string, depth = 12) =>
    request<Reachability[]>(`/trace/${type}/${id}/downstream${query({ depth })}`),
  traceGraph: (type: TraceObjectType, id: string, depth = 6) =>
    request<TraceGraph>(`/trace/${type}/${id}/graph${query({ depth })}`),

  // Findings
  findings: (params: { state?: FindingState; ruleKey?: string; page?: number; size?: number }) =>
    request<Page<Finding>>(`/findings${query(params)}`),
  dismissFinding: (id: string, reason: string) =>
    request<Finding>(`/findings/${id}/dismiss`, { method: 'POST', body: JSON.stringify({ reason }) }),
  acceptFinding: (id: string) => request<Finding>(`/findings/${id}/accept`, { method: 'POST' }),
  reopenFinding: (id: string) => request<Finding>(`/findings/${id}/reopen`, { method: 'POST' }),
  triggerSweep: () => request<SweepSummary[]>('/findings/sweep', { method: 'POST' }),

  // Lint
  lint: (statement: string) =>
    request<{ findings: LintFinding[] }>('/lint', { method: 'POST', body: JSON.stringify({ statement }) }),

  // Rules
  rules: () => request<Rule[]>('/rules'),
  setRuleEnabled: (key: string, enabled: boolean) =>
    request<Rule>(`/rules/${key}`, { method: 'PATCH', body: JSON.stringify({ enabled }) }),
  setRuleThreshold: (key: string, threshold: number) =>
    request<Rule>(`/rules/${key}/threshold`, { method: 'PUT', body: JSON.stringify({ threshold }) }),
  previewThresholdEffect: (key: string, threshold: number) =>
    request<number>(`/rules/${key}/threshold-preview${query({ threshold })}`),
  dismissalReasons: (key: string) => request<DismissalReason[]>(`/rules/${key}/dismissal-reasons`),
  applyNoisyDefaults: () => request<NoisyDetectorResult[]>('/rules/apply-noisy-defaults', { method: 'POST' }),

  // Clauses
  clauses: () => request<Clause[]>('/clauses'),
  createClause: (body: { standard: string; section?: string; text: string }) =>
    request<Clause>('/clauses', { method: 'POST', body: JSON.stringify(body) }),

  // AI / embeddings
  embeddingModel: () => request<{ modelName: string; dimensions: number }>('/ai/embedding-model'),
  similarByEmbedding: (requirementId: string, limit = 10) =>
    request<{ requirementId: string; key: string; title: string; similarity: number }[]>(
      `/ai/similar${query({ requirementId, limit })}`),
  reembedStale: () => request<number>('/ai/reembed-stale', { method: 'POST' }),

  // Import queue
  uploadImportBatch: async (file: File, applicationId: string, kind: UploadKind): Promise<ImportBatchInfo> => {
    const form = new FormData()
    form.append('file', file)
    const token = tokenProvider()
    const res = await fetch(`${BASE}/import${query({ applicationId, kind })}`, {
      method: 'POST', body: form,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw await problemFrom(res)
    return res.json()
  },
  importBatches: () => request<ImportBatchInfo[]>('/import/batches'),
  importBatch: (id: string) => request<ImportBatchInfo>(`/import/batches/${id}`),
  /** VYB-0666: a real delete — nothing outside the queue references a batch. */
  deleteImportBatch: (id: string) => request<void>(`/import/batches/${id}`, { method: 'DELETE' }),
  extractCandidates: (batchId: string) =>
    request<ImportCandidateInfo[]>(`/import/batches/${batchId}/extract`, { method: 'POST' }),
  importCandidates: (batchId: string) => request<ImportCandidateInfo[]>(`/import/batches/${batchId}/candidates`),
  lintCandidate: (id: string) => request<ImportCandidateInfo>(`/import/candidates/${id}/lint`, { method: 'POST' }),
  proposeCandidateCapability: (id: string, applicationId: string) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}/propose-capability${query({ applicationId })}`, { method: 'POST' }),
  editCandidateText: (id: string, statement: string) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}`, { method: 'PATCH', body: JSON.stringify({ statement }) }),
  confirmCandidateCapability: (id: string, capabilityId: string) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}/confirm-capability`, {
      method: 'POST', body: JSON.stringify({ capabilityId }),
    }),
  // VYB-0666: an honest 409 ("not configured", "unrecognized type", etc.) is a real
  // ApiError here, same as every other AI-unavailable path — never a silent default.
  confirmCandidateType: (id: string, type: RequirementType) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}/confirm-type`, {
      method: 'POST', body: JSON.stringify({ type }),
    }),
  selectCandidate: (id: string, selected: boolean) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}/select`, { method: 'POST', body: JSON.stringify({ selected }) }),
  setCandidateImportReason: (id: string, reason: string) =>
    request<ImportCandidateInfo>(`/import/candidates/${id}/import-reason`, { method: 'PUT', body: JSON.stringify({ reason }) }),
  commitImportBatch: (batchId: string) =>
    request<ImportCommitOutcome[]>(`/import/batches/${batchId}/commit`, { method: 'POST' }),

  // VYB-0667: document analysis. `documentAnalysis` returns undefined on 204 — a batch
  // that has never been analysed is not an error and must not render as one.
  analyseDocument: (batchId: string) =>
    request<DocumentAnalysisInfo>(`/import/batches/${batchId}/analyse`, { method: 'POST' }),
  documentAnalysis: (batchId: string) =>
    request<DocumentAnalysisInfo | undefined>(`/import/batches/${batchId}/analysis`),
  documentAnalysisHistory: (batchId: string) =>
    request<DocumentAnalysisInfo[]>(`/import/batches/${batchId}/analysis/history`),
  acceptDocumentAnalysis: (id: string) =>
    request<DocumentAnalysisInfo>(`/import/analysis/${id}/accept`, { method: 'POST' }),
  dismissDocumentAnalysis: (id: string, reason: string) =>
    request<DocumentAnalysisInfo>(`/import/analysis/${id}/dismiss`, {
      method: 'POST', body: JSON.stringify({ reason }),
    }),

  // Review rounds
  reviews: () => request<Review[]>('/reviews'),
  review: (id: string) => request<Review>(`/reviews/${id}`),
  openReview: (body: OpenReviewRequest) =>
    request<Review>('/reviews', { method: 'POST', body: JSON.stringify(body) }),
  reviewComments: (id: string) => request<ReviewComment[]>(`/reviews/${id}/comments`),
  addReviewComment: (id: string, body: string, requirementId?: string) =>
    request<ReviewComment>(`/reviews/${id}/comments`, {
      method: 'POST', body: JSON.stringify({ body, requirementId }),
    }),
  signReview: (id: string) => request<Review>(`/reviews/${id}/sign`, { method: 'POST' }),
  closeReview: (id: string) => request<Review>(`/reviews/${id}/close`, { method: 'POST' }),

  // Test evidence
  // VYB-0923 to VYB-0927: manual test management. Writes need the Tester role (server-enforced); reads any signed-in person.
  testPlans: (params: { applicationId?: string } = {}) =>
    request<TestPlan[]>(`/test-plans${query({ applicationId: params.applicationId })}`),
  testSuites: (planId: string) => request<TestSuite[]>(`/test-plans/${planId}/suites`),
  testSuiteCases: (suiteId: string) => request<SuiteCase[]>(`/test-suites/${suiteId}/cases`),
  testRuns: (params: { suiteId?: string; planId?: string; status?: RunStatus } = {}) =>
    request<TestRun[]>(`/test-runs${query({ suiteId: params.suiteId, planId: params.planId, status: params.status })}`),
  testRun: (id: string) => request<TestRunDetail>(`/test-runs/${id}`),
  createTestRun: (suiteId: string, body: { assignedTo?: string; buildLabel?: string } = {}) =>
    request<TestRunDetail>(`/test-suites/${suiteId}/runs`, { method: 'POST', body: JSON.stringify(body) }),
  startTestRun: (id: string) => request<TestRunDetail>(`/test-runs/${id}/start`, { method: 'POST' }),
  completeTestRun: (id: string) => request<TestRunDetail>(`/test-runs/${id}/complete`, { method: 'POST' }),
  retestRun: (id: string, body: { buildLabel?: string } = {}) =>
    request<TestRunDetail>(`/test-runs/${id}/retest`, { method: 'POST', body: JSON.stringify(body) }),
  recordStepResult: (runId: string, stepId: string, result: StepResult, actualResult?: string) =>
    request<TestRunDetail>(`/test-runs/${runId}/steps/${stepId}/result`, { method: 'PUT', body: JSON.stringify({ result, actualResult }) }),
  recordCaseResult: (runId: string, caseId: string, result: StepResult, actualResult?: string) =>
    request<TestRunDetail>(`/test-runs/${runId}/cases/${caseId}/result`, { method: 'PUT', body: JSON.stringify({ result, actualResult }) }),
  stepDefectDraft: (runId: string, stepId: string) => request<DefectDraft>(`/test-runs/${runId}/steps/${stepId}/defect-draft`),
  caseDefectDraft: (runId: string, caseId: string) => request<DefectDraft>(`/test-runs/${runId}/cases/${caseId}/defect-draft`),
  raiseStepDefect: (runId: string, stepId: string, body: RaiseRunDefectBody) =>
    request<RaisedDefect>(`/test-runs/${runId}/steps/${stepId}/defects`, { method: 'POST', body: JSON.stringify(body) }),
  raiseCaseDefect: (runId: string, caseId: string, body: RaiseRunDefectBody) =>
    request<RaisedDefect>(`/test-runs/${runId}/cases/${caseId}/defects`, { method: 'POST', body: JSON.stringify(body) }),
  /** VYB-0927: one evidence file, fetched with the bearer token (a plain link cannot carry it) so it can be saved. */
  downloadEvidence: async (requirementId: string, attachmentId: string, version: number): Promise<Blob> => {
    const token = tokenProvider()
    const res = await fetch(`${BASE}/requirements/${requirementId}/attachments/${attachmentId}/versions/${version}/download`,
      { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },
  passRates: (params: { q?: string; page?: number; size?: number }) =>
    request<Page<PassRate>>(`/quality/pass-rates${query({ q: params.q, page: params.page, size: params.size })}`),
  evidenceSummary: () => request<EvidenceSummary>('/evidence/summary'),
  unverifiedRequirements: () => request<RequirementRef[]>('/evidence/unverified'),
  staleEvidenceRequirements: () => request<RequirementRef[]>('/evidence/stale'),
  draftTestCase: (title: string, description: string | undefined, category: TestCaseSuggestionCategory | undefined, requirementId: string) =>
    request<TestCase>('/test-cases', { method: 'POST', body: JSON.stringify({ title, description, category, requirementId }) }),
  /** VYB-0825: every test case that already exists, browsable — `q` matches the test case's own key/title or the requirement it verifies'. */
  testCases: (params: { q?: string; status?: 'DRAFT' | 'INGESTED'; page?: number; size?: number }) =>
    request<Page<TestCaseListItem>>(`/test-cases${query({ q: params.q, status: params.status, page: params.page, size: params.size })}`),
  /** VYB-0827: one row per requirement that has at least one test case, with counts by category — the "Test cases" tab's top level. */
  testCasesByRequirement: (params: { q?: string; page?: number; size?: number }) =>
    request<Page<RequirementTestCaseSummary>>(`/test-cases/by-requirement${query({ q: params.q, page: params.page, size: params.size })}`),
  /** VYB-0827: one requirement's test cases, fetched on expand — not paged, a requirement rarely has more than a handful. */
  testCasesForRequirement: (requirementId: string) =>
    request<TestCase[]>(`/test-cases/by-requirement/${requirementId}`),
  /** VYB-0828: correcting or expanding a test case already saved — title/description/category only; key, status and the VERIFIES link are untouched. */
  updateTestCase: (id: string, title: string, description: string | undefined, category: TestCaseSuggestionCategory | undefined) =>
    request<TestCase>(`/test-cases/${id}`, { method: 'PATCH', body: JSON.stringify({ title, description, category }) }),
  requirementVerifications: (requirementId: string) =>
    request<VerificationRecord[]>(`/evidence/requirements/${requirementId}/verifications`),

  // Defects
  defects: (params: { state?: DefectState; page?: number; size?: number }) =>
    request<Page<Defect>>(`/defects${query(params)}`),
  raiseDefect: (body: { title: string; severity: DefectSeverity; requirementId?: string; foundIn: FoundIn }) =>
    request<Defect>('/defects', { method: 'POST', body: JSON.stringify(body) }),
  classifyDefect: (id: string, rootCause: RootCause) =>
    request<Defect>(`/defects/${id}/classify`, { method: 'POST', body: JSON.stringify({ rootCause }) }),
  closeDefect: (id: string) => request<Defect>(`/defects/${id}/close`, { method: 'POST' }),
  defectRootCauseSplit: (capabilityId?: string) =>
    request<RootCauseSplit[]>(`/defects/root-cause-split${query({ capabilityId })}`),

  // Clarifications
  clarifications: (requirementId: string) =>
    request<Clarification[]>(`/requirements/${requirementId}/clarifications`),
  raiseClarification: (requirementId: string, body: { question: string; assignedTo?: string; blocksTask?: boolean }) =>
    request<Clarification>(`/requirements/${requirementId}/clarifications`, {
      method: 'POST', body: JSON.stringify(body),
    }),
  answerClarification: (
    id: string,
    body: { answer: string; changeRequestTitle?: string; changeRequestRationale?: string },
  ) => request<Clarification>(`/clarifications/${id}/answer`, { method: 'POST', body: JSON.stringify(body) }),
  blockingClarifications: () => request<Clarification[]>('/clarifications/blocking'),

  // My Work
  myTasks: () => request<Task[]>('/tasks/mine'),
  tasksFor: (userId: string) => request<Task[]>(`/tasks/${userId}`),
  stalledRequirements: () => request<StalledRequirement[]>('/tasks/stalled'),
  /** VYB-0838 (D19): dismisses one derived task, at its current revision. */
  completeTask: (kind: TaskKind, objectId: string) =>
    request<void>('/tasks/complete', { method: 'POST', body: JSON.stringify({ kind, objectId }) }),
  /** Reverses completeTask — the task reappears immediately, not just once stale. */
  reopenTask: (kind: TaskKind, objectId: string) =>
    request<void>('/tasks/reopen', { method: 'POST', body: JSON.stringify({ kind, objectId }) }),
  completedToday: () => request<CompletedTask[]>('/tasks/completed-today'),

  // Notifications
  notifications: () => request<AppNotification[]>('/notifications'),
  unreadNotificationCount: () => request<number>('/notifications/unread-count'),
  markNotificationRead: (id: string) => request<void>(`/notifications/${id}/read`, { method: 'POST' }),
  // VYB-0791: not the browser's native EventSource — it can't send a custom
  // Authorization header at all, and putting the bearer token in the URL's query
  // string instead (the usual EventSource workaround) is exactly the kind of
  // token-in-a-place-it-gets-logged this app has avoided everywhere else a token is
  // handled (see AuthProvider.tsx). A plain fetch() with a real header + a hand-rolled
  // SSE line parser costs a little more code and keeps that same discipline.
  subscribeToNotifications: (onEvent: (data: { kind: string; title: string }) => void): (() => void) => {
    const controller = new AbortController()
    let stopped = false

    async function connect() {
      while (!stopped) {
        try {
          const token = tokenProvider()
          const res = await fetch(`${BASE}/notifications/stream`, {
            headers: token ? { Authorization: `Bearer ${token}` } : {},
            signal: controller.signal,
          })
          if (!res.ok || !res.body) throw new Error(`stream returned ${res.status}`)
          const reader = res.body.getReader()
          const decoder = new TextDecoder()
          let buffer = ''
          for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            buffer += decoder.decode(value, { stream: true })
            let sep
            while ((sep = buffer.indexOf('\n\n')) !== -1) {
              const rawEvent = buffer.slice(0, sep)
              buffer = buffer.slice(sep + 2)
              const dataLine = rawEvent.split('\n').find((l) => l.startsWith('data:'))
              if (dataLine) {
                try { onEvent(JSON.parse(dataLine.slice(5).trim())) } catch { /* not JSON we recognise — skip */ }
              }
            }
          }
        } catch {
          // Connection dropped or never opened — reconnect after a short pause,
          // same self-healing behaviour EventSource gives you natively.
        }
        if (!stopped) await new Promise((r) => setTimeout(r, 3000))
      }
    }

    void connect()
    return () => { stopped = true; controller.abort() }
  },

  // Change requests
  changeRequests: () => request<ChangeRequest[]>('/change-requests'),
  changeRequest: (id: string) => request<ChangeRequest>(`/change-requests/${id}`),
  raiseChangeRequest: (body: { title: string; rationale: string; requirementIds: string[] }) =>
    request<ChangeRequest>('/change-requests', { method: 'POST', body: JSON.stringify(body) }),
  changeRequestImpact: (id: string) =>
    request<ChangeImpact>(`/change-requests/${id}/impact`, { method: 'POST' }),
  decideChangeRequest: (id: string, approve: boolean) =>
    request<ChangeRequest>(`/change-requests/${id}/decide`, { method: 'POST', body: JSON.stringify({ approve }) }),
  applyChangeRequest: (id: string) =>
    request<ChangeRequest>(`/change-requests/${id}/apply`, { method: 'POST' }),

  // Briefs
  /** VYB-0836: applicationId omitted means every application — the Delivery screen's history before one is picked. */
  briefsFor: (applicationId?: string) => request<Brief[]>(`/briefs${query({ applicationId })}`),
  brief: (id: string) => request<Brief>(`/briefs/${id}`),
  generateBrief: (body: { applicationId: string; capabilityIds: string[]; target: BriefTarget; developerId: string; sections?: BriefSection[]; includeAiElaboration?: boolean }) =>
    request<Brief>('/briefs', { method: 'POST', body: JSON.stringify(body) }),
  /** VYB-0818: pushes the generated brief to whatever the "planning" connection is configured with. */
  pushBrief: (id: string) => request<{ success: boolean; statusCode: number; error?: string }>(`/briefs/${id}/push`, { method: 'POST' }),

  // Saved views — one person's own requirement-grid filters
  savedViews: () => request<SavedViewRow[]>('/saved-views'),
  saveView: (body: { name: string; status?: string; priority?: string; type?: string; titleContains?: string; capabilityId?: string }) =>
    request<SavedViewRow>('/saved-views', { method: 'POST', body: JSON.stringify(body) }),
  deleteSavedView: (id: string) => request<void>(`/saved-views/${id}`, { method: 'DELETE' }),

  // Scope signals
  scopeSignals: (capabilityIds: string[]) =>
    request<ScopeSignals>(`/signals${capabilityIds.length ? '?' + capabilityIds.map((id) => `capabilityIds=${id}`).join('&') : ''}`),
  scopeSignalsMedian: () => request<ScopeSignals>('/signals/median'),
  impactVolume: (requirementId: string) => request<ImpactVolume>(`/signals/impact/${requirementId}`),

  // Baselines
  baselines: () => request<Baseline[]>('/baselines'),
  freezeBaseline: (body: { name: string; releaseId?: string; requirementIds: string[] }) =>
    request<Baseline>('/baselines', { method: 'POST', body: JSON.stringify(body) }),
  baselineItems: (id: string) => request<BaselineItem[]>(`/baselines/${id}/items`),
  baselineDiff: (from: string, to: string) => request<BaselineDiff>(`/baselines/diff${query({ from, to })}`),

  // Variants
  variants: () => request<Variant[]>('/variants'),
  createVariant: (name: string) => request<Variant>('/variants', { method: 'POST', body: JSON.stringify({ name }) }),
  markVariantApplies: (variantId: string, requirementId: string) =>
    request<void>(`/variants/${variantId}/applicability/${requirementId}`, { method: 'PUT' }),
  clearVariantApplicability: (variantId: string, requirementId: string) =>
    request<void>(`/variants/${variantId}/applicability/${requirementId}`, { method: 'DELETE' }),
  variantMatrix: (requirementIds: string[]) =>
    request<VariantMatrixRow[]>('/variants/matrix', { method: 'POST', body: JSON.stringify({ requirementIds }) }),

  // Releases
  releases: () => request<VyoogRelease[]>('/releases'),
  createRelease: (name: string) => request<VyoogRelease>('/releases', { method: 'POST', body: JSON.stringify({ name }) }),
  setReleaseTargetDate: (id: string, targetDate: string) =>
    request<VyoogRelease>(`/releases/${id}/target-date`, { method: 'PUT', body: JSON.stringify({ targetDate }) }),
  releaseScope: (id: string) => request<string[]>(`/releases/${id}/scope`),
  commitToRelease: (id: string, requirementId: string, reason: string) =>
    request<void>(`/releases/${id}/scope`, { method: 'POST', body: JSON.stringify({ requirementId, reason }) }),
  removeFromRelease: (id: string, requirementId: string, reason: string) =>
    request<void>(`/releases/${id}/scope/${requirementId}${query({ reason })}`, { method: 'DELETE' }),
  releaseMovements: (id: string, from?: string, to?: string) =>
    request<ScopeMovementView[]>(`/releases/${id}/movements${query({ from, to })}`),
  releaseReadiness: (id: string) => request<ReleaseReadiness>(`/releases/${id}/readiness`),
  releaseBlocked: (id: string) => request<BlockedItem[]>(`/releases/${id}/blocked`),
  /** VYB-0929: undefined (204) when no release is being prepared. */
  currentRelease: () => request<CurrentRelease | undefined>('/releases/current'),
  releaseNotes: (id: string) => request<ReleaseNotes>(`/releases/${id}/notes`),

  // Environments and deployment (ingestion itself is CI-only, not called from here)
  environments: () => request<VyoogEnvironment[]>('/environments'),
  environmentSummaries: () => request<EnvironmentSummary[]>('/environments/summary'),
  recentDeployments: (limit = 8) => request<RecentDeployment[]>(`/deployments/recent?limit=${limit}`),
  blockedFromRelease: (limit = 20) => request<ReleaseBlocker[]>(`/deployments/blocked?limit=${limit}`),
  createEnvironment: (name: string, ordinal: number) =>
    request<VyoogEnvironment>('/environments', { method: 'POST', body: JSON.stringify({ name, ordinal }) }),
  deploymentsFor: (environmentId: string) => request<Deployment[]>(`/environments/${environmentId}/deployments`),
  deploymentPresence: (deploymentId: string) => request<PresenceRow[]>(`/deployments/${deploymentId}/presence`),

  // Design
  designFlowFor: (applicationId: string) => request<DesignFlow>(`/design/flows${query({ applicationId })}`),
  createDesignFlow: (applicationId: string) =>
    request<DesignFlow>(`/design/flows${query({ applicationId })}`, { method: 'POST' }),
  deleteDesignFlow: (id: string) => request<void>(`/design/flows/${id}`, { method: 'DELETE' }),
  /** VYB-0666: draws the flow from the application's requirements — additive, never destructive. */
  generateDesign: (flowId: string, applicationId: string) =>
    request<{ nodesCreated: number; edgesCreated: number; skippedAlreadyDrawn: number }>(
      `/design/flows/${flowId}/generate${query({ applicationId })}`, { method: 'POST' }),
  designNodes: (flowId: string) => request<DesignNode[]>(`/design/flows/${flowId}/nodes`),
  designEdges: (flowId: string) => request<DesignEdge[]>(`/design/flows/${flowId}/edges`),
  addDesignNode: (flowId: string, body: { kind: DesignNodeKind; label: string; note?: string }) =>
    request<DesignNode>(`/design/flows/${flowId}/nodes`, { method: 'POST', body: JSON.stringify(body) }),
  addDesignEdge: (flowId: string, body: { fromNode: string; toNode: string; label?: string }) =>
    request<DesignEdge>(`/design/flows/${flowId}/edges`, { method: 'POST', body: JSON.stringify(body) }),
  deleteDesignNode: (id: string) => request<void>(`/design/nodes/${id}`, { method: 'DELETE' }),
  linkDesignRequirement: (nodeId: string, requirementId: string) =>
    request<void>(`/design/nodes/${nodeId}/requirements/${requirementId}`, { method: 'PUT' }),
  unlinkDesignRequirement: (nodeId: string, requirementId: string) =>
    request<void>(`/design/nodes/${nodeId}/requirements/${requirementId}`, { method: 'DELETE' }),
  designCoverage: (applicationId: string) => request<UncoveredRequirement[]>(`/design/applications/${applicationId}/coverage`),
  designOrphanNodes: (flowId: string) => request<OrphanNode[]>(`/design/flows/${flowId}/orphan-nodes`),
  designCoverageSummary: (applicationId: string, flowId: string) =>
    request<DesignCoverageSummary>(`/design/applications/${applicationId}/flows/${flowId}/coverage-summary`),
  designFlowProgress: (flowId: string) => request<DesignFlowProgress>(`/design/flows/${flowId}/progress`),

  // Administration — users
  users: () => request<AppUserView[]>('/users'),
  /**
   * VYB-0700: adds a directory row ahead of someone's first sign-in so grants can be
   * prepared for them. Creates no credential — Keycloak owns identity; their first
   * sign-in claims this row by email.
   */
  createUser: (body: { email: string; displayName: string; role?: AccessRoleName; scopeType?: ScopeTypeName; scopeId?: string }) =>
    request<AppUserView>('/users', { method: 'POST', body: JSON.stringify(body) }),
  userPicker: (q?: string) => request<UserPickerRow[]>(`/users/picker${query({ q })}`),
  setUserStatus: (id: string, status: string) =>
    request<AppUserView>(`/users/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }),
  setUserDelegate: (id: string, delegateId: string | null) =>
    request<void>(`/users/${id}/delegate`, { method: 'PUT', body: JSON.stringify({ delegateId }) }),
  setUserManager: (id: string, managerId: string | null) =>
    request<void>(`/users/${id}/manager`, { method: 'PUT', body: JSON.stringify({ managerId }) }),

  // Administration — grants
  grants: () => request<Grant[]>('/grants'),
  grantsForUser: (userId: string) => request<Grant[]>(`/grants/user/${userId}`),
  createGrant: (body: { userId: string; role: AccessRoleName; scopeType: ScopeTypeName; scopeId?: string; expiresAt?: string }) =>
    request<Grant>('/grants', { method: 'POST', body: JSON.stringify(body) }),
  revokeGrant: (id: string) => request<void>(`/grants/${id}/revoke`, { method: 'POST' }),
  rolesMatrix: () => request<RoleCapability[]>('/grants/roles-matrix'),

  // Administration — service accounts
  serviceAccounts: () => request<ServiceAccountView[]>('/service-accounts'),
  knownServiceScopes: () => request<string[]>('/service-accounts/known-scopes'),
  createServiceAccount: (body: { name: string; purpose?: string; clientId: string; scopes: string[] }) =>
    request<ServiceAccountView>('/service-accounts', { method: 'POST', body: JSON.stringify(body) }),
  rotateServiceAccountKey: (id: string, newClientId: string) =>
    request<ServiceAccountView>(`/service-accounts/${id}/rotate`, { method: 'POST', body: JSON.stringify({ newClientId }) }),

  // Administration — security and audit
  securityReport: () => request<SecurityReport>('/security'),
  auditSearch: (params: {
    actorId?: string; action?: string; objectType?: string; objectId?: string
    from?: string; to?: string; page?: number; size?: number
  }) => request<Page<AuditEventView>>(`/audit${query(params)}`),

  // Administration — integrations
  integrations: () => request<IntegrationView[]>('/integrations'),
  // VYB-0917: platform administrators only; never returns configuration or a secret
  connectorHealth: () => request<ConnectorStatus[]>('/integrations/health'),
  connectorSyncLog: (key: string, limit = 20) =>
    request<ConnectorSyncEntry[]>(`/integrations/${encodeURIComponent(key)}/sync-log?limit=${limit}`),
  setIntegrationConnected: (key: string, connected: boolean, webhookSecret?: string, owns?: string, direction?: string) =>
    request<IntegrationView>(`/integrations/${key}`, { method: 'PUT', body: JSON.stringify({ connected, webhookSecret, owns, direction }) }),
  setIntegrationConfig: (key: string, configJson: string) =>
    request<IntegrationView>(`/integrations/${key}/config`, { method: 'PUT', body: JSON.stringify({ configJson }) }),
  createIntegration: (key: string, owns: string, direction: string) =>
    request<IntegrationView>('/integrations', { method: 'POST', body: JSON.stringify({ key, owns, direction }) }),
  pushSignals: (capabilityIds: string[]) =>
    request<{ success: boolean; statusCode: number; error?: string }>(
      `/signals/push${capabilityIds.length ? '?' + capabilityIds.map((id) => `capabilityIds=${id}`).join('&') : ''}`,
      { method: 'POST' }),

  // Administration — settings
  settings: () => request<AppConfigView>('/settings'),
  setReqKeyPrefix: (prefix: string) => request<void>('/settings/req-key-prefix', { method: 'PUT', body: JSON.stringify({ prefix }) }),
  previewStageThreshold: (status: string, days: number) =>
    request<number>(`/settings/stage-thresholds/preview${query({ status, days })}`),
  setStageThresholds: (thresholds: Record<string, number>) =>
    request<void>('/settings/stage-thresholds', { method: 'PUT', body: JSON.stringify(thresholds) }),
  setMaxExternalGrantDays: (days: number) => request<void>('/settings/max-external-grant-days', { method: 'PUT', body: JSON.stringify({ days }) }),
  setStaleKeyAgeDays: (days: number) => request<void>('/settings/stale-key-age-days', { method: 'PUT', body: JSON.stringify({ days }) }),
  setKeyRotationOverlapDays: (days: number) => request<void>('/settings/key-rotation-overlap-days', { method: 'PUT', body: JSON.stringify({ days }) }),
  setAuditRetentionDays: (days: number) => request<void>('/settings/audit-retention-days', { method: 'PUT', body: JSON.stringify({ days }) }),
  setNoisyDetectorDismissalCeiling: (ceiling: number) =>
    request<void>('/settings/noisy-detector-dismissal-ceiling', { method: 'PUT', body: JSON.stringify({ ceiling }) }),
  setAiCallsPerRunLimit: (days: number) => request<void>('/settings/ai-calls-per-run-limit', { method: 'PUT', body: JSON.stringify({ days }) }),
  setEmbeddingModel: (model: string) => request<void>('/settings/embedding-model', { method: 'PUT', body: JSON.stringify({ model }) }),
  suspend: (reason: string) => request<void>('/settings/suspend', { method: 'POST', body: JSON.stringify({ reason }) }),
  resume: () => request<void>('/settings/resume', { method: 'POST' }),
  exportSummary: () => request<Record<string, number>>('/settings/export/summary'),
  exportTenant: () => request<TenantExportManifest>('/settings/export'),
  /** VYB-0732: the real full export — the same manifest, zipped together with every attachment's actual bytes fetched live from object storage. */
  downloadTenantExportBundle: async (): Promise<Blob> => {
    const token = tokenProvider()
    const res = await fetch(`${BASE}/settings/export/bundle`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
    if (!res.ok) throw await problemFrom(res)
    return res.blob()
  },
  auditPartitions: () => request<AuditPartitionInfo[]>('/settings/audit-partitions'),
  archiveAuditPartitions: () => request<{ archived: string[] }>('/settings/audit-partitions/archive', { method: 'POST' }),
  resetPreview: () => request<ResetPreview>('/settings/reset/preview'),
  resetTenant: (confirmationPhrase: string) =>
    request<void>('/settings/reset', { method: 'POST', body: JSON.stringify({ confirmationPhrase }) }),

  // Global search
  search: (q: string) => request<SearchResult[]>(`/search${query({ q })}`),
}
