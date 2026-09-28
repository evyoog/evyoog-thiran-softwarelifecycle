import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { CalendarDays, GitBranch, Rocket } from 'lucide-react'
import {
  api, type AppUserView, type CompletedTask, type Task, type TaskKind,
} from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'

type Tab = 'today' | 'pipeline' | 'deployment' | 'calendar'

const KIND_LABEL: Record<TaskKind, string> = {
  AUTHOR_WORDING: 'Fix unclear wording',
  REVIEWER_PENDING: 'Review pending',
  APPROVER_AWAITING: 'Approval pending',
  DEVELOPER_IMPLEMENT: 'Implement',
  DEVELOPER_REIMPLEMENT: 'Re-implement',
  TESTER_VERIFY: 'Verify',
  TESTER_REVERIFY: 'Re-verify',
}

/** The lifecycle stage each derived task belongs to — what the chip on the row shows. */
type Stage = 'author' | 'review' | 'approve' | 'dev' | 'test' | 'deploy'

const KIND_STAGE: Record<TaskKind, Stage> = {
  AUTHOR_WORDING: 'author',
  REVIEWER_PENDING: 'review',
  APPROVER_AWAITING: 'approve',
  DEVELOPER_IMPLEMENT: 'dev',
  DEVELOPER_REIMPLEMENT: 'dev',
  TESTER_VERIFY: 'test',
  TESTER_REVERIFY: 'test',
}

const STAGE_LABEL: Record<Stage, string> = {
  author: 'Author', review: 'Review', approve: 'Approve',
  dev: 'Develop', test: 'Verify', deploy: 'Deploy',
}

// VYB-0810: VERIFIED is gone as a status — REVIEWED (VYB-0802's manual review gate)
// takes its lane instead, mapped to the 'approve' stage it actually is (awaiting the
// Approver's decision). APPROVED keeps meaning "now being built" (dev stage); it is the
// terminal status of the lifecycle, so there is no longer a lane after it.
// VYB-0813 (D17): NEEDS_REVISION gets its own lane, mapped to 'author' — the same
// stage as DRAFT, since a requirement here is back with its author to act on, not a
// blank slate but not anyone else's turn either.
const STAGES = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED'] as const
const STAGE_OF_STATUS: Record<(typeof STAGES)[number], Stage> = {
  DRAFT: 'author', IN_REVIEW: 'review', REVIEWED: 'approve', NEEDS_REVISION: 'author', APPROVED: 'dev',
}
const STATUS_LANE_LABEL: Record<(typeof STAGES)[number], string> = {
  DRAFT: 'Draft', IN_REVIEW: 'In review', REVIEWED: 'Reviewed', NEEDS_REVISION: 'Needs revision', APPROVED: 'Approved',
}

/**
 * VYB-0820: requested as frontend-only for now — four more pipeline lanes after
 * Approved, tracking delivery beyond the requirement's own status. Not derived from
 * anything real yet; see the placeholder note where these render in {@link PipelineTab}.
 */
const POST_APPROVAL_LANES: { key: string; label: string }[] = [
  { key: 'planning', label: 'Planning' },
  { key: 'development', label: 'Development' },
  { key: 'testing', label: 'Testing' },
  { key: 'deployed', label: 'Deployed' },
]

/**
 * VYB-0370–0374. Nobody creates a task in Vyoog — every row here is one of the seven
 * conditions {@code TaskService} derives, computed fresh on every load.
 *
 * <p>Two things the layout prototype does that this deliberately does not:
 *
 * <p><b>No completion checkbox.</b> The prototype puts a tick box on every row. There is
 * nothing to tick: a task exists exactly as long as its condition is true (Principle 4),
 * so a checkbox would either lie — hiding a row whose condition still holds — or need a
 * completion table, which is the very thing "tasks are derived" rules out. Each row links
 * to the requirement instead, which is where the state that ends the task actually
 * changes.
 *
 * <p><b>No amber.</b> The prototype paints "due today" and the Verify stage in its AI
 * colour. Amber means AI here and nothing else (Principle 5), so those read blue and
 * orange respectively.
 */
