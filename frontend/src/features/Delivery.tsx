import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Download, Send, Sparkles, Code2, Copy, History as HistoryIcon } from 'lucide-react'
import { ApiError, api, type Brief, type BriefSection, type BriefTarget, type RequirementStatus } from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog'
import { UserPicker } from '@/shared/ui/UserPicker'

type Tab = 'briefs' | 'signals' | 'impact'

const TARGETS: { value: BriefTarget; label: string; filename: string; badge: string; note: string }[] = [
  { value: 'CLAUDE_CODE', label: 'Claude Code', filename: 'brief-claude-code.md', badge: 'Agent',
    note: 'Includes commit-trailer instructions and this codebase\'s test-naming convention.' },
  { value: 'CODEX', label: 'Codex', filename: 'brief-codex.md', badge: 'Agent',
    note: 'Includes commit-trailer instructions; no test-naming convention section.' },
  { value: 'CURSOR', label: 'Cursor', filename: 'brief-cursor.md', badge: 'Agent',
    note: 'Includes commit-trailer instructions, defers to the project rules file, and asks for no edits outside the requirements in scope.' },
  { value: 'HUMAN', label: 'Human', filename: 'brief-human.md', badge: 'Person',
    note: 'Friendlier prose, no commit-trailer block at all.' },
]

const SIGNAL_LABELS: Record<string, string> = {
  requirementCount: 'Requirements', acceptanceCriteriaCount: 'Acceptance criteria', dependencyDepth: 'Dependency depth',
  crossApplicationReach: 'Cross-app reach', ambiguityLoad: 'Ambiguity load', openGaps: 'Open gaps',
  changeRate: 'Change rate (30d)', novelty: 'Novelty (30d)',
}

/** VYB-0500–0508. */
export function Delivery() {
  const [tab, setTab] = useState<Tab>('briefs')
  return (
    <Page
      title="Delivery"
      desc="Implementation briefs, exact scope signals, and impact volume. No effort estimate, ever."
    >
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        {(['briefs', 'signals', 'impact'] as Tab[]).map((t) => (
          <button key={t} className={`btn${t === tab ? ' pri' : ''}`} onClick={() => setTab(t)}>
            {t === 'briefs' ? 'Briefs' : t === 'signals' ? 'Scope signals' : 'Impact'}
          </button>
        ))}
      </div>
      {tab === 'briefs' && <BriefsTab />}
      {tab === 'signals' && <SignalsTab />}
      {tab === 'impact' && <ImpactTab />}
    </Page>
  )
}

function useAppCapabilityPicker() {
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [capabilityIds, setCapabilityIds] = useState<string[]>([])
  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId,
  })
  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', applicationId], queryFn: () => api.capabilities(applicationId), enabled: !!applicationId,
  })
  return { productId, setProductId, applicationId, setApplicationId, capabilityIds, setCapabilityIds,
    products, applications, capabilities }
}

/**
 * The optional sections, with the wording the prototype used for each — the description
 * is what makes an unfamiliar toggle decidable without opening the generator's source.
 * Every entry maps to a real `BriefSection` the backend can switch off; the prototype's
 * own list carried several the generator has nothing to fill, and those are absent here
 * rather than present and inert.
 */
const BRIEF_SECTIONS: { key: BriefSection; title: string; desc: string }[] = [
  { key: 'CONTEXT', title: 'Context', desc: 'What the agent is being asked to do, written for the chosen target.' },
  { key: 'CATEGORY_REVIEW', title: 'Category review', desc: 'Counts per category and the test-coverage line, including a missing-NFR callout.' },
  { key: 'ACCEPTANCE_CRITERIA', title: 'Acceptance criteria', desc: 'The criteria under each requirement. Without them the agent has nothing to write tests against.' },
  { key: 'OPEN_QUESTIONS', title: 'Open questions', desc: 'Requirements with no criteria recorded, gathered so the agent asks rather than guesses.' },
  { key: 'DEFINITION_OF_DONE', title: 'Definition of done', desc: 'Test-per-criterion, and moving the requirement out of Draft when it is finished.' },
  { key: 'COMMIT_TRAILER', title: 'Commit trailer format', desc: 'Requirement ids in commit messages, so the code half of the trace chain is built as work happens.' },
  { key: 'QUALITY_APPENDIX', title: 'Quality appendix', desc: 'Vyoog quality score per requirement, so the agent knows which parts rest on firmer ground.' },
]

