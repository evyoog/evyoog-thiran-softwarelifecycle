import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { CalendarDays, GitBranch, Rocket } from 'lucide-react'
import {
  api, type AppUserView, type CompletedTask, type Task, type TaskKind,
} from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { useLocale } from '@/shared/i18n/LocaleProvider'
import type { MessageKey } from '@/shared/i18n/catalog.en'

type Tab = 'today' | 'pipeline' | 'deployment' | 'calendar'

/** VYB-0958: the words of this screen live in the message catalogue (`mywork.*`); these maps hold only the keys. */
const KIND_LABEL: Record<TaskKind, MessageKey> = {
  AUTHOR_WORDING: 'mywork.kind.AUTHOR_WORDING',
  DEVELOPER_IMPLEMENT: 'mywork.kind.DEVELOPER_IMPLEMENT',
  DEVELOPER_REIMPLEMENT: 'mywork.kind.DEVELOPER_REIMPLEMENT',
  TESTER_VERIFY: 'mywork.kind.TESTER_VERIFY',
  TESTER_REVERIFY: 'mywork.kind.TESTER_REVERIFY',
}

/** The lifecycle stage each derived task belongs to — what the chip on the row shows. */
type Stage = 'author' | 'review' | 'approve' | 'dev' | 'test' | 'deploy'

const KIND_STAGE: Record<TaskKind, Stage> = {
  AUTHOR_WORDING: 'author',
  DEVELOPER_IMPLEMENT: 'dev',
  DEVELOPER_REIMPLEMENT: 'dev',
  TESTER_VERIFY: 'test',
  TESTER_REVERIFY: 'test',
}

const STAGE_LABEL: Record<Stage, MessageKey> = {
  author: 'mywork.stage.author', review: 'mywork.stage.review', approve: 'mywork.stage.approve',
  dev: 'mywork.stage.dev', test: 'mywork.stage.test', deploy: 'mywork.stage.deploy',
}