export function MyWork() {
  const [tab, setTab] = useState<Tab>('today')
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [viewing, setViewing] = useState<string>('')

  const whoId = viewing || me?.id || ''
  const isMe = !viewing || viewing === me?.id

  return (
    <Page
      title="My Work"
      desc="Nobody creates a task in Vyoog. An approved requirement with no code link is a developer task; a requirement changed after its last passing test is a tester task. The list writes itself from the state of the chain."
      actions={
        <button className="btn" onClick={() => setTab('calendar')}>
          <CalendarDays /> Calendar
        </button>
      }
    >
      <div style={{ display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' }}>
        {(['today', 'pipeline', 'deployment', 'calendar'] as Tab[]).map((t) => (
          <button key={t} className={`btn${t === tab ? ' pri' : ''}`} onClick={() => setTab(t)}>
            {t === 'today' ? 'Today' : t === 'pipeline' ? 'Pipeline'
              : t === 'deployment' ? 'Deployment' : 'Calendar'}
          </button>
        ))}
      </div>

      {/* VYB-0373: the person switcher, visible on every panel rather than hidden behind
          its own tab — whose work you are looking at is context for all of them, not a
          separate feature. Read-only for anyone but yourself, and server-side that needs
          the ADMINISTRATOR grant regardless of what this strip offers. */}
      <WhoStrip myId={me?.id} selected={whoId} onSelect={(id) => setViewing(id === me?.id ? '' : id)} />

      {tab === 'today' && <TodayTab userId={whoId} isMe={isMe} />}
      {tab === 'pipeline' && <PipelineTab />}
      {tab === 'deployment' && <DeploymentTab />}
      {tab === 'calendar' && <CalendarTab />}
    </Page>
  )
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

/**
 * Everyone with derived work, and how much of it is open.
 *
 * <p>The count for somebody else needs the ADMINISTRATOR grant. Where it is refused the
 * strip shows a dash, never a zero — Principle 8: "no access" and "nothing to do" are
 * different facts and must not render identically.
 */
function WhoStrip({ myId, selected, onSelect }: {
  myId?: string
  selected: string
  onSelect: (id: string) => void
}) {
  const { data: users } = useQuery({ queryKey: ['users'], queryFn: api.users })
  const active = (users ?? []).filter((u) => u.status === 'ACTIVE').slice(0, 8)

  const counts = useQueries({
    queries: active.map((u) => ({
      queryKey: ['tasks-for', u.id],
      queryFn: () => api.tasksFor(u.id),
      retry: false,
      staleTime: 60_000,
    })),
  })

  if (active.length === 0) return null

  return (
    <div className="who-strip">
      {active.map((u: AppUserView, i) => {
        const q = counts[i]
        const open = q?.data?.filter((t) => !t.blocked).length
        return (
          <button
            key={u.id}
            className={`who-b${u.id === selected ? ' on' : ''}`}
            onClick={() => onSelect(u.id)}
            aria-pressed={u.id === selected}
          >
            <span className="avatar" aria-hidden="true">{initials(u.displayName)}</span>
            <span>
              <span className="who-n">{u.displayName}{u.id === myId ? ' (you)' : ''}</span>
              <span className="who-r">{u.email}</span>
            </span>
            <span className="who-c" title={q?.isError ? 'You cannot see this person’s work' : 'Open tasks'}>
              {q?.isError ? '—' : open ?? '·'}
            </span>
          </button>
        )
      })}
    </div>
  )
}

function ageInDays(since?: string): number {
  if (!since) return 0
  return (Date.now() - new Date(since).getTime()) / 86_400_000
}

/** VYB-0370: overdue / today / this week / blocked. AC1 — every row states why. */
function TodayTab({ userId, isMe }: { userId?: string; isMe: boolean }) {
  const qc = useQueryClient()
  const tasksKey = isMe ? ['my-tasks', userId] : ['tasks-for', userId]
  const { data, isLoading, isError } = useQuery({
    queryKey: tasksKey,
    queryFn: () => (isMe ? api.myTasks() : api.tasksFor(userId!)),
    enabled: !!userId,
  })
  // VYB-0838 (D19): only the viewer's own completions — the same "read-only for
  // anyone but yourself" line the who-strip already draws (checking a box on
  // somebody else's work isn't viewing it, it's acting on it).
  const doneKey = ['completed-today', userId]
  const { data: done } = useQuery({
    queryKey: doneKey, queryFn: api.completedToday, enabled: !!userId && isMe,
  })

  const invalidate = () => { qc.invalidateQueries({ queryKey: tasksKey }); qc.invalidateQueries({ queryKey: doneKey }) }
  const complete = useMutation({
    mutationFn: (t: Task) => api.completeTask(t.kind, t.objectId), onSuccess: invalidate,
  })
  const reopen = useMutation({
    mutationFn: (t: CompletedTask) => api.reopenTask(t.kind, t.objectId), onSuccess: invalidate,
  })

  // Bucketed by how long a task has been waiting. It used to bucket by the due date
  // Planning resolved; with that feature removed there is no date on a task at all, so
  // this is the fallback the undated case always used rather than a new rule.
  const b = useMemo(() => {
    const blocked = (data ?? []).filter((t) => t.blocked)
    const rest = (data ?? []).filter((t) => !t.blocked)
    const isOverdue = (t: Task) => ageInDays(t.since) > 7
    const isToday = (t: Task) => ageInDays(t.since) <= 1
    const isWeek = (t: Task) => { const a = ageInDays(t.since); return a > 1 && a <= 7 }
    const overdue = rest.filter(isOverdue)
    return { blocked, overdue, today: rest.filter(isToday), week: rest.filter(isWeek) }
  }, [data])

  if (isError) return <Empty title="Cannot see this person’s work" desc="Viewing somebody else's derived work needs the ADMINISTRATOR grant." />
  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (data && data.length === 0 && (done ?? []).length === 0) {
    return <Empty title="Nothing derived right now" desc="Every condition that would create a task is currently false. Tasks appear here automatically when a requirement reaches a stage this person owns." />
  }

  // How long the longest-waiting task has been sitting, in whole days.
  const oldest = b.overdue.reduce((worst, t) => Math.max(worst, Math.round(ageInDays(t.since))), 0)

  return (
    <>
      <div className="q-grid">
        <Stat cls="act" value={b.overdue.length} label="Overdue"
          sub={b.overdue.length ? (oldest ? `oldest ${oldest} days late` : 'by condition age') : 'nothing late'} />
        <Stat cls="mine" value={b.today.length} label="Due today" />
        <Stat cls="mine" value={b.week.length} label="Due this week" />
        <Stat cls="zero" value={b.blocked.length} label="Blocked"
          sub={b.blocked.length ? 'waiting on someone else' : ''} />
        <Stat cls="zero" value={(done ?? []).length} label="Closed today" />
      </div>

      <TaskGroup title="Overdue" colour="var(--crit)" rows={b.overdue} cls="over"
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      {/*
        VYB-0838 (D19): amber here is a deliberate, confirmed override of the
        "amber means AI, nothing else" rule (tokens.css's own header comment, and
        CLAUDE.md's Principle 5) — matching the vyoog-layout prototype's
        --ai-coloured "Due today" group exactly as asked, not a reversion by
        accident. See docs/DECISIONS.md D19 before reusing --ai anywhere else.
      */}
      <TaskGroup title="Due today" colour="var(--ai)" rows={b.today} cls="today"
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      <TaskGroup title="This week" colour="var(--tx-2)" rows={b.week} cls=""
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      <TaskGroup title="Blocked — waiting on someone else" colour="var(--tx-3)" rows={b.blocked} cls="blocked" />

      {isMe && (done ?? []).length > 0 && (
        <>
          <div className="tg-h">
            <span className="tg-t" style={{ color: 'var(--ok)' }}>Closed today</span>
            <span className="tg-n">{done!.length}</span>
            <span className="tg-line" />
          </div>
          {done!.map((c, i) => (
            <DoneRow key={`${c.kind}-${c.objectId}-${i}`} c={c} onReopen={() => reopen.mutate(c)} />
          ))}
        </>
      )}

      <p className="hint muted" style={{ marginTop: 14 }}>
        {isMe
          ? 'Checking a task records that you dismissed it — it reopens on its own if the requirement changes again, so nothing here can go stale silently.'
          : "Nothing here can be ticked off while viewing someone else's work."}
      </p>
    </>
  )
}

function Stat({ cls, value, label, sub }: { cls: string; value: number; label: string; sub?: string }) {
  return (
    <div className={`q-c ${value === 0 ? 'zero' : cls}`}>
      <span className="q-v">{value}</span>
      <span className="q-l">{label}</span>
      {sub ? <span className="q-s">{sub}</span> : null}
    </div>
  )
}

function TaskGroup({ title, colour, rows, cls, onComplete }: {
  title: string; colour: string; rows: Task[]; cls: string; onComplete?: (t: Task) => void
}) {
  if (rows.length === 0) return null
  return (
    <>
      <div className="tg-h">
        <span className="tg-t" style={{ color: colour }}>{title}</span>
        <span className="tg-n">{rows.length}</span>
        <span className="tg-line" />
      </div>
      {rows.map((t, i) => (
        <TaskRow key={`${t.kind}-${t.objectId}-${i}`} t={t} cls={cls} onComplete={onComplete} />
      ))}
    </>
  )
}

function TaskRow({ t, cls, onComplete }: { t: Task; cls: string; onComplete?: (t: Task) => void }) {
  // Age, not a due date: a task has no date of its own now that Planning is gone, and
  // showing "today" for something nobody committed to a day would be inventing one.
  const age = Math.round(ageInDays(t.since))
  const dueText = `${age}d open`
  const dueCls = age > 7 ? 'over' : ''
  const stage = KIND_STAGE[t.kind]

  return (
    <div className={`tk-row ${cls}`}>
      {/* VYB-0838 (D19): a real completion action, not the prototype's client-side-only
          toggle — clicking this calls api.completeTask, which records a dismissal at
          the requirement's current revision. Absent (not disabled) for a blocked task:
          there's nothing to dismiss until whoever owes the answer actually answers. */}
      {onComplete && !t.blocked ? (
        <button
          type="button" className="tk-cb" title="Mark complete"
          onClick={() => onComplete(t)}
        />
      ) : <span />}
      <Link to={`/requirements/${t.objectId}`} className="tk-rid mono">{t.objectLabel}</Link>
      <span>
        <span className="tk-act">{KIND_LABEL[t.kind]}</span>
        <span className="tk-sub">{t.reason}</span>
      </span>
      <span className={`tk-stage st-${stage}`}>{STAGE_LABEL[stage]}</span>
      <span>
        {t.blocked && (
          <span className="badge st-draft" title={t.blockedByQuestion}>blocked</span>
        )}
      </span>
      <span className={`tk-due ${dueCls}`}>{dueText}</span>
    </div>
  )
}

/** VYB-0838 (D19): a dismissed task, shown struck through with an undo — reopen deletes the completion outright rather than requiring the underlying condition to change. */
function DoneRow({ c, onReopen }: { c: CompletedTask; onReopen: () => void }) {
  return (
    <div className="tk-row done">
      <button type="button" className="tk-cb done" title="Reopen" onClick={onReopen}>✓</button>
      <Link to={`/requirements/${c.objectId}`} className="tk-rid mono">{c.objectLabel}</Link>
      <span>
        <span className="tk-act">{KIND_LABEL[c.kind]}</span>
        <span className="tk-sub">Marked complete</span>
      </span>
      <span className={`tk-stage st-${KIND_STAGE[c.kind]}`}>{STAGE_LABEL[KIND_STAGE[c.kind]]}</span>
      <span />
      <span className="tk-due">done</span>
    </div>
  )
}

// VYB-0821: rendering thousands of cards in one lane is its own kind of unfriendly —
// this caps what actually mounts, while the lane header still shows the real total
// (from the page's own totalElements, not the length of what got fetched) and an
// overflow link, so a lane with 4000 requirements never silently claims to be complete.
const LANE_PAGE_SIZE = 50

/** VYB-0371: lanes by lifecycle stage, not a sprint board. AC2 — stalled cards are marked. */
function PipelineTab() {
  // How many LANE_PAGE_SIZE pages are shown per lane — starts at 1 (the first 50), and
  // grows independently per lane when its own "Show more" is clicked. Each page is its
  // own query (queryKey includes the page number) rather than one query re-fetching a
  // growing size, so a page already seen stays cached instead of being re-requested
  // every time an adjacent lane's count changes.
  const [pagesShown, setPagesShown] = useState<Record<string, number>>({})
  const pageSpecs = useMemo(
    () => STAGES.flatMap((status) =>
      Array.from({ length: pagesShown[status] ?? 1 }, (_, page) => ({ status, page }))),
    [pagesShown],
  )
  // Still one request per page, not one capped fetch of everything sliced
  // client-side — the old `size: 200` fetch meant a deployment with thousands of
  // requirements would silently show wrong counts (only whatever fell in the first
  // 200 rows, by whatever order the server happened to return them) and could leave a
  // real lane looking empty just because none of its rows made that first page.
  const pageQueries = useQueries({
    queries: pageSpecs.map(({ status, page }) => ({
      queryKey: ['requirements-pipeline', status, page],
      queryFn: () => api.requirements({ status, size: LANE_PAGE_SIZE, page, sort: ['key,asc'] }),
    })),
  })
  const { data: stalled } = useQuery({ queryKey: ['stalled'], queryFn: api.stalledRequirements })
  const stalledById = new Map((stalled ?? []).map((s) => [s.id, s]))

  if (pageQueries.some((q) => q.isLoading)) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div className="sp-note">
        <GitBranch style={{ width: 16, height: 16, flex: '0 0 auto', color: 'var(--tx-3)' }} />
        <p>
          <b>The requirement's own lifecycle, not a sprint board.</b> Each column is a stage the requirement
          passes through and the role that owns it. A card sitting in one column past its configured threshold
          is marked stalled — that is the number worth watching, because a requirement can sit in Development
          for six weeks while its tickets look busy.
        </p>
      </div>
      <div className="lanes">
        {STAGES.map((status) => {
          const indices = pageSpecs
            .map((spec, i) => (spec.status === status ? i : -1))
            .filter((i) => i >= 0)
          const pages = indices.map((i) => pageQueries[i]?.data).filter((p) => !!p)
          const cards = pages.flatMap((p) => p!.content)
          // totalElements, not cards.length: the real count for this status, whatever
          // the actual number of requirements in it — the header must never understate
          // a lane just because only the pages shown so far were fetched.
          const total = pages[0]?.totalElements ?? cards.length
          const totalPages = pages[0]?.totalPages ?? 1
          const shownPages = pagesShown[status] ?? 1
          const remaining = total - cards.length
          const canShowMore = shownPages < totalPages && remaining > 0
          return (
            <div className="lane" key={status}>
              <div className="lane-h">
                <span className={`tk-stage st-${STAGE_OF_STATUS[status]}`} style={{ justifySelf: 'auto' }}>
                  {STAGE_LABEL[STAGE_OF_STATUS[status]]}
                </span>
                <span className="lane-t">{STATUS_LANE_LABEL[status]}</span>
                <span className="lane-n">{total}</span>
              </div>
              <div className="lane-b">
                {cards.length === 0 && <span className="hint muted" style={{ fontSize: 10.5 }}>Nothing here.</span>}
                {cards.map((r) => {
                  const stall = stalledById.get(r.id)
                  return (
                    <Link to={`/requirements/${r.id}`} className={`lc${stall ? ' stall' : ''}`} key={r.id}>
                      <span className="lc-id">{r.key}</span>
                      <div className="lc-t">{r.title}</div>
                      <div className="lc-f">
                        <span className="lc-age">{r.priority.toLowerCase()}</span>
                        {stall && (
                          <span className="lc-age old" title={`Threshold is ${stall.thresholdDays} days`}>
                            {Math.round(stall.daysInStage)}d in stage
                          </span>
                        )}
                      </div>
                    </Link>
                  )
                })}
                {canShowMore && (
                  <button
                    type="button"
                    className="hint"
                    style={{ padding: '4px 2px', background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left' }}
                    onClick={() => setPagesShown((p) => ({ ...p, [status]: shownPages + 1 }))}
                  >
                    Show {Math.min(LANE_PAGE_SIZE, remaining)} more ({remaining} left)
                  </button>
                )}
              </div>
            </div>
          )
        })}
        {/*
          VYB-0820: four more lanes after Approved — Planning, Development, Testing,
          Deployed — requested as a frontend-only placeholder, backend to follow. None
          of these read from a real field yet (there is no per-requirement data saying
          which of these an APPROVED requirement is actually in), so each says that
          plainly rather than showing an empty lane that would read as "computed, and
          genuinely zero" the way the real lanes above do (Principle 8: absence should
          read as "not tracked," never as a lie dressed up as a real empty state).
        */}
        {POST_APPROVAL_LANES.map((lane) => (
          <div className="lane" key={lane.key} style={{ opacity: 0.7 }}>
            <div className="lane-h">
              <span className="lane-t">{lane.label}</span>
              <span className="lane-n">—</span>
            </div>
            <div className="lane-b">
              <span className="hint muted" style={{ fontSize: 10.5 }}>
                Not tracked yet — this lane isn't wired to real data.
              </span>
            </div>
          </div>
        ))}
      </div>
    </>
  )
}

/**
 * VYB-0374: which requirements are actually in which environment.
 *
 * <p>Build numbers and deployment events arrive from CI/CD — rendered hatched, because
 * Vyoog displays them and does not own them (Principle 8). What Vyoog owns is the link:
 * this requirement, verified at this revision, is present in this build.
 */
function DeploymentTab() {
  const { data: envs, isLoading } = useQuery({
    queryKey: ['environment-summaries'], queryFn: api.environmentSummaries,
  })
  const { data: recent } = useQuery({ queryKey: ['recent-deployments'], queryFn: () => api.recentDeployments(8) })
  const { data: blocked } = useQuery({ queryKey: ['blocked-from-release'], queryFn: () => api.blockedFromRelease(20) })

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div className="sp-note">
        <Rocket style={{ width: 16, height: 16, flex: '0 0 auto', color: 'var(--tx-3)' }} />
        <p>
          <b>Which requirements are actually in which environment.</b> Build numbers and deployment events
          arrive from CI/CD — hatched, because Vyoog displays them and does not own them. What Vyoog owns is
          the link: this requirement, verified at this revision, is present in this build. That is the last
          segment of the traceability chain.
        </p>
      </div>

      {envs && envs.length === 0 && (
        <Empty title="No environments registered"
          desc="Environments and deployments arrive from CI/CD. Until something posts a build, there is nothing to show here." />
      )}

      <div className="env-strip">
        {(envs ?? []).map((e, i) => (
          <div className={`env${i === (envs?.length ?? 0) - 1 && e.buildLabel ? ' live' : ''}`} key={e.id}>
            <div className="env-l">{e.name}</div>
            {/* Principle 8: never deployed renders as "not connected", not as 0. */}
            <div className="env-v">{e.buildLabel ? e.requirementCount.toLocaleString() : '—'}</div>
            <div className="env-b">{e.buildLabel ? `build ${e.buildLabel}` : 'not connected'}</div>
          </div>
        ))}
      </div>

      <div className="row" style={{ alignItems: 'stretch', gap: 12 }}>
        <div className="card" style={{ flex: '1.3 1 0', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
            <h4 className="section-h" style={{ margin: 0 }}>Recent deployments</h4>
            <span className="sp" />
            <span className="hint muted">from CI/CD</span>
          </div>
          {(recent ?? []).length === 0 && <p className="hint muted">Nothing deployed yet.</p>}
          {(recent ?? []).map((d) => (
            <div key={d.id} style={{ display: 'grid', gridTemplateColumns: '84px 92px 1fr auto', gap: 10, alignItems: 'center', padding: '7px 0', borderBottom: '1px solid var(--line)' }}>
              <span className="tk-rid mono">{d.buildLabel}</span>
              <span className={`badge ${d.environmentName.toLowerCase().startsWith('prod') ? 'st-approved' : 'st-draft'}`}>
                {d.environmentName}
              </span>
              <span style={{ fontSize: 11.5, color: d.succeeded ? 'var(--tx-2)' : 'var(--crit)' }}>
                {d.succeeded
                  ? `${d.requirementCount} requirement${d.requirementCount === 1 ? '' : 's'} in this build`
                  : 'Build failed — not promoted'}
              </span>
              <span className="hint muted" style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                {new Date(d.deployedAt).toLocaleString()}
              </span>
            </div>
          ))}
        </div>

        <div className="card" style={{ flex: '1 1 0', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
            <h4 className="section-h" style={{ margin: 0 }}>Blocked from release</h4>
            <span className="sp" />
            <span className="badge pr-critical">{blocked?.length ?? 0}</span>
          </div>
          {(blocked ?? []).length === 0 && (
            <p className="hint muted">Nothing approved is missing a passing test at its current revision.</p>
          )}
          {(blocked ?? []).map((r) => (
            <div key={r.requirementId} style={{ padding: '6px 0', borderBottom: '1px solid var(--line)' }}>
              <Link to={`/requirements/${r.requirementId}`} className="tk-rid mono">{r.key}</Link>
              <div style={{ fontSize: 11.5, color: 'var(--tx-2)', marginTop: 2 }}>{r.reason}</div>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}

type CalKind = 'task' | 'rev' | 'rel' | 'dep' | 'risk'
type CalEvent = { date: Date; label: string; kind: CalKind }

const LEGEND: { kind: CalKind; colour: string; label: string }[] = [
  { kind: 'task', colour: 'var(--tx-3)', label: 'My task due' },
  { kind: 'rev', colour: 'var(--info)', label: 'Review closes' },
  { kind: 'rel', colour: 'var(--brand)', label: 'Release milestone' },
  { kind: 'dep', colour: 'var(--ok)', label: 'Deployment' },
  { kind: 'risk', colour: 'var(--crit)', label: 'Overdue' },
]

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/**
 * VYB-0372: plots only dates this domain genuinely has — a release's target date, a
 * review's close date, a clarification's derived due date, and a deployment's timestamp.
 * No date is invented: a release with no target simply does not appear, and a task has no
 * date of its own now that Planning is gone.
 */
function CalendarTab() {
  const [month, setMonth] = useState(() => { const d = new Date(); d.setDate(1); d.setHours(0, 0, 0, 0); return d })

  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const { data: reviews } = useQuery({ queryKey: ['reviews'], queryFn: api.reviews })
  const { data: clarifications } = useQuery({ queryKey: ['clarifications-blocking'], queryFn: api.blockingClarifications })
  const { data: deployments } = useQuery({ queryKey: ['recent-deployments'], queryFn: () => api.recentDeployments(8) })

  const events = useMemo<CalEvent[]>(() => {
    const out: CalEvent[] = []
    for (const r of releases ?? []) {
      if (r.targetDate) out.push({ date: new Date(r.targetDate), label: `${r.name} target`, kind: 'rel' })
    }
    for (const rv of reviews ?? []) {
      if (rv.closesAt) out.push({ date: new Date(rv.closesAt), label: `Review closes`, kind: 'rev' })
    }
    for (const c of clarifications ?? []) {
      if (c.dueAt) out.push({ date: new Date(c.dueAt), label: `Clarification due`, kind: 'rev' })
    }
    for (const d of deployments ?? []) {
      out.push({ date: new Date(d.deployedAt), label: `${d.buildLabel} → ${d.environmentName}`, kind: 'dep' })
    }
    return out
  }, [releases, reviews, clarifications, deployments])

  // Monday-first, the way the prototype's grid reads.
  const lead = (month.getDay() + 6) % 7
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  const cells: { date: Date; inMonth: boolean }[] = []
  for (let i = lead; i > 0; i--) {
    cells.push({ date: new Date(month.getFullYear(), month.getMonth(), 1 - i), inMonth: false })
  }
  for (let d = 1; d <= daysInMonth; d++) {
    cells.push({ date: new Date(month.getFullYear(), month.getMonth(), d), inMonth: true })
  }
  while (cells.length % 7 !== 0) {
    const last = cells[cells.length - 1].date
    cells.push({ date: new Date(last.getFullYear(), last.getMonth(), last.getDate() + 1), inMonth: false })
  }

  const today = new Date()
  const shift = (by: number) => setMonth(new Date(month.getFullYear(), month.getMonth() + by, 1))

  return (
    <>
      <div className="row" style={{ marginBottom: 12, alignItems: 'center' }}>
        <button className="btn" onClick={() => shift(-1)} aria-label="Previous month">◂</button>
        <span style={{ fontFamily: 'var(--f-serif)', fontSize: 19, fontWeight: 700 }}>
          {month.toLocaleString(undefined, { month: 'long', year: 'numeric' })}
        </span>
        <button className="btn" onClick={() => shift(1)} aria-label="Next month">▸</button>
        <span className="sp" />
        <button className="btn" onClick={() => { const d = new Date(); d.setDate(1); setMonth(d) }}>Today</button>
      </div>

      <div className="cal">
        <div className="cal-hd">
          {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => <span key={d}>{d}</span>)}
        </div>
        <div className="cal-g">
          {cells.map(({ date, inMonth }, i) => {
            const weekend = i % 7 >= 5
            const dayEvents = events.filter((e) => sameDay(e.date, date))
            const shown = dayEvents.slice(0, 3)
            return (
              <div
                key={date.toISOString()}
                className={`cal-d${inMonth ? '' : ' off'}${weekend ? ' we' : ''}${sameDay(date, today) ? ' now' : ''}`}
              >
                <span className="cal-n">
                  {date.getDate()}{sameDay(date, today) ? ' · today' : ''}
                </span>
                {inMonth && shown.map((e, n) => (
                  <span key={n} className={`cal-ev ev-${e.kind}`} title={e.label}>{e.label}</span>
                ))}
                {inMonth && dayEvents.length > 3 && (
                  <span className="cal-more">+{dayEvents.length - 3} more</span>
                )}
              </div>
            )
          })}
        </div>
      </div>

      <div className="cal-legend">
        {LEGEND.map((l) => (
          <span key={l.kind}><i style={{ background: l.colour }} />{l.label}</span>
        ))}
      </div>
      <p className="hint muted" style={{ marginTop: 10 }}>
        Only dates this product actually holds are plotted — a release target, a review close, a clarification
        deadline, a deployment, and the due date Planning resolved for each task. Nothing here is invented.
      </p>
    </>
  )
}