const ALL_SECTIONS = BRIEF_SECTIONS.map((s) => s.key)

/**
 * Colours markdown Vyoog generated itself, so the rules can be this blunt — the
 * generator's output shape is known, not arbitrary user input. Escapes first, then marks
 * up; anything not matched is left as plain text.
 */
function colourMarkdown(md: string): string {
  return md
    .replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c] as string))
    .replace(/^(#{1,2} .*)$/gm, '<span class="h">$1</span>')
    .replace(/^(#{3,4} .*)$/gm, '<span class="h2">$1</span>')
    .replace(/^(&gt; .*)$/gm, '<span class="q">$1</span>')
    .replace(/^(\s*- .*)$/gm, '<span class="c">$1</span>')
    .replace(/(`?\b[A-Z]{2,}-\d+\b`?)/g, '<span class="id">$1</span>')
    .replace(/^(---)$/gm, '<span class="rule">$1</span>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<span class="b">$1</span>')
}

/** `VY-<app-slug>-<scope-slug>-implementation-brief.md`, the prototype's own naming. */
function briefFilename(appName: string, capNames: string[], appWise: boolean): string {
  const slug = (t: string) => t.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  const scope = appWise || capNames.length !== 1 ? appName : capNames[0]
  return `VY-${slug(appName)}-${slug(scope)}-implementation-brief.md`
}

/**
 * Total, approved, and brief-ready (approved + has a test case) counts per capability.
 * Three `size: 1` queries — only totalElements is wanted, so none of them pulls a
 * capability's rows. Lifted out of the row component so the estimate box below reads the
 * same numbers the rows show rather than counting twice.
 *
 * VYB-0831: "ready" is a stricter figure than "approved" — the delivery brief now only
 * ever carries a requirement that is both, so gating and warnings below key off it
 * instead of approved alone, the same "every warning is computed from a figure the
 * server returned, never a guess" discipline this file already holds itself to.
 */
function useCapabilityCounts(capabilityIds: string[]) {
  return useQueries({
    queries: capabilityIds.flatMap((id) => [
      { queryKey: ['cap-count', id, 'all'], queryFn: () => api.requirements({ capabilityId: id, size: 1 }) },
      { queryKey: ['cap-count', id, 'APPROVED'], queryFn: () => api.requirements({ capabilityId: id, status: 'APPROVED' as RequirementStatus, size: 1 }) },
      { queryKey: ['cap-count', id, 'READY'], queryFn: () => api.requirements({ capabilityId: id, status: 'APPROVED' as RequirementStatus, hasTestCase: true, size: 1 }) },
    ]),
    combine: (results) => {
      const counts = new Map<string, { total: number; approved: number; ready: number }>()
      capabilityIds.forEach((id, i) => {
        const total = results[i * 3]?.data?.totalElements
        const approved = results[i * 3 + 1]?.data?.totalElements
        const ready = results[i * 3 + 2]?.data?.totalElements
        if (total !== undefined && approved !== undefined && ready !== undefined) counts.set(id, { total, approved, ready })
      })
      return counts
    },
  })
}

/**
 * One capability row. It shows ready-of-total, not just total, because ready — approved
 * and carrying at least one test case — is what a brief will actually carry: a row
 * reading "40" beside a brief that turns out to hold two is the confusion this screen
 * exists to avoid.
 */
function CapabilityRow({ name, counts, checked, onToggle }: {
  name: string; counts?: { total: number; approved: number; ready: number }; checked: boolean; onToggle: () => void
}) {
  const noneApproved = counts && counts.approved === 0 && counts.total > 0
  const approvedButNotReady = counts && counts.approved > 0 && counts.ready === 0
  return (
    <button className={`capsel-row${checked ? ' on' : ''}`} onClick={onToggle} aria-pressed={checked}>
      <span className="capsel-cb">{checked ? '✓' : ''}</span>
      <span className="capsel-nm" title={name}>{name}</span>
      {noneApproved && <span className="capsel-g" title="Requirements here, but none approved">0 ready</span>}
      {approvedButNotReady && (
        <span className="capsel-g" title="Approved here, but none have a test case yet">0 test cases</span>
      )}
      <span className="capsel-n" title="ready (approved + has a test case) of total">
        {counts ? `${counts.ready}/${counts.total}` : '·'}
      </span>
    </button>
  )
}

function BriefsTab() {
  const qc = useQueryClient()
  const picker = useAppCapabilityPicker()
  const [target, setTarget] = useState<BriefTarget>('CLAUDE_CODE')
  const [developerId, setDeveloperId] = useState('')
  const [sections, setSections] = useState<BriefSection[]>(ALL_SECTIONS)
  // VYB-0817: a separate, explicit opt-in — not one of BRIEF_SECTIONS, since it calls a
  // real AI provider (a real cost and a few extra seconds) rather than just switching
  // what Vyoog's own data renders. Off by default.
  const [includeAiElaboration, setIncludeAiElaboration] = useState(false)
  const [preview, setPreview] = useState<{ id: string; content: string; filename: string } | null>(null)
  const [showPush, setShowPush] = useState(false)
  const [pushResult, setPushResult] = useState('')
  const [copied, setCopied] = useState(false)
  const [showHistory, setShowHistory] = useState(false)

  // VYB-0819: every generate already persists a Brief row (BriefService.generate saves
  // it before returning) — this just surfaces what was already being saved, rather than
  // changing when saving happens. VYB-0836: always on, not gated behind picking a
  // product/application — an empty picker still means "show me what's already been
  // generated", not "there's nothing to show yet".
  const { data: history } = useQuery({
    queryKey: ['briefs', picker.applicationId],
    queryFn: () => api.briefsFor(picker.applicationId || undefined),
  })
  const { data: allUsers } = useQuery({ queryKey: ['users'], queryFn: api.users })
  const nameFor = (userId: string) => allUsers?.find((u) => u.id === userId)?.displayName ?? userId.slice(0, 8)

  const liveCaps = picker.capabilities?.filter((c) => !c.archived) ?? []
  const counts = useCapabilityCounts(liveCaps.map((c) => c.id))
  const appWise = picker.capabilityIds.length === 0 || picker.capabilityIds.length === liveCaps.length
  const selectedCapIds = picker.capabilityIds.length > 0 ? picker.capabilityIds : liveCaps.map((c) => c.id)

  // Real, server-computed scope figures — the same endpoint the Scope signals tab uses,
  // so the estimate here and the numbers there can never disagree.
  const { data: signals } = useQuery({
    queryKey: ['scope-signals', selectedCapIds],
    queryFn: () => api.scopeSignals(selectedCapIds),
    enabled: selectedCapIds.length > 0,
  })

  const generate = useMutation({
    mutationFn: () => api.generateBrief({
      applicationId: picker.applicationId, capabilityIds: picker.capabilityIds, target, developerId, sections,
      includeAiElaboration,
    }),
    onSuccess: (brief) => {
      const capNames = liveCaps.filter((c) => picker.capabilityIds.includes(c.id)).map((c) => c.name)
      const appName = picker.applications?.find((a) => a.id === picker.applicationId)?.name ?? 'app'
      setPreview({ id: brief.id, content: brief.content, filename: briefFilename(appName, capNames, appWise) })
      void qc.invalidateQueries({ queryKey: ['briefs', picker.applicationId] })
    },
  })

  // VYB-0818: sends the generated brief out to whatever the "planning" connection is
  // configured with — the destination itself is chosen later, in Administration, by
  // whoever knows what that tool is. Until it is, this refuses by name (Principle 8)
  // rather than pretending to succeed.
  const pushToPlanning = useMutation({
    mutationFn: () => api.pushBrief(preview!.id),
  })

  // VYB-0831: ready — approved AND carrying a test case — is what a brief actually
  // carries now, so it gates the button, not approved alone. Warning and then generating
  // anyway is what produced a file that looked like a brief and held no requirements.
  const selectedCounts = selectedCapIds.map((id) => counts.get(id)).filter(Boolean) as { total: number; approved: number; ready: number }[]
  const approved = selectedCounts.reduce((n, c) => n + c.approved, 0)
  const ready = selectedCounts.reduce((n, c) => n + c.ready, 0)
  const notApproved = selectedCounts.reduce((n, c) => n + (c.total - c.approved), 0)
  const approvedNoTestCase = approved - ready
  // Distinguish "counts haven't arrived" from "genuinely none" — blocking during the
  // first render would make the button look broken on every page load.
  const countsReady = selectedCapIds.length > 0 && selectedCounts.length === selectedCapIds.length
  const nothingToBrief = countsReady && ready === 0

  const canGenerate = !!picker.applicationId && !!developerId.trim()
    && selectedCapIds.length > 0 && !nothingToBrief && !generate.isPending

  const download = () => {
    if (!preview) return
    const blob = new Blob([preview.content], { type: 'text/markdown' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = preview.filename; a.click()
    URL.revokeObjectURL(url)
  }

  const copy = async () => {
    if (!preview) return
    await navigator.clipboard.writeText(preview.content)
    setCopied(true)
    setTimeout(() => setCopied(false), 1600)
  }

  // Reopens a previously-saved brief exactly as generated — the scope it actually
  // covered (which capabilities) isn't reconstructed here, only the application name,
  // since capability selection was never part of what Brief itself persists. VYB-0836:
  // reads the brief's own applicationName rather than whatever's currently picked in
  // the sidebar — history can now open a brief from a different application than the
  // one selected (or none at all).
  const openHistoric = (b: Brief) => {
    setPreview({ id: b.id, content: b.content, filename: briefFilename(b.applicationName, [], true) })
    setShowHistory(false)
  }

  // Every warning is computed from a figure the server returned, never a guess.
  const warnings: { text: string; level: 'crit' | 'warn' }[] = []
  if (selectedCounts.length > 0 && ready === 0) {
    warnings.push({ level: 'crit', text: approved > 0
      ? `${approved} requirement${approved === 1 ? '' : 's'} here ${approved === 1 ? 'is' : 'are'} approved, but none ${approved === 1 ? 'has' : 'have'} a test case yet, so the brief would be empty. Generate or draft one first (Quality → Verification) — only requirements with a test case are briefed.`
      : notApproved > 0
      ? `None of the ${notApproved} requirements here are approved, so the brief would be empty. Approve them in Requirements first — only approved requirements with a test case are briefed.`
      : 'Nothing in scope. The brief would carry a header and no requirements.' })
  } else {
    if (notApproved > 0) {
      warnings.push({ level: 'warn', text: `${notApproved} requirement${notApproved === 1 ? '' : 's'} in these capabilities ${notApproved === 1 ? 'is' : 'are'} not approved and will be left out. The brief names them as excluded.` })
    }
    if (approvedNoTestCase > 0) {
      warnings.push({ level: 'warn', text: `${approvedNoTestCase} approved requirement${approvedNoTestCase === 1 ? '' : 's'} here ${approvedNoTestCase === 1 ? 'has' : 'have'} no test case yet and will be left out. The brief names ${approvedNoTestCase === 1 ? 'it' : 'them'} as excluded.` })
    }
  }
  if (signals) {
    if (signals.requirementCount > 120) {
      warnings.push({ level: 'warn', text: `${signals.requirementCount} requirements is more than one session can reasonably absorb. Consider generating one brief per capability instead.` })
    }
    if (signals.requirementCount > 0 && signals.acceptanceCriteriaCount === 0) {
      warnings.push({ level: 'crit', text: 'No requirement in scope has an acceptance criterion. The agent has nothing to write tests against and will be told to ask rather than guess.' })
    } else if (signals.acceptanceCriteriaCount < signals.requirementCount) {
      warnings.push({ level: 'warn', text: `Only ${signals.acceptanceCriteriaCount} acceptance criteria across ${signals.requirementCount} requirements. The thin ones are listed under Open questions.` })
    }
    if (signals.openGaps > 8) {
      warnings.push({ level: 'warn', text: `${signals.openGaps} open gaps in the selected capabilities. They are carried into the brief rather than hidden from the agent.` })
    }
  }

  return (
    <>
      <div className="bg-split" style={{ height: 'calc(100vh - 260px)', minHeight: 460, border: '1px solid var(--line)', borderRadius: 10 }}>
        {/* ---- scope and options ---- */}
        <aside className="bg-left">
          <div className="bg-sec">Scope</div>
          <div className="field">
            <label className="label">Product</label>
            <select className="select" value={picker.productId}
              onChange={(e) => { picker.setProductId(e.target.value); picker.setApplicationId(''); picker.setCapabilityIds([]) }}>
              <option value="">—</option>
              {picker.products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="label">Application</label>
            <select className="select" value={picker.applicationId} disabled={!picker.productId}
              onChange={(e) => { picker.setApplicationId(e.target.value); picker.setCapabilityIds([]) }}>
              <option value="">—</option>
              {picker.applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 6, margin: '4px 0 7px' }}>
            <span className="eyebrow" style={{ fontSize: 8.5 }}>Capabilities</span>
            <div className="sp" />
            <button className="btn" style={{ padding: '3px 8px', fontSize: 10.5 }}
              onClick={() => picker.setCapabilityIds(liveCaps.map((c) => c.id))}>App-wise</button>
            <button className="btn" style={{ padding: '3px 8px', fontSize: 10.5 }}
              onClick={() => picker.setCapabilityIds([])}>Clear</button>
          </div>
          <div className="capsel">
            {!picker.applicationId && <p className="hint muted" style={{ padding: '8px 0' }}>Pick an application first.</p>}
            {picker.applicationId && liveCaps.length === 0 && (
              <p className="hint muted" style={{ padding: '8px 0' }}>This application has no capabilities yet. Add one in Portfolio.</p>
            )}
            {liveCaps.map((c) => (
              <CapabilityRow
                key={c.id} name={c.name} counts={counts.get(c.id)}
                checked={picker.capabilityIds.includes(c.id)}
                onToggle={() => picker.setCapabilityIds((prev) =>
                  prev.includes(c.id) ? prev.filter((id) => id !== c.id) : [...prev, c.id])}
              />
            ))}
            {picker.applicationId && liveCaps.length > 0 && picker.capabilityIds.length === 0 && (
              <p className="hint muted" style={{ paddingTop: 6 }}>None ticked — the brief covers every capability above.</p>
            )}
          </div>

          <div className="bg-sec">Include</div>
          {BRIEF_SECTIONS.map((s) => (
            <button key={s.key} className={`opt-row${sections.includes(s.key) ? ' on' : ''}`}
              aria-pressed={sections.includes(s.key)}
              onClick={() => setSections((prev) =>
                prev.includes(s.key) ? prev.filter((k) => k !== s.key) : [...prev, s.key])}>
              <span className="opt-cb">{sections.includes(s.key) ? '✓' : ''}</span>
              <span>
                <span className="opt-t">{s.title}</span>
                <span className="opt-d">{s.desc}</span>
              </span>
            </button>
          ))}
          <button className={`opt-row${includeAiElaboration ? ' on' : ''}`}
            aria-pressed={includeAiElaboration}
            onClick={() => setIncludeAiElaboration((v) => !v)}>
            <span className="opt-cb">{includeAiElaboration ? '✓' : ''}</span>
            <span>
              <span className="opt-t">AI elaboration</span>
              <span className="opt-d">
                Expands each requirement's statement into a few sentences of detailed prose for the developer — grounded only in the statement and its acceptance criteria, shown alongside the original, never replacing it. Calls a real AI provider, so this takes a few extra seconds.
              </span>
            </span>
          </button>

          <div className="bg-sec">Target</div>
          <div className="field">
            <select className="select" value={target} onChange={(e) => setTarget(e.target.value as BriefTarget)}>
              {TARGETS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <span className="hint">{TARGETS.find((t) => t.value === target)?.note}</span>
          </div>

          <div className="bg-sec">Developer</div>
          <UserPicker value={developerId} onSelect={setDeveloperId} placeholder="Search for a developer…" />

          {/* Scope figures, straight from the signals endpoint. Deliberately no effort or
              duration anywhere — that is Principle 7, and the prototype's own estimate box
              carried none either. */}
          <div className="bg-est">
            <div className="bg-est-r"><span>Capabilities selected</span>
              <span>{picker.capabilityIds.length || liveCaps.length} of {liveCaps.length}</span></div>
            <div className="bg-est-r"><span>Ready (approved + test case) — in the brief</span>
              <span style={{ color: ready === 0 ? 'var(--crit)' : 'var(--ok)' }}>{ready}</span></div>
            <div className="bg-est-r"><span>Not approved — excluded</span>
              <span style={{ color: notApproved > 0 ? 'var(--high)' : undefined }}>{notApproved}</span></div>
            <div className="bg-est-r"><span>Approved, no test case yet — excluded</span>
              <span style={{ color: approvedNoTestCase > 0 ? 'var(--high)' : undefined }}>{approvedNoTestCase}</span></div>
            <div className="bg-est-r"><span>Acceptance criteria</span>
              <span>{signals ? signals.acceptanceCriteriaCount : '—'}</span></div>
            <div className="bg-est-r"><span>Open gaps carried</span>
              <span style={{ color: signals && signals.openGaps > 0 ? 'var(--high)' : undefined }}>
                {signals ? signals.openGaps : '—'}</span></div>
            <div className="bg-est-r"><span>Ambiguity load</span>
              <span style={{ color: signals && signals.ambiguityLoad > 0 ? 'var(--high)' : undefined }}>
                {signals ? signals.ambiguityLoad : '—'}</span></div>
          </div>

          <div style={{ marginTop: 11 }}>
            {warnings.map((w) => <div key={w.text} className={`bg-warn ${w.level}`}>{w.text}</div>)}
          </div>

          <button className="btn pri" style={{ width: '100%', marginTop: 14, height: 36, justifyContent: 'center' }}
            disabled={!canGenerate} onClick={() => generate.mutate()}>
            <Sparkles /> {generate.isPending ? 'Generating…' : 'Generate brief'}
          </button>
          {nothingToBrief && (
            <p className="hint" style={{ marginTop: 8, color: 'var(--crit)' }}>
              {approved > 0
                ? 'These are approved, but none has a test case yet, so there is nothing to brief. Generate or draft a test case for one first — Quality → Verification.'
                : notApproved > 0
                ? <>Nothing here is approved yet, so there is nothing to brief.{' '}
                    <Link to="/requirements">Approve them in Requirements</Link> — select the rows, then Bulk edit → Status.</>
                : 'These capabilities hold no requirements at all.'}
            </p>
          )}
          {generate.isError && (
            <p className="err-text" style={{ marginTop: 8 }}>
              {generate.error instanceof ApiError
                ? (generate.error.detail ?? generate.error.title)
                : 'Could not generate the brief.'}
            </p>
          )}
        </aside>

        {/* ---- preview ---- */}
        <div className="bg-right">
          {preview && !showHistory && (
            <div className="mdbar">
              <Code2 style={{ width: 15, height: 15, color: 'var(--brand)' }} />
              <span className="mdname">{preview.filename}</span>
              <span className="badge st-verified">generated</span>
              <div className="sp" />
              <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }} onClick={copy}>
                <Copy /> {copied ? 'Copied' : 'Copy'}
              </button>
              <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }}
                disabled={!canGenerate} onClick={() => generate.mutate()}>Regenerate</button>
              <button className="btn pri" style={{ padding: '4px 9px', fontSize: 11 }} onClick={download}>
                <Download /> Download .md
              </button>
              <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }}
                disabled={pushToPlanning.isPending} onClick={() => pushToPlanning.mutate()}
                title="Sends this brief to whatever the planning-tool integration is configured with in Administration.">
                <Send /> {pushToPlanning.isPending ? 'Pushing…' : 'Push to planning tool'}
              </button>
            </div>
          )}
          {/* VYB-0836: always visible — history exists independently of whatever the
              sidebar currently has picked, and is exactly what "no product/application
              selected" should still be able to show. */}
          <div className="mdbar">
            <HistoryIcon style={{ width: 15, height: 15, color: 'var(--brand)' }} />
            <span className="mdname">History</span>
            <span className="badge">{history?.length ?? 0} saved</span>
            {!picker.applicationId && <span className="hint muted" style={{ fontSize: 10.5 }}>across every application</span>}
            <div className="sp" />
            <button className="btn" style={{ padding: '4px 9px', fontSize: 11 }}
              onClick={() => setShowHistory((v) => !v)}>
              {showHistory ? 'Back to preview' : 'View history'}
            </button>
          </div>
          {preview && !showHistory && (pushToPlanning.isSuccess || pushToPlanning.isError) && (
            <p className={pushToPlanning.isSuccess && pushToPlanning.data?.success ? 'hint muted' : 'err-text'} style={{ padding: '0 10px' }}>
              {pushToPlanning.isSuccess
                ? (pushToPlanning.data.success ? 'Pushed to the planning tool.' : `Push failed: ${pushToPlanning.data.error ?? 'unknown error'}`)
                : (pushToPlanning.error instanceof ApiError ? (pushToPlanning.error.detail ?? pushToPlanning.error.title) : 'Could not push this brief.')}
            </p>
          )}
          <div className="mdscroll">
            {showHistory ? (
              !history || history.length === 0 ? (
                <div className="bg-empty">
                  <span className="ring"><HistoryIcon /></span>
                  <h4>{picker.applicationId ? 'No briefs saved yet for this application' : 'No briefs saved yet'}</h4>
                  <p>Every "Generate brief" is saved automatically — they'll show up here.</p>
                </div>
              ) : (
                <div style={{ padding: 8 }}>
                  {history.map((b) => (
                    <button key={b.id} className="opt-row" style={{ width: '100%', textAlign: 'left' }}
                      onClick={() => openHistoric(b)}>
                      <span>
                        <span className="opt-t">
                          {new Date(b.generatedAt).toLocaleString()} — {b.target}
                          {b.stale && <span className="badge st-high" style={{ marginLeft: 6 }}>stale</span>}
                        </span>
                        <span className="opt-d">{b.applicationName} · Developer: {nameFor(b.developerId)}</span>
                      </span>
                    </button>
                  ))}
                </div>
              )
            ) : preview ? (
              <pre className="md" dangerouslySetInnerHTML={{ __html: colourMarkdown(preview.content) }} />
            ) : (
              <div className="bg-empty">
                <span className="ring"><Code2 /></span>
                <h4>No brief generated yet</h4>
                <p>
                  Pick an application on the left, tick the capabilities you want, and generate.
                  Leaving every capability unticked produces an app-wide brief.
                </p>
              </div>
            )}
          </div>
        </div>
      </div>

      {preview && (
        <div style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
          <button className="btn" onClick={() => setShowPush(true)}><Send /> Push to the coding agent</button>
          {pushResult && <span className="hint muted">{pushResult}</span>}
        </div>
      )}
      {showPush && (
        <ConfirmDialog
          title="Push this brief?"
          description="Sends the generated markdown to the configured coding-agent integration."
          confirmLabel="Push"
          onCancel={() => setShowPush(false)}
          onConfirm={() => { setShowPush(false); setPushResult('No coding-agent integration is connected — configure one in Administration.') }}
        />
      )}
    </>
  )
}