// VYB-0810: VERIFIED is gone as a status — REVIEWED (VYB-0802's manual review gate)
// takes its lane instead, mapped to the 'approve' stage it actually is (awaiting the
// Approver's decision). APPROVED keeps meaning "now being built" (dev stage); it is the
// terminal status of the lifecycle, so there is no longer a lane after it.
// VYB-0813 (D17): NEEDS_REVISION gets its own lane, mapped to 'author' — the same
// stage as DRAFT, since a requirement here is back with its author to act on, not a
// blank slate but not anyone else's turn either.
export const STAGES = ['DRAFT', 'IN_REVIEW', 'REVIEWED', 'NEEDS_REVISION', 'APPROVED'] as const
const STAGE_OF_STATUS: Record<(typeof STAGES)[number], Stage> = {
  DRAFT: 'author', IN_REVIEW: 'review', REVIEWED: 'approve', NEEDS_REVISION: 'author', APPROVED: 'dev',
}

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
  const { t } = useLocale()
  const [tab, setTab] = useState<Tab>('today')
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: api.me })
  const [viewing, setViewing] = useState<string>('')

  const whoId = viewing || me?.id || ''
  const isMe = !viewing || viewing === me?.id

  return (
    <Page
      title={t('mywork.title')}
      desc={t('mywork.desc')}
      actions={
        <button className="btn" onClick={() => setTab('calendar')}>
          <CalendarDays aria-hidden="true" /> {t('mywork.tab.calendar')}
        </button>
      }
    >
      <div style={{ display: 'flex', gap: 6, marginBottom: 14, flexWrap: 'wrap' }}>
        {(['today', 'pipeline', 'deployment', 'calendar'] as Tab[]).map((id) => (
          <button key={id} className={`btn${id === tab ? ' pri' : ''}`} aria-pressed={id === tab} onClick={() => setTab(id)}>
            {t(`mywork.tab.${id}`)}
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
  const { t, fmt } = useLocale()
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
    <div className="who-strip" role="group" aria-label={t('mywork.who')}>
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
              <span className="who-n">{u.id === myId ? t('mywork.whoYou', { name: u.displayName }) : u.displayName}</span>
              <span className="who-r">{u.email}</span>
            </span>
            <span className="who-c" title={q?.isError ? t('mywork.cannotSeeCount') : t('mywork.openTasks')}>
              {q?.isError ? '—' : open === undefined ? '·' : fmt.number(open)}
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
  const { t } = useLocale()
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

  if (isError) return <Empty title={t('mywork.cannotSee.title')} desc={t('mywork.cannotSee.desc')} />
  if (isLoading) return <p className="eyebrow">{t('common.loading')}</p>
  if (data && data.length === 0 && (done ?? []).length === 0) {
    return <Empty title={t('mywork.nothing.title')} desc={t('mywork.nothing.desc')} />
  }

  // How long the longest-waiting task has been sitting, in whole days.
  const oldest = b.overdue.reduce((worst, t) => Math.max(worst, Math.round(ageInDays(t.since))), 0)

  return (
    <>
      <div className="q-grid">
        <Stat cls="act" value={b.overdue.length} label={t('mywork.stat.overdue')}
          sub={b.overdue.length ? (oldest ? t('mywork.stat.oldest', { count: oldest }) : t('mywork.stat.byAge')) : t('mywork.stat.nothingLate')} />
        <Stat cls="mine" value={b.today.length} label={t('mywork.stat.today')} />
        <Stat cls="mine" value={b.week.length} label={t('mywork.stat.week')} />
        <Stat cls="zero" value={b.blocked.length} label={t('mywork.stat.blocked')}
          sub={b.blocked.length ? t('mywork.stat.waiting') : ''} />
        <Stat cls="zero" value={(done ?? []).length} label={t('mywork.stat.closed')} />
      </div>

      <TaskGroup title={t('mywork.stat.overdue')} colour="var(--crit)" rows={b.overdue} cls="over"
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      {/*
        VYB-0838 (D19): amber here is a deliberate, confirmed override of the
        "amber means AI, nothing else" rule (tokens.css's own header comment, and
        CLAUDE.md's Principle 5) — matching the vyoog-layout prototype's
        --ai-coloured "Due today" group exactly as asked, not a reversion by
        accident. See docs/DECISIONS.md D19 before reusing --ai anywhere else.
      */}
      <TaskGroup title={t('mywork.stat.today')} colour="var(--ai)" rows={b.today} cls="today"
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      <TaskGroup title={t('mywork.group.week')} colour="var(--tx-2)" rows={b.week} cls=""
        onComplete={isMe ? (t) => complete.mutate(t) : undefined} />
      <TaskGroup title={t('mywork.group.blocked')} colour="var(--tx-3)" rows={b.blocked} cls="blocked" />

      {isMe && (done ?? []).length > 0 && (
        <>
          <div className="tg-h">
            <span className="tg-t" style={{ color: 'var(--ok)' }}>{t('mywork.stat.closed')}</span>
            <span className="tg-n">{done!.length}</span>
            <span className="tg-line" />
          </div>
          {done!.map((c, i) => (
            <DoneRow key={`${c.kind}-${c.objectId}-${i}`} c={c} onReopen={() => reopen.mutate(c)} />
          ))}
        </>
      )}

      <p className="hint muted" style={{ marginTop: 14 }}>
        {isMe ? t('mywork.hint.mine') : t('mywork.hint.others')}
      </p>
    </>
  )
}

function Stat({ cls, value, label, sub }: { cls: string; value: number; label: string; sub?: string }) {
  const { fmt } = useLocale()
  return (
    <div className={`q-c ${value === 0 ? 'zero' : cls}`}>
      <span className="q-v">{fmt.number(value)}</span>
      <span className="q-l">{label}</span>
      {sub ? <span className="q-s">{sub}</span> : null}
    </div>
  )
}

function TaskGroup({ title, colour, rows, cls, onComplete }: {
  title: string; colour: string; rows: Task[]; cls: string; onComplete?: (t: Task) => void
}) {
  const { fmt } = useLocale()
  if (rows.length === 0) return null
  return (
    <>
      <div className="tg-h">
        <span className="tg-t" style={{ color: colour }}>{title}</span>
        <span className="tg-n">{fmt.number(rows.length)}</span>
        <span className="tg-line" />
      </div>
      {rows.map((t, i) => (
        <TaskRow key={`${t.kind}-${t.objectId}-${i}`} t={t} cls={cls} onComplete={onComplete} />
      ))}
    </>
  )
}

function TaskRow({ t: task, cls, onComplete }: { t: Task; cls: string; onComplete?: (t: Task) => void }) {
  const { t } = useLocale()
  // Age, not a due date: a task has no date of its own now that Planning is gone, and
  // showing "today" for something nobody committed to a day would be inventing one.
  const age = Math.round(ageInDays(task.since))
  const dueText = t('mywork.task.age', { count: age })
  const dueCls = age > 7 ? 'over' : ''
  const stage = KIND_STAGE[task.kind]

  return (
    <div className={`tk-row ${cls}`}>
      {/* VYB-0838 (D19): a real completion action, not the prototype's client-side-only
          toggle — clicking this calls api.completeTask, which records a dismissal at
          the requirement's current revision. Absent (not disabled) for a blocked task:
          there's nothing to dismiss until whoever owes the answer actually answers. */}
      {onComplete && !task.blocked ? (
        <button
          type="button" className="tk-cb" title={t('mywork.task.complete')} aria-label={`${t('mywork.task.complete')}: ${task.objectLabel}`}
          onClick={() => onComplete(task)}
        />
      ) : <span />}
      <Link to={`/requirements/${task.objectId}`} className="tk-rid mono">{task.objectLabel}</Link>
      <span>
        <span className="tk-act">{t(KIND_LABEL[task.kind])}</span>
        <span className="tk-sub">{task.reason}</span>
      </span>
      <span className={`tk-stage st-${stage}`}>{t(STAGE_LABEL[stage])}</span>
      <span>
        {task.blocked && (
          <span className="badge st-draft" title={task.blockedByQuestion}>{t('mywork.task.blocked')}</span>
        )}
      </span>
      <span className={`tk-due ${dueCls}`}>{dueText}</span>
    </div>
  )
}

/** VYB-0838 (D19): a dismissed task, shown struck through with an undo — reopen deletes the completion outright rather than requiring the underlying condition to change. */
function DoneRow({ c, onReopen }: { c: CompletedTask; onReopen: () => void }) {
  const { t } = useLocale()
  return (
    <div className="tk-row done">
      <button type="button" className="tk-cb done" title={t('mywork.task.reopen')} aria-label={`${t('mywork.task.reopen')}: ${c.objectLabel}`} onClick={onReopen}>✓</button>
      <Link to={`/requirements/${c.objectId}`} className="tk-rid mono">{c.objectLabel}</Link>
      <span>
        <span className="tk-act">{t(KIND_LABEL[c.kind])}</span>
        <span className="tk-sub">{t('mywork.task.markedComplete')}</span>
      </span>
      <span className={`tk-stage st-${KIND_STAGE[c.kind]}`}>{t(STAGE_LABEL[KIND_STAGE[c.kind]])}</span>
      <span />
      <span className="tk-due">{t('mywork.task.done')}</span>
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
  const { t, fmt } = useLocale()
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

  if (pageQueries.some((q) => q.isLoading)) return <p className="eyebrow">{t('common.loading')}</p>

  return (
    <>
      <div className="sp-note">
        <GitBranch aria-hidden="true" style={{ width: 16, height: 16, flex: '0 0 auto', color: 'var(--tx-3)' }} />
        <p>
          <b>{t('mywork.pipeline.noteLead')}</b> {t('mywork.pipeline.noteBody')}
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
                  {t(STAGE_LABEL[STAGE_OF_STATUS[status]])}
                </span>
                <span className="lane-t">{t(`status.${status}`)}</span>
                <span className="lane-n">{fmt.number(total)}</span>
              </div>
              <div className="lane-b">
                {cards.length === 0 && <span className="hint muted" style={{ fontSize: 10.5 }}>{t('mywork.pipeline.empty')}</span>}
                {cards.map((r) => {
                  const stall = stalledById.get(r.id)
                  return (
                    <Link to={`/requirements/${r.id}`} className={`lc${stall ? ' stall' : ''}`} key={r.id}>
                      <span className="lc-id">{r.key}</span>
                      <div className="lc-t">{r.title}</div>
                      <div className="lc-f">
                        <span className="lc-age">{t(`priority.${r.priority}`)}</span>
                        {stall && (
                          <span className="lc-age old" title={t('mywork.pipeline.threshold', { count: stall.thresholdDays })}>
                            {t('mywork.pipeline.inStage', { count: Math.round(stall.daysInStage) })}
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
                    {t('mywork.pipeline.showMore', { shown: fmt.number(Math.min(LANE_PAGE_SIZE, remaining)), left: fmt.number(remaining) })}
                  </button>
                )}
              </div>
            </div>
          )
        })}
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
  const { t, fmt } = useLocale()
  const { data: envs, isLoading } = useQuery({
    queryKey: ['environment-summaries'], queryFn: api.environmentSummaries,
  })
  const { data: recent } = useQuery({ queryKey: ['recent-deployments'], queryFn: () => api.recentDeployments(8) })
  const { data: blocked } = useQuery({ queryKey: ['blocked-from-release'], queryFn: () => api.blockedFromRelease(20) })

  if (isLoading) return <p className="eyebrow">{t('common.loading')}</p>

  return (
    <>
      <div className="sp-note">
        <Rocket aria-hidden="true" style={{ width: 16, height: 16, flex: '0 0 auto', color: 'var(--tx-3)' }} />
        <p>
          <b>{t('mywork.deploy.noteLead')}</b> {t('mywork.deploy.noteBody')}
        </p>
      </div>

      {envs && envs.length === 0 && (
        <Empty title={t('mywork.deploy.noEnvs.title')} desc={t('mywork.deploy.noEnvs.desc')} />
      )}

      <div className="env-strip">
        {(envs ?? []).map((e, i) => (
          <div className={`env${i === (envs?.length ?? 0) - 1 && e.buildLabel ? ' live' : ''}`} key={e.id}>
            <div className="env-l">{e.name}</div>
            {/* Principle 8: never deployed renders as "not connected", not as 0. */}
            <div className="env-v">{e.buildLabel ? fmt.number(e.requirementCount) : '—'}</div>
            <div className="env-b">{e.buildLabel ? t('mywork.deploy.build', { label: e.buildLabel }) : t('mywork.deploy.notConnected')}</div>
          </div>
        ))}
      </div>

      <div className="row" style={{ alignItems: 'stretch', gap: 12 }}>
        <div className="card" style={{ flex: '1.3 1 0', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
            <h2 className="section-h" style={{ margin: 0 }}>{t('mywork.deploy.recent')}</h2>
            <span className="sp" />
            <span className="hint muted">{t('mywork.deploy.fromCi')}</span>
          </div>
          {(recent ?? []).length === 0 && <p className="hint muted">{t('mywork.deploy.none')}</p>}
          {(recent ?? []).map((d) => (
            <div key={d.id} style={{ display: 'grid', gridTemplateColumns: '84px 92px 1fr auto', gap: 10, alignItems: 'center', padding: '7px 0', borderBottom: '1px solid var(--line)' }}>
              <span className="tk-rid mono">{d.buildLabel}</span>
              <span className={`badge ${d.environmentName.toLowerCase().startsWith('prod') ? 'st-approved' : 'st-draft'}`}>
                {d.environmentName}
              </span>
              <span style={{ fontSize: 11.5, color: d.succeeded ? 'var(--tx-2)' : 'var(--crit)' }}>
                {d.succeeded ? t('mywork.deploy.inBuild', { count: d.requirementCount }) : t('mywork.deploy.failed')}
              </span>
              <span className="hint muted" style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                {fmt.dateTime(d.deployedAt)}
              </span>
            </div>
          ))}
        </div>

        <div className="card" style={{ flex: '1 1 0', minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 10 }}>
            <h2 className="section-h" style={{ margin: 0 }}>{t('mywork.deploy.blocked')}</h2>
            <span className="sp" />
            <span className="badge pr-critical">{fmt.number(blocked?.length ?? 0)}</span>
          </div>
          {(blocked ?? []).length === 0 && (
            <p className="hint muted">{t('mywork.deploy.noneBlocked')}</p>
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

const LEGEND: { kind: CalKind; colour: string }[] = [
  { kind: 'task', colour: 'var(--tx-3)' },
  { kind: 'rev', colour: 'var(--info)' },
  { kind: 'rel', colour: 'var(--brand)' },
  { kind: 'dep', colour: 'var(--ok)' },
  { kind: 'risk', colour: 'var(--crit)' },
]

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/**
 * VYB-0372: plots only dates this domain genuinely has — a release's target date, a
 * clarification's derived due date, and a deployment's timestamp.
 * No date is invented: a release with no target simply does not appear, and a task has no
 * date of its own now that Planning is gone.
 */
function CalendarTab() {
  const { t, fmt } = useLocale()
  const [month, setMonth] = useState(() => { const d = new Date(); d.setDate(1); d.setHours(0, 0, 0, 0); return d })

  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const { data: clarifications } = useQuery({ queryKey: ['clarifications-blocking'], queryFn: api.blockingClarifications })
  const { data: deployments } = useQuery({ queryKey: ['recent-deployments'], queryFn: () => api.recentDeployments(8) })

  const events = useMemo<CalEvent[]>(() => {
    const out: CalEvent[] = []
    for (const r of releases ?? []) {
      if (r.targetDate) out.push({ date: new Date(r.targetDate), label: t('mywork.cal.releaseTarget', { name: r.name }), kind: 'rel' })
    }
    for (const c of clarifications ?? []) {
      if (c.dueAt) out.push({ date: new Date(c.dueAt), label: t('mywork.cal.clarification'), kind: 'rev' })
    }
    for (const d of deployments ?? []) {
      out.push({ date: new Date(d.deployedAt), label: t('mywork.cal.deployment', { build: d.buildLabel, env: d.environmentName }), kind: 'dep' })
    }
    return out
  }, [releases, clarifications, deployments, t])

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
        <button className="btn" onClick={() => shift(-1)} aria-label={t('mywork.cal.prev')}>◂</button>
        <span style={{ fontFamily: 'var(--f-serif)', fontSize: 19, fontWeight: 700 }} aria-live="polite">
          {fmt.monthYear(month)}
        </span>
        <button className="btn" onClick={() => shift(1)} aria-label={t('mywork.cal.next')}>▸</button>
        <span className="sp" />
        <button className="btn" onClick={() => { const d = new Date(); d.setDate(1); setMonth(d) }}>{t('mywork.cal.today')}</button>
      </div>

      <div className="cal">
        <div className="cal-hd">
          {[0, 1, 2, 3, 4, 5, 6].map((d) => <span key={d}>{fmt.weekdayShort(d)}</span>)}
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
                  {sameDay(date, today) ? t('mywork.cal.todayMark', { day: date.getDate() }) : date.getDate()}
                </span>
                {inMonth && shown.map((e, n) => (
                  <span key={n} className={`cal-ev ev-${e.kind}`} title={e.label}>{e.label}</span>
                ))}
                {inMonth && dayEvents.length > 3 && (
                  <span className="cal-more">{t('mywork.cal.more', { count: dayEvents.length - 3 })}</span>
                )}
              </div>
            )
          })}
        </div>
      </div>

      <div className="cal-legend">
        {LEGEND.map((l) => (
          <span key={l.kind}><i aria-hidden="true" style={{ background: l.colour }} />{t(`mywork.cal.legend.${l.kind}`)}</span>
        ))}
      </div>
      <p className="hint muted" style={{ marginTop: 10 }}>{t('mywork.cal.hint')}</p>
    </>
  )
}