function SignalsTab() {
  const picker = useAppCapabilityPicker()
  const [revealed, setRevealed] = useState<string | null>(null)
  const [showPushConfirm, setShowPushConfirm] = useState(false)
  const { data: signals } = useQuery({
    queryKey: ['scope-signals', picker.applicationId, picker.capabilityIds],
    queryFn: () => api.scopeSignals(picker.capabilityIds),
    enabled: picker.capabilityIds.length > 0,
  })
  const { data: median } = useQuery({ queryKey: ['scope-signals-median'], queryFn: api.scopeSignalsMedian })
  const { data: integrations } = useQuery({ queryKey: ['integrations'], queryFn: api.integrations })
  const planning = integrations?.find((i) => i.key === 'planning')
  const push = useMutation({ mutationFn: () => api.pushSignals(picker.capabilityIds) })

  return (
    <>
      <div className="row toolbar" style={{ marginBottom: 14 }}>
        <div className="field">
          <label className="label">Product</label>
          <select className="select" value={picker.productId}
            onChange={(e) => { picker.setProductId(e.target.value); picker.setApplicationId(''); picker.setCapabilityIds([]) }}>
            <option value="">—</option>
            {picker.products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label">Application</label>
          <select className="select" value={picker.applicationId} disabled={!picker.productId}
            onChange={(e) => { picker.setApplicationId(e.target.value); picker.setCapabilityIds([]) }}>
            <option value="">—</option>
            {picker.applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div className="field" style={{ flex: 2 }}>
          <label className="label">Capabilities in scope</label>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            {picker.capabilities?.filter((c) => !c.archived).map((c) => (
              <label key={c.id} style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 12.5 }}>
                <input
                  type="checkbox" checked={picker.capabilityIds.includes(c.id)}
                  onChange={(e) => picker.setCapabilityIds((prev) =>
                    e.target.checked ? [...prev, c.id] : prev.filter((id) => id !== c.id))}
                />
                {c.name}
              </label>
            ))}
          </div>
        </div>
      </div>

      {picker.capabilityIds.length === 0 && (
        <Empty title="Pick at least one capability" desc="The eight signals are computed for whatever scope you check above." />
      )}

      {signals && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 12 }}>
          {Object.entries(SIGNAL_LABELS).map(([key, label]) => {
            const value = (signals as unknown as Record<string, number>)[key]
            const medianValue = median ? (median as unknown as Record<string, number>)[key] : undefined
            const isRatio = key === 'changeRate' || key === 'novelty'
            return (
              <div key={key} className="card">
                <div className="eyebrow">{label}</div>
                <div style={{ fontSize: 22, fontWeight: 700, margin: '4px 0' }}>
                  {isRatio ? `${Math.round(value * 100)}%` : value}
                </div>
                {medianValue !== undefined && (
                  <div className="muted" style={{ fontSize: 11 }}>
                    portfolio median: {isRatio ? `${Math.round(medianValue * 100)}%` : medianValue}
                  </div>
                )}
                <button
                  className="btn" style={{ padding: '2px 6px', marginTop: 6, fontSize: 10 }}
                  onClick={() => setRevealed(revealed === key ? null : key)}
                >
                  {revealed === key ? 'Hide query' : 'Show query'}
                </button>
                {revealed === key && (
                  <pre className="mono" style={{ fontSize: 9.5, marginTop: 6, whiteSpace: 'pre-wrap', color: 'var(--tx-3)' }}>
                    {Object.entries(signals.queries).find(([q]) => q.startsWith(key))?.[1] ?? '(query not found)'}
                  </pre>
                )}
              </div>
            )
          })}
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 14 }}>
        There is deliberately no ninth card combining these into an effort or duration estimate — see VYB-0463.
      </p>

      {/* VYB-0465/0505: a real push, to a real configured URL — named exactly what
          it does rather than a generic "Are you sure?", and disabled outright when
          there's nowhere configured to send it, rather than failing after the click. */}
      <div className="divider" />
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <button
          className="btn pri" disabled={picker.capabilityIds.length === 0 || !planning?.config}
          onClick={() => setShowPushConfirm(true)}
        >
          <Send /> Push signals to delivery tool
        </button>
        {!planning?.config && (
          <span className="hint muted">No push URL configured for "planning" yet — see Administration → Connected systems.</span>
        )}
        {push.data && (
          <span className="hint" style={{ color: push.data.success ? 'var(--ok)' : 'var(--crit)' }}>
            {push.data.success ? `Delivered (HTTP ${push.data.statusCode})` : `Failed: ${push.data.error}`}
          </span>
        )}
      </div>

      {showPushConfirm && (
        <ConfirmDialog
          title={`Push these ${picker.capabilityIds.length} capabilities' signals to "planning"?`}
          description={`Sends the eight figures shown above — requirement count, acceptance criteria count, dependency depth, cross-application reach, ambiguity load, open gaps, change rate, and novelty — as one signed JSON POST to the URL configured for "planning". Nothing else about this scope is sent.`}
          confirmLabel="Push now"
          onConfirm={() => { push.mutate(); setShowPushConfirm(false) }}
          onCancel={() => setShowPushConfirm(false)}
        />
      )}
    </>
  )
}

function ImpactTab() {
  const [requirementId, setRequirementId] = useState('')
  const [activeId, setActiveId] = useState('')
  const { data, isError } = useQuery({
    queryKey: ['impact', activeId], queryFn: () => api.impactVolume(activeId), enabled: !!activeId,
  })

  return (
    <>
      <p className="hint muted" style={{ marginBottom: 14 }}>
        Vyoog stops at volume — it tells you what's affected, never how long that would take.
      </p>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <input
          className="input" style={{ flex: 1 }} placeholder="Requirement ID"
          value={requirementId} onChange={(e) => setRequirementId(e.target.value)}
        />
        <button className="btn pri" disabled={!requirementId.trim()} onClick={() => setActiveId(requirementId.trim())}>
          Show impact
        </button>
      </div>
      {isError && <p className="err-text">Could not find that requirement.</p>}
      {data && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 12 }}>
          {([
            ['Requirements', data.requirements], ['Tests', data.tests], ['Applications', data.applications],
            ['Capabilities', data.capabilities], ['Owners', data.owners], ['Teams', data.teams], ['Briefs', data.briefs],
          ] as const).map(([label, value]) => (
            <div key={label} className="card">
              <div className="eyebrow">{label}</div>
              <div style={{ fontSize: 22, fontWeight: 700 }}>{value}</div>
              {/* VYB-0507: real signals are computed from Vyoog's own tables; this one figure is
                  structurally different — it's CI-ingested test evidence, sourced from an external
                  system, not authored here. Marked, not hidden. Not --ai — that token is reserved
                  for AI output specifically (see tokens.css), and this isn't that. */}
              {label === 'Tests' && data.testsBorrowed > 0 && (
                <div className="badge" style={{ marginTop: 6, color: 'var(--brand)', borderColor: 'var(--brand-bd)' }}>
                  {data.testsBorrowed === data.tests ? 'All borrowed (CI)' : `${data.testsBorrowed} borrowed (CI)`}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 12 }}>
        "Borrowed" tests are CI-ingested — reported by the CI integration, not authored here. A test drafted
        directly (Quality → Verification → "Draft test case") counts toward "Tests" but never "Borrowed".
      </p>
    </>
  )
}
