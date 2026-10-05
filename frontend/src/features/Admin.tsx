import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Plus, KeyRound, Download } from 'lucide-react'
import {
  ApiError, api, type AccessRoleName, type ScopeTypeName, type Grant, type AppUserView, type IntegrationView,
  type Team, type TeamRole,
} from '@/shared/api/client'
import { Page, Empty } from '@/shared/ui/Page'
import { Modal } from '@/shared/ui/Modal'
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog'
import { announce } from '@/shared/ui/Announcer'
import { useMe } from '@/shared/useMe'
import { useRovingGrid } from '@/shared/ui/useRovingGrid'
import { UserPicker } from '@/shared/ui/UserPicker'
import { ConnectorHealthTab } from './admin/ConnectorHealthTab'

type Tab = 'users' | 'teams' | 'grants' | 'roles' | 'service-accounts' | 'security' | 'audit' | 'integrations' | 'connector-health' | 'settings'
const TABS: { key: Tab; label: string }[] = [
  { key: 'users', label: 'Users' },
  { key: 'teams', label: 'Teams' },
  { key: 'grants', label: 'Grants' },
  { key: 'roles', label: 'Roles matrix' },
  { key: 'service-accounts', label: 'Service accounts' },
  { key: 'security', label: 'Security' },
  { key: 'audit', label: 'Audit log' },
  { key: 'integrations', label: 'Connected systems' },
  { key: 'connector-health', label: 'Connector health' },
  { key: 'settings', label: 'Settings' },
]

const ROLES: AccessRoleName[] = [
  'VIEWER', 'BUSINESS_ANALYST', 'REVIEWER', 'APPROVER', 'DEVELOPER',
  'TESTER', 'COMPLIANCE_LEAD', 'ARCHITECT', 'ADMINISTRATOR',
]
const SCOPE_TYPES: ScopeTypeName[] = ['PLATFORM', 'PRODUCT', 'APP', 'CAPABILITY', 'RELEASE']

/**
 * VYB-0700–0713/0730–0743/0750–0758: access, service accounts, security reporting,
 * settings and the connected-systems registry. Every mutating action here needs
 * ADMINISTRATOR on the backend regardless of what this screen shows — see
 * PrincipalGuard — so a non-administrator seeing this tab (shouldn't happen; Shell
 * hides it) would just get 403s, not a false sense of access.
 */
export function Admin() {
  const [tab, setTab] = useState<Tab>('users')
  const { data: me, isLoading } = useMe()

  if (isLoading) return <Page title="Administration" desc="Loading…"><p className="eyebrow">Loading…</p></Page>
  if (!me?.platformAdministrator) {
    return (
      <Page title="Administration" desc="Accounts, what each may do, and where they came from. Vyoog holds no passwords.">
        <Empty title="Administrator access required" desc="You aren't holding a platform ADMINISTRATOR grant — ask someone who does to grant you one, or the individual screens below aren't reachable." />
      </Page>
    )
  }

  return (
    <Page title="Administration" desc="Accounts, what each may do, and where they came from. Vyoog holds no passwords.">
      <div style={{ display: 'flex', gap: 6, marginBottom: 16, flexWrap: 'wrap' }}>
        {TABS.map((t) => (
          <button key={t.key} className={`btn${t.key === tab ? ' pri' : ''}`} onClick={() => setTab(t.key)}>{t.label}</button>
        ))}
      </div>
      {tab === 'users' && <UsersTab />}
      {tab === 'teams' && <TeamsTab />}
      {tab === 'grants' && <GrantsTab />}
      {tab === 'roles' && <RolesMatrixTab />}
      {tab === 'service-accounts' && <ServiceAccountsTab />}
      {tab === 'security' && <SecurityTab />}
      {tab === 'audit' && <AuditTab />}
      {tab === 'integrations' && <IntegrationsTab />}
      {tab === 'connector-health' && <ConnectorHealthTab />}
      {tab === 'settings' && <SettingsTab />}
    </Page>
  )
}

// ── Teams ─────────────────────────────────────────────────────────────────────────

/**
 * Teams, and who leads each one.
 *
 * <p>The lead is not decoration: Planning refuses an assignment made by anyone but a lead
 * of the assignee's team. Where a team has no lead the rule cannot be applied and
 * assignment stays open to everyone — so an unled team is shown as such rather than left
 * looking configured.
 *
 * <p>These are Vyoog's own teams, not an org chart borrowed from anywhere. No reporting
 * line, no capacity, no allocation — §13 rules those out and a lead here answers exactly
 * one question: who may hand this person work.
 */
function TeamsTab() {
  const qc = useQueryClient()
  const { data: teams, isLoading } = useQuery({ queryKey: ['teams'], queryFn: api.teams })
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')

  const create = useMutation({
    mutationFn: () => api.createTeam(name.trim()),
    onSuccess: () => {
      announce(`Team ${name.trim()} created.`)
      setName(''); setCreating(false)
      void qc.invalidateQueries({ queryKey: ['teams'] })
    },
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div style={{ display: 'flex', marginBottom: 12 }}>
        <div className="sp" />
        <button className="btn pri" onClick={() => setCreating(true)}><Plus /> New team</button>
      </div>

      {!teams?.length ? (
        <Empty title="No teams yet"
          desc="A team is what makes “who may assign this person work” answerable. Until one exists, anyone can assign to anyone." />
      ) : (
        teams.map((t) => <TeamCard key={t.id} team={t} />)
      )}

      {creating && (
        <Modal title="New team" onClose={() => setCreating(false)}>
          <div className="field">
            <label className="label">Name</label>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Platform" />
          </div>
          {create.isError && (
            <p className="err-text">
              {create.error instanceof ApiError ? (create.error.detail ?? create.error.title) : 'Could not create the team.'}
            </p>
          )}
          <div className="actions">
            <button className="btn" onClick={() => setCreating(false)}>Cancel</button>
            <button className="btn pri" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>
              {create.isPending ? 'Creating…' : 'Create'}
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

function TeamCard({ team }: { team: Team }) {
  const qc = useQueryClient()
  const { data: members } = useQuery({
    queryKey: ['team-members', team.id],
    queryFn: () => api.teamMembers(team.id),
  })
  const [adding, setAdding] = useState('')

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['team-members', team.id] })
    void qc.invalidateQueries({ queryKey: ['teams'] })
  }
  const add = useMutation({
    mutationFn: (userId: string) => api.addTeamMember(team.id, userId),
    onSuccess: () => { setAdding(''); invalidate() },
  })
  const setRole = useMutation({
    mutationFn: (v: { userId: string; role: TeamRole }) => api.setTeamMemberRole(team.id, v.userId, v.role),
    onSuccess: invalidate,
  })
  const remove = useMutation({
    mutationFn: (userId: string) => api.removeTeamMember(team.id, userId),
    onSuccess: invalidate,
  })

  const leads = members?.filter((m) => m.role === 'LEAD') ?? []
  const error = setRole.error ?? add.error ?? remove.error

  return (
    <div className="card" style={{ marginBottom: 12, padding: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <h4 className="section-h" style={{ margin: 0 }}>{team.name}</h4>
        {/* Principle 8: an unled team is stated, not left to look like a configured one. */}
        {members && leads.length === 0 && (
          <span className="hint muted">no lead — anyone can assign to these people</span>
        )}
        <div className="sp" />
        <span className="muted" style={{ fontSize: 11.5 }}>{members?.length ?? 0} member{members?.length === 1 ? '' : 's'}</span>
      </div>

      {members?.length ? (
        <table className="table">
          <thead><tr><th>Name</th><th>Email</th><th>Role</th><th /></tr></thead>
          <tbody>
            {members.map((m) => (
              <tr key={m.userId}>
                <td>{m.displayName}</td>
                <td className="muted" style={{ fontSize: 11.5 }}>{m.email}</td>
                <td>
                  <select className="select" value={m.role}
                    onChange={(e) => setRole.mutate({ userId: m.userId, role: e.target.value as TeamRole })}>
                    <option value="LEAD">Lead</option>
                    <option value="MEMBER">Member</option>
                  </select>
                </td>
                <td style={{ textAlign: 'right' }}>
                  <button className="btn" onClick={() => remove.mutate(m.userId)}>Remove</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="hint muted">Nobody in this team yet.</p>
      )}

      <div className="field" style={{ marginTop: 10, maxWidth: 320 }}>
        <label className="label">Add someone</label>
        <UserPicker value={adding} onSelect={(id) => { setAdding(id); add.mutate(id) }} placeholder="Search for a person…" />
      </div>

      {error != null && (
        <p className="err-text">{error instanceof ApiError ? (error.detail ?? error.title) : 'That did not work.'}</p>
      )}
    </div>
  )
}

// ── Users ─────────────────────────────────────────────────────────────────────────

/** An ISO instant as a person reads it. The directory rendered the raw string, which is unreadable in a table cell. */
function whenReadable(iso?: string | null): string {
  if (!iso) return 'never'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * A user cannot delegate to, or be managed by, someone who has left — the whole point of
 * both fields is that work reaches a person who is actually there. The directory offered
 * every user including DEPARTED ones, so it was possible to route a colleague's tasks
 * into a dead end from the picker itself.
 */
function assignableTo(all: AppUserView[], self: AppUserView): AppUserView[] {
  return all.filter((other) => other.id !== self.id && other.status !== 'DEPARTED')
}

function UsersTab() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['admin-users'], queryFn: api.users })
  const [creating, setCreating] = useState(false)
  // Every mutation here used to fail silently: the select snapped back on refetch with
  // no reason shown, which reads as "the click didn't register" rather than "the server
  // refused it". The reason the server gave is what belongs on screen.
  const [error, setError] = useState<string | null>(null)
  const invalidate = () => void qc.invalidateQueries({ queryKey: ['admin-users'] })
  const onError = (e: unknown, fallback: string) =>
    setError(e instanceof ApiError ? (e.detail ?? e.title) : fallback)

  const setStatus = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => api.setUserStatus(id, status),
    onSuccess: () => { setError(null); invalidate() },
    onError: (e) => onError(e, 'Could not change that status.'),
  })
  const setDelegate = useMutation({
    mutationFn: ({ id, delegateId }: { id: string; delegateId: string | null }) => api.setUserDelegate(id, delegateId),
    onSuccess: () => { setError(null); invalidate() },
    onError: (e) => onError(e, 'Could not set that delegate.'),
  })
  const setManager = useMutation({
    mutationFn: ({ id, managerId }: { id: string; managerId: string | null }) => api.setUserManager(id, managerId),
    onSuccess: () => { setError(null); invalidate() },
    onError: (e) => onError(e, 'Could not set that manager.'),
  })

  const grid = useRovingGrid(data?.length ?? 0, 9)

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <p className="hint muted" style={{ margin: 0, maxWidth: '68ch' }}>
          Vyoog mirrors identities from Keycloak; it stores no passwords and cannot create a sign-in.
          Adding someone here prepares their directory row so grants and a manager can be set before
          their first sign-in, which claims the row by email address.
        </p>
        <div className="sp" />
        <button className="btn pri" onClick={() => setCreating(true)}><Plus /> Add user</button>
      </div>

      {error && <p className="err-text" style={{ marginBottom: 10 }}>{error}</p>}

      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && data.length === 0 && (
        <Empty title="No users yet" desc="Users appear here on first sign-in, or add one now to prepare their access." />
      )}

      {data && data.length > 0 && (
        <div className="tbl-wrap">
          <table className="tbl" role="grid" aria-rowcount={data.length} aria-colcount={9} onKeyDown={grid.onKeyDown}>
            <thead>
              <tr role="row">
                <th>User</th><th>Source</th><th>Status</th><th>Status changed</th><th>MFA</th>
                <th>Last seen</th><th>Grants</th><th>Delegate</th><th>Manager</th>
              </tr>
            </thead>
            <tbody>
              {data.map((u: AppUserView, rowIdx) => (
                <tr key={u.id} role="row" style={{ background: u.departedButActive ? 'var(--high-dim)' : undefined }}>
                  <td {...grid.cellProps(rowIdx, 0)}>
                    <div style={{ fontWeight: 600 }}>{u.displayName}</div>
                    <div className="muted" style={{ fontSize: 11 }}>{u.email}</div>
                  </td>
                  <td className="mono muted" {...grid.cellProps(rowIdx, 1)}>
                    {u.source}
                    {/* Principle 8: a row nobody has signed into yet says so rather than
                        looking like an ordinary account that simply never appears. */}
                    {u.source === 'LOCAL' && u.lastSeenAt == null && (
                      <div style={{ fontSize: 10, color: 'var(--tx-3)' }}>awaiting first sign-in</div>
                    )}
                  </td>
                  <td {...grid.cellProps(rowIdx, 2)}>
                    <select
                      className="select" value={u.status}
                      onChange={(e) => setStatus.mutate({ id: u.id, status: e.target.value })}
                    >
                      {['ACTIVE', 'LEAVE', 'DEPARTED', 'EXTERNAL'].map((s) => <option key={s} value={s}>{s}</option>)}
                    </select>
                    {u.departedButActive && (
                      <div style={{ color: 'var(--high)', fontSize: 10.5, marginTop: 2 }}>Departed but still holds grants</div>
                    )}
                  </td>
                  {/* VYB-0705 AC1 asks for when the status last changed, not only what it
                      is now. The DTO has carried it all along; the table never showed it. */}
                  <td className="muted" {...grid.cellProps(rowIdx, 3, { fontSize: 11.5 })}>{whenReadable(u.statusChangedAt)}</td>
                  <td {...grid.cellProps(rowIdx, 4)}>{u.mfaEnrolled ? 'Yes' : 'No'}</td>
                  <td className="muted" {...grid.cellProps(rowIdx, 5, { fontSize: 11.5 })}>{whenReadable(u.lastSeenAt)}</td>
                  <td className="mono" {...grid.cellProps(rowIdx, 6)}>{u.activeGrantCount}</td>
                  <td {...grid.cellProps(rowIdx, 7)}>
                    {/* VYB-0706: who takes this user's derived tasks while they're away. */}
                    <select
                      className="select" value={u.delegateId ?? ''}
                      onChange={(e) => setDelegate.mutate({ id: u.id, delegateId: e.target.value || null })}
                    >
                      <option value="">— none —</option>
                      {assignableTo(data, u).map((other) => (
                        <option key={other.id} value={other.id}>{other.displayName}</option>
                      ))}
                    </select>
                  </td>
                  <td {...grid.cellProps(rowIdx, 8)}>
                    {/* VYB-0334/0792: who an ageing clarification escalates to, before falling back to the capability owner then the raiser. */}
                    <select
                      className="select" value={u.managerId ?? ''}
                      onChange={(e) => setManager.mutate({ id: u.id, managerId: e.target.value || null })}
                    >
                      <option value="">— none —</option>
                      {assignableTo(data, u).map((other) => (
                        <option key={other.id} value={other.id}>{other.displayName}</option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {creating && (
        <CreateUserModal
          onClose={() => setCreating(false)}
          onCreated={(name) => { setCreating(false); setError(null); invalidate(); announce(`${name} added to the directory`) }}
        />
      )}
    </>
  )
}

/**
 * VYB-0700: no password field, no invite-email field, no "set a temporary credential" —
 * Keycloak owns authentication and this dialog could not create a sign-in if it wanted
 * to. It records who someone is so their access can be prepared; the person still
 * arrives through the shared realm like everyone else.
 */
function CreateUserModal({ onClose, onCreated }: { onClose: () => void; onCreated: (displayName: string) => void }) {
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<AccessRoleName | ''>('DEVELOPER')
  // No scope picker: the role applies platform-wide, which is what the endpoint defaults
  // to when none is sent. Narrowing a role to one product or capability is what the
  // Grants tab is for, and it needs an id this dialog has no picker for.
  const create = useMutation({
    mutationFn: () => api.createUser({
      email: email.trim(), displayName: displayName.trim(), role: role || undefined,
    }),
    onSuccess: () => onCreated(displayName.trim()),
  })

  const canSubmit = email.trim().length > 0 && displayName.trim().length > 0 && !create.isPending

  return (
    <Modal title="Add a user" onClose={onClose}>
      <p className="hint muted" style={{ marginTop: 0 }}>
        If this address matches the one they sign in with, their first sign-in picks up this row and
        the role below comes with it. If it doesn't, the row and the role still stand on their own.
      </p>
      <div className="field">
        <label className="label" htmlFor="new-user-email">Email</label>
        <input
          id="new-user-email" className="input" type="email" value={email} autoComplete="off"
          onChange={(e) => setEmail(e.target.value)} placeholder="dana@vyoog.com"
        />
      </div>
      <div className="field">
        <label className="label" htmlFor="new-user-name">Display name</label>
        <input
          id="new-user-name" className="input" value={displayName} autoComplete="off"
          onChange={(e) => setDisplayName(e.target.value)} placeholder="Dana Reyes"
        />
      </div>
      {/* The role is a Vyoog access grant, not a Keycloak role — it applies the moment
          this returns and consults nothing outside this database. */}
      <div className="field">
        <label className="label" htmlFor="new-user-role">Role</label>
        <select
          id="new-user-role" className="select" value={role}
          onChange={(e) => setRole(e.target.value as AccessRoleName | '')}
        >
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
          <option value="">— no access yet —</option>
        </select>
        <span className="hint">
          Applies platform-wide and takes effect immediately — no sign-in needed. Narrow it to one
          product or capability later from the Grants tab.
        </span>
      </div>

      {create.isError && (
        <p className="err-text">
          {create.error instanceof ApiError ? (create.error.detail ?? create.error.title) : 'Could not add that user.'}
        </p>
      )}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn pri" disabled={!canSubmit} onClick={() => create.mutate()}>
          {create.isPending ? 'Adding…' : 'Add user'}
        </button>
      </div>
    </Modal>
  )
}

// ── Grants ────────────────────────────────────────────────────────────────────────

function GrantsTab() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [revoking, setRevoking] = useState<Grant | null>(null)
  const { data: grants, isLoading } = useQuery({ queryKey: ['admin-grants'], queryFn: api.grants })
  const { data: users } = useQuery({ queryKey: ['admin-users'], queryFn: api.users })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['admin-grants'] })
  const revoke = useMutation({
    mutationFn: (id: string) => api.revokeGrant(id),
    onSuccess: () => { announce('Grant revoked'); setRevoking(null); invalidate() },
  })

  const userLabel = (id: string) => users?.find((u) => u.id === id)?.email ?? id.slice(0, 8)
  const grid = useRovingGrid(grants?.length ?? 0, 6)

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }}>
        <button className="btn pri" onClick={() => setShowCreate(true)}><Plus /> Grant a role</button>
      </div>
      {grants && grants.length === 0 && <Empty title="No grants yet" desc="Grant the first one above." />}
      <div className="tbl-wrap">
        <table className="tbl" role="grid" aria-rowcount={grants?.length ?? 0} aria-colcount={6} onKeyDown={grid.onKeyDown}>
          <thead>
            <tr role="row"><th>User</th><th>Role</th><th>Scope</th><th>Expires</th><th>State</th><th /></tr>
          </thead>
          <tbody>
            {grants?.map((g, rowIdx) => (
              <tr key={g.id} role="row">
                <td {...grid.cellProps(rowIdx, 0)}>{userLabel(g.userId)}</td>
                <td className="mono" {...grid.cellProps(rowIdx, 1)}>{g.role}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 2)}>{g.scopeType}{g.scopeId ? ` ${g.scopeId.slice(0, 8)}` : ''}</td>
                <td className="muted" {...grid.cellProps(rowIdx, 3, { fontSize: 11.5 })}>{g.expiresAt ?? '—'}</td>
                <td {...grid.cellProps(rowIdx, 4)}>{g.revokedAt ? 'Revoked' : g.active ? 'Active' : 'Expired'}</td>
                <td {...grid.cellProps(rowIdx, 5)}>
                  {g.active && (
                    <button className="btn" onClick={() => setRevoking(g)}>Revoke</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showCreate && <CreateGrantModal onClose={() => setShowCreate(false)} onCreated={invalidate} users={users ?? []} />}

      {/* VYB-0758 AC1: names the user and scope, not a bare "are you sure?". */}
      {revoking && (
        <ConfirmDialog
          title="Revoke this grant?"
          description={`${userLabel(revoking.userId)} will immediately lose ${revoking.role} at ${revoking.scopeType}${revoking.scopeId ? ' ' + revoking.scopeId.slice(0, 8) : ''}.`}
          confirmLabel="Revoke"
          onConfirm={() => revoke.mutate(revoking.id)}
          onCancel={() => setRevoking(null)}
        />
      )}
    </>
  )
}

function CreateGrantModal({
  onClose, onCreated, users,
}: { onClose: () => void; onCreated: () => void; users: AppUserView[] }) {
  const [userId, setUserId] = useState('')
  const [role, setRole] = useState<AccessRoleName>('VIEWER')
  const [scopeType, setScopeType] = useState<ScopeTypeName>('PLATFORM')
  const [scopeId, setScopeId] = useState('')
  const [expiresAt, setExpiresAt] = useState('')

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const [productId, setProductId] = useState('')
  const { data: applications } = useQuery({
    queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId && scopeType !== 'PRODUCT',
  })
  const [applicationId, setApplicationId] = useState('')
  const { data: capabilities } = useQuery({
    queryKey: ['capabilities', applicationId], queryFn: () => api.capabilities(applicationId),
    enabled: !!applicationId && scopeType === 'CAPABILITY',
  })
  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases, enabled: scopeType === 'RELEASE' })

  const selectedUser = users.find((u) => u.id === userId)
  const expiryRequired = selectedUser?.status === 'EXTERNAL'

  const create = useMutation({
    mutationFn: () => api.createGrant({
      userId, role, scopeType,
      scopeId: scopeType === 'PLATFORM' ? undefined : scopeId || undefined,
      expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined,
    }),
    onSuccess: () => { announce('Grant created'); onCreated(); onClose() },
  })

  return (
    <Modal onClose={onClose} title="Grant a role">
      <h3>Grant a role</h3>
      <div className="field">
        <label className="label">User</label>
        <select className="select" value={userId} onChange={(e) => setUserId(e.target.value)}>
          <option value="">Select a user…</option>
          {users.map((u) => <option key={u.id} value={u.id}>{u.email}{u.status === 'EXTERNAL' ? ' (external)' : ''}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="label">Role</label>
        <select className="select" value={role} onChange={(e) => setRole(e.target.value as AccessRoleName)}>
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </div>
      <div className="field">
        <label className="label">Scope</label>
        <select
          className="select" value={scopeType}
          onChange={(e) => { setScopeType(e.target.value as ScopeTypeName); setScopeId(''); setProductId(''); setApplicationId('') }}
        >
          {SCOPE_TYPES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </div>
      {/* VYB-0751 AC1: the picker follows the real hierarchy rather than a free-text scope id. */}
      {(scopeType === 'PRODUCT' || scopeType === 'APP' || scopeType === 'CAPABILITY') && (
        <div className="field">
          <label className="label">Product</label>
          <select
            className="select" value={productId}
            onChange={(e) => { setProductId(e.target.value); setApplicationId(''); if (scopeType === 'PRODUCT') setScopeId(e.target.value) }}
          >
            <option value="">Select a product…</option>
            {products?.filter((p) => !p.archived).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
      )}
      {(scopeType === 'APP' || scopeType === 'CAPABILITY') && productId && (
        <div className="field">
          <label className="label">Application</label>
          <select
            className="select" value={applicationId}
            onChange={(e) => { setApplicationId(e.target.value); if (scopeType === 'APP') setScopeId(e.target.value) }}
          >
            <option value="">Select an application…</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
      )}
      {scopeType === 'CAPABILITY' && applicationId && (
        <div className="field">
          <label className="label">Capability</label>
          <select className="select" value={scopeId} onChange={(e) => setScopeId(e.target.value)}>
            <option value="">Select a capability…</option>
            {capabilities?.filter((c) => !c.archived).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
      )}
      {scopeType === 'RELEASE' && (
        <div className="field">
          <label className="label">Release</label>
          <select className="select" value={scopeId} onChange={(e) => setScopeId(e.target.value)}>
            <option value="">Select a release…</option>
            {releases?.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
        </div>
      )}
      <div className="field">
        <label className="label">Expires{expiryRequired ? ' (required — external user)' : ' (optional)'}</label>
        <input type="datetime-local" className="input" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} />
      </div>
      {create.isError && <p className="err-text">Could not create the grant — check the expiry rules for external users.</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button
          className="btn pri"
          disabled={!userId || (scopeType !== 'PLATFORM' && !scopeId) || (expiryRequired && !expiresAt) || create.isPending}
          onClick={() => create.mutate()}
        >
          Grant
        </button>
      </div>
    </Modal>
  )
}

// ── Roles matrix ──────────────────────────────────────────────────────────────────

function RolesMatrixTab() {
  const { data, isLoading } = useQuery({ queryKey: ['roles-matrix'], queryFn: api.rolesMatrix })
  const grid = useRovingGrid(data?.length ?? 0, 3)
  if (isLoading) return <p className="eyebrow">Loading…</p>
  return (
    <div className="tbl-wrap">
      <table className="tbl" role="grid" aria-rowcount={data?.length ?? 0} aria-colcount={3} onKeyDown={grid.onKeyDown}>
        <thead><tr role="row"><th>Role</th><th>May do</th><th>Enforced by</th></tr></thead>
        <tbody>
          {data?.map((c, i) => (
            <tr key={i} role="row">
              <td className="mono" {...grid.cellProps(i, 0)}>{c.role}</td>
              <td {...grid.cellProps(i, 1)}>{c.enforced ? c.description : <span className="muted">Not enforced by any endpoint yet</span>}</td>
              <td className="mono muted" {...grid.cellProps(i, 2, { fontSize: 10.5 })}>{c.enforcedBy}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="hint muted" style={{ padding: '8px 12px' }}>
        Generated from RoleCapabilityRegistry, not hand-typed here — a role that isn't checked anywhere says so honestly.
      </p>
    </div>
  )
}

// ── Service accounts ──────────────────────────────────────────────────────────────

function ServiceAccountsTab() {
  const qc = useQueryClient()
  const [showCreate, setShowCreate] = useState(false)
  const [rotating, setRotating] = useState<string | null>(null)
  const [newClientId, setNewClientId] = useState('')
  const { data, isLoading } = useQuery({ queryKey: ['service-accounts'], queryFn: api.serviceAccounts })
  const { data: knownScopes } = useQuery({ queryKey: ['known-scopes'], queryFn: api.knownServiceScopes })

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['service-accounts'] })
  const rotate = useMutation({
    mutationFn: ({ id, clientId }: { id: string; clientId: string }) => api.rotateServiceAccountKey(id, clientId),
    onSuccess: () => { announce('Service account key rotated'); setRotating(null); setNewClientId(''); invalidate() },
  })

  if (isLoading) return <p className="eyebrow">Loading…</p>

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 10 }}>
        <button className="btn pri" onClick={() => setShowCreate(true)}><Plus /> New service account</button>
      </div>
      {data && data.length === 0 && <Empty title="No service accounts yet" desc="Register one above." />}
      {data?.map((a) => (
        <div key={a.id} className="card" style={{ marginBottom: 10, borderColor: a.keyAgeDays > 90 ? 'var(--high-bd)' : undefined }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
            <strong style={{ flex: 1 }}>{a.name}</strong>
            {a.keyAgeDays > 90 && <span className="badge" style={{ background: 'var(--high-dim)', color: 'var(--high)' }}>Stale key</span>}
            <span className="mono muted" style={{ fontSize: 10 }}>{a.clientId}</span>
          </div>
          <p className="muted" style={{ fontSize: 12, margin: '0 0 6px' }}>{a.purpose}</p>
          <div className="mono muted" style={{ fontSize: 10.5, marginBottom: 6 }}>
            Scopes: {a.scopes.join(', ') || 'none (can do nothing)'} · Key age: {a.keyAgeDays}d ·{' '}
            {a.everUsed ? `last used ${a.lastUsedAt}` : 'never used'}
          </div>
          <button className="btn" onClick={() => setRotating(a.id)}><KeyRound /> Rotate key</button>
        </div>
      ))}

      {showCreate && (
        <CreateServiceAccountModal onClose={() => setShowCreate(false)} onCreated={invalidate} knownScopes={knownScopes ?? []} />
      )}

      {rotating && (
        <Modal onClose={() => setRotating(null)} title="Rotate service account key">
          <h3>Rotate key</h3>
          <p className="muted" style={{ fontSize: 12.5 }}>
            The previous client id keeps working during the configured overlap window — this doesn't invalidate it immediately.
          </p>
          <div className="field">
            <label className="label">New client id</label>
            <input className="input" value={newClientId} onChange={(e) => setNewClientId(e.target.value)} />
          </div>
          <div className="actions">
            <button className="btn" onClick={() => setRotating(null)}>Cancel</button>
            <button
              className="btn pri" disabled={!newClientId.trim() || rotate.isPending}
              onClick={() => rotate.mutate({ id: rotating, clientId: newClientId.trim() })}
            >
              Rotate
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

function CreateServiceAccountModal({
  onClose, onCreated, knownScopes,
}: { onClose: () => void; onCreated: () => void; knownScopes: string[] }) {
  const [name, setName] = useState('')
  const [purpose, setPurpose] = useState('')
  const [clientId, setClientId] = useState('')
  const [scopes, setScopes] = useState<Set<string>>(new Set())

  const create = useMutation({
    mutationFn: () => api.createServiceAccount({ name, purpose, clientId, scopes: [...scopes] }),
    onSuccess: () => { onCreated(); onClose() },
  })

  const toggleScope = (s: string) => setScopes((prev) => {
    const next = new Set(prev)
    next.has(s) ? next.delete(s) : next.add(s)
    return next
  })

  return (
    <Modal onClose={onClose} title="New service account">
      <h3>New service account</h3>
      <div className="field">
        <label className="label">Name</label>
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Purpose</label>
        <input className="input" value={purpose} onChange={(e) => setPurpose(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Keycloak client id</label>
        <input className="input" value={clientId} onChange={(e) => setClientId(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Scopes (at least one — none means it can do nothing)</label>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {knownScopes.map((s) => (
            <label key={s} className="mono" style={{ fontSize: 11.5, display: 'flex', alignItems: 'center', gap: 4 }}>
              <input type="checkbox" checked={scopes.has(s)} onChange={() => toggleScope(s)} />
              {s}
            </label>
          ))}
        </div>
      </div>
      {create.isError && <p className="err-text">Could not create — check the name is unique and at least one scope is selected.</p>}
      <div className="actions">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button
          className="btn pri" disabled={!name.trim() || !clientId.trim() || scopes.size === 0 || create.isPending}
          onClick={() => create.mutate()}
        >
          Create
        </button>
      </div>
    </Modal>
  )
}

// ── Security ──────────────────────────────────────────────────────────────────────

function SecurityTab() {
  const { data, isLoading } = useQuery({ queryKey: ['security-report'], queryFn: api.securityReport })
  if (isLoading) return <p className="eyebrow">Loading…</p>
  if (!data) return null

  const sections: { title: string; items: { id: string; title: string; detail?: string; suggestion?: string }[] }[] = [
    { title: 'Separation of duties', items: data.separationOfDuties },
    { title: 'Departed accounts still active', items: data.departedAccounts },
    { title: 'Stale service account keys', items: data.staleKeys },
  ]

  return (
    <>
      {sections.map((s) => (
        <div key={s.title} style={{ marginBottom: 16 }}>
          <h4 className="section-h">{s.title}</h4>
          {s.items.length === 0 && <Empty title="Nothing here" desc="No open findings for this check right now." />}
          {s.items.map((f) => (
            <div key={f.id} className="card" style={{ marginBottom: 8, borderColor: 'var(--crit-bd)' }}>
              <strong>{f.title}</strong>
              {f.detail && <p className="muted" style={{ fontSize: 12, margin: '4px 0' }}>{f.detail}</p>}
              {/* VYB-0754 AC1: the corrective action, always alongside the finding. */}
              {f.suggestion && <p style={{ fontSize: 12, margin: 0, color: 'var(--ai-tx)' }}>{f.suggestion}</p>}
            </div>
          ))}
        </div>
      ))}
      <div>
        <h4 className="section-h">Expiring external grants (next 14 days)</h4>
        {data.expiringExternalGrants.length === 0 && <Empty title="None expiring soon" desc="Nothing to renew or revoke right now." />}
        {data.expiringExternalGrants.map((g) => (
          <div key={g.grantId} className="list-item">
            <span style={{ flex: 1 }}>{g.role} — expires {g.expiresAt}</span>
            <span className="hint muted" style={{ fontSize: 11 }}>Renew or revoke before it expires (Grants tab)</span>
          </div>
        ))}
      </div>
    </>
  )
}

// ── Audit log ─────────────────────────────────────────────────────────────────────

function AuditTab() {
  const [actorId, setActorId] = useState('')
  const [action, setAction] = useState('')
  const [objectType, setObjectType] = useState('')
  const [page, setPage] = useState(0)

  const { data, isLoading } = useQuery({
    queryKey: ['audit', actorId, action, objectType, page],
    queryFn: () => api.auditSearch({
      actorId: actorId || undefined, action: action || undefined, objectType: objectType || undefined, page, size: 25,
    }),
  })
  const grid = useRovingGrid(data?.content.length ?? 0, 6)

  return (
    <>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
        <input className="input" placeholder="Actor id" value={actorId} onChange={(e) => { setActorId(e.target.value); setPage(0) }} />
        <input className="input" placeholder="Action contains…" value={action} onChange={(e) => { setAction(e.target.value); setPage(0) }} />
        <input className="input" placeholder="Object type" value={objectType} onChange={(e) => { setObjectType(e.target.value); setPage(0) }} />
      </div>
      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && data.content.length === 0 && <Empty title="No matching audit events" desc="Widen the filters above." />}
      <div className="tbl-wrap">
        <table className="tbl" role="grid" aria-rowcount={data?.content.length ?? 0} aria-colcount={6} onKeyDown={grid.onKeyDown}>
          <thead><tr role="row"><th>When</th><th>Actor</th><th>Action</th><th>Object</th><th>Request</th><th>IP</th></tr></thead>
          <tbody>
            {data?.content.map((e, rowIdx) => (
              <tr key={e.id} role="row">
                <td className="mono muted" {...grid.cellProps(rowIdx, 0, { fontSize: 10.5 })}>{e.occurredAt}</td>
                <td className="mono" {...grid.cellProps(rowIdx, 1, { fontSize: 10.5 })}>{e.actorType}{e.actorId ? ` ${e.actorId.slice(0, 8)}` : ''}</td>
                <td {...grid.cellProps(rowIdx, 2)}>{e.action}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 3, { fontSize: 10.5 })}>{e.objectType}{e.objectId ? ` ${e.objectId.slice(0, 8)}` : ''}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 4, { fontSize: 10 })}>{e.requestId?.slice(0, 8)}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 5, { fontSize: 10 })}>{e.ip ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 12 }}>
          <button className="btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
          <span className="eyebrow">Page {data.number + 1} of {Math.max(data.totalPages, 1)} — {data.totalElements} total</span>
          <button className="btn" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
        </div>
      )}
      <p className="hint muted" style={{ marginTop: 10 }}>No delete action exists anywhere in this interface — the store itself refuses updates and deletes.</p>

      <AuditPartitionsPanel />
    </>
  )
}

/**
 * VYB-0723: real Postgres partitions of audit_event (V010), not a simulated
 * retention concept — each row here is one actual partition, with its real row
 * count. "Archive eligible now" runs the same job the nightly schedule runs.
 */
function AuditPartitionsPanel() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['audit-partitions'], queryFn: api.auditPartitions })
  const archive = useMutation({
    mutationFn: api.archiveAuditPartitions,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['audit-partitions'] }),
  })
  const grid = useRovingGrid(data?.length ?? 0, 4)

  return (
    <div style={{ marginTop: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <h4 className="section-h">Retention: partitions</h4>
        <button className="btn" disabled={archive.isPending} onClick={() => archive.mutate()}>
          Archive eligible partitions now
        </button>
      </div>
      <p className="hint muted" style={{ fontSize: 11 }}>
        One partition per month. Archiving detaches a whole partition once every row in it is past the audit
        retention threshold above — a real DDL operation, not a bulk delete (audit_event's append-only trigger
        would refuse that). An archived partition is renamed, not dropped — still queryable by name.
      </p>
      {archive.data && archive.data.archived.length > 0 && (
        <p className="hint" style={{ color: 'var(--ok)' }}>Archived: {archive.data.archived.join(', ')}</p>
      )}
      {archive.data && archive.data.archived.length === 0 && (
        <p className="hint muted">Nothing eligible right now.</p>
      )}
      {isLoading && <p className="eyebrow">Loading…</p>}
      {data && (
        <div className="tbl-wrap" style={{ marginTop: 8 }}>
          <table className="tbl" role="grid" aria-rowcount={data.length} aria-colcount={4} onKeyDown={grid.onKeyDown}>
            <thead><tr role="row"><th>Partition</th><th>Range</th><th>Rows</th><th>State</th></tr></thead>
            <tbody>
              {data.map((p, rowIdx) => (
                <tr key={p.name} role="row">
                  <td className="mono" {...grid.cellProps(rowIdx, 0, { fontSize: 11 })}>{p.name}</td>
                  <td className="mono muted" {...grid.cellProps(rowIdx, 1, { fontSize: 10.5 })}>
                    {p.rangeStart && p.rangeEnd ? `${p.rangeStart} – ${p.rangeEnd}` : '—'}
                  </td>
                  <td className="mono" {...grid.cellProps(rowIdx, 2)}>{p.rowCount}</td>
                  <td {...grid.cellProps(rowIdx, 3)}>{p.archived
                    ? <span className="badge" style={{ color: 'var(--tx-2)' }}>Archived</span>
                    : <span className="badge" style={{ color: 'var(--brand)', borderColor: 'var(--brand-bd)' }}>Active</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ── Connected systems ─────────────────────────────────────────────────────────────

function IntegrationsTab() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['integrations'], queryFn: api.integrations })
  const [configuring, setConfiguring] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const setConnected = useMutation({
    mutationFn: ({ key, connected }: { key: string; connected: boolean }) => api.setIntegrationConnected(key, connected),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['integrations'] }),
  })
  const grid = useRovingGrid(data?.length ?? 0, 5)

  if (isLoading) return <p className="eyebrow">Loading…</p>
  const connectedCount = data?.filter((i) => i.connected).length ?? 0

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <p className="hint muted">{connectedCount} of {data?.length ?? 0} connected.</p>
        <button className="btn" onClick={() => { setAdding(!adding); setConfiguring(null) }}>
          {adding ? 'Cancel' : 'Add connection'}
        </button>
      </div>
      {adding && <AddIntegrationForm onClose={() => setAdding(false)} />}
      <div className="tbl-wrap">
        <table className="tbl" role="grid" aria-rowcount={data?.length ?? 0} aria-colcount={5} onKeyDown={grid.onKeyDown}>
          <thead><tr role="row"><th>System</th><th>Owns</th><th>Direction</th><th>State</th><th /></tr></thead>
          <tbody>
            {data?.map((i, rowIdx) => (
              <tr key={i.key} role="row">
                <td className="mono" {...grid.cellProps(rowIdx, 0)}>{i.key}</td>
                <td className="muted" {...grid.cellProps(rowIdx, 1)}>{i.owns}</td>
                <td className="mono muted" {...grid.cellProps(rowIdx, 2)}>{i.direction}</td>
                <td {...grid.cellProps(rowIdx, 3)}>
                  {i.degraded
                    ? <span style={{ color: 'var(--crit)' }}>Degraded — {i.lastError}</span>
                    : i.connected ? 'Connected' : 'Not connected'}
                </td>
                <td {...grid.cellProps(rowIdx, 4, { display: 'flex', gap: 6 })}>
                  <button className="btn" onClick={() => setConnected.mutate({ key: i.key, connected: !i.connected })}>
                    {i.connected ? 'Disconnect' : 'Mark connected'}
                  </button>
                  <button className="btn" onClick={() => { setConfiguring(configuring === i.key ? null : i.key); setAdding(false) }}>
                    Configure
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {configuring && data && (
        <IntegrationConfigPanel connection={data.find((i) => i.key === configuring)!} onClose={() => setConfiguring(null)} />
      )}
    </>
  )
}

/** VYB-0839: registers a new row only — no code runs against it until something is written to read its config, same as every other connection here. */
function AddIntegrationForm({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [key, setKey] = useState('')
  const [owns, setOwns] = useState('')
  const [direction, setDirection] = useState('OUTBOUND')

  const create = useMutation({
    mutationFn: () => api.createIntegration(key.trim(), owns.trim(), direction),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['integrations'] }); onClose() },
  })

  return (
    <div className="card" style={{ marginBottom: 12 }}>
      <div className="eyebrow" style={{ marginBottom: 8 }}>New connection</div>
      <div className="field">
        <label className="label">Key <span className="hint muted">(short, unique id — e.g. "delivery-tool-2"; can't be changed later)</span></label>
        <input className="input" placeholder="e.g. delivery-tool-2" value={key} onChange={(e) => setKey(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Owns <span className="hint muted">(what this system is for)</span></label>
        <input className="input" placeholder="e.g. second delivery-tool push" value={owns} onChange={(e) => setOwns(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Direction</label>
        <select className="input" value={direction} onChange={(e) => setDirection(e.target.value)}>
          <option value="OUTBOUND">OUTBOUND — Vyoog pushes to it</option>
          <option value="INBOUND">INBOUND — it pushes to Vyoog</option>
          <option value="BOTH">BOTH</option>
        </select>
      </div>
      {create.isError && (
        <p className="err-text">
          {create.error instanceof ApiError ? (create.error.detail ?? create.error.title) : 'Could not create the connection.'}
        </p>
      )}
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="btn pri" disabled={!key.trim() || create.isPending} onClick={() => create.mutate()}>Create</button>
        <button className="btn" onClick={onClose}>Cancel</button>
      </div>
    </div>
  )
}

/**
 * VYB-0465/0507/0839: where every connection's editable fields actually get set — the
 * config column never had a UI, or a setter, before session 16, and owns/direction
 * never had one before session 55. "planning" keeps its own structured push-config
 * fields (real code — {@code BriefPushService} — reads exactly those keys); "git" gets
 * a single structured "Repo URL" field (requested ahead of any code reading it — see
 * BUILD-REGISTER.md session 57). Every other connection gets a raw JSON editor instead,
 * since nothing in this codebase is wired to read a specific shape out of their config
 * yet and a dedicated field would be guessing at one.
 */
function IntegrationConfigPanel({ connection, onClose }: { connection: IntegrationView; onClose: () => void }) {
  const qc = useQueryClient()
  const existing = (() => { try { return JSON.parse(connection.config ?? '{}') } catch { return {} } })()
  const [pushUrl, setPushUrl] = useState(existing.pushUrl ?? '')
  const [apiKey, setApiKey] = useState(existing.apiKey ?? '')
  const [customerName, setCustomerName] = useState(existing.customerName ?? '')
  const [repoUrl, setRepoUrl] = useState(existing.repoUrl ?? '')
  const [configJson, setConfigJson] = useState(connection.config ?? '')
  const [configJsonError, setConfigJsonError] = useState(false)
  const [owns, setOwns] = useState(connection.owns ?? '')
  const [direction, setDirection] = useState(connection.direction)
  const [secret, setSecret] = useState('')

  const isPlanning = connection.key === 'planning'
  const isGit = connection.key === 'git'
  const invalidate = () => void qc.invalidateQueries({ queryKey: ['integrations'] })

  const saveDetails = useMutation({
    mutationFn: () => api.setIntegrationConnected(connection.key, connection.connected, undefined, owns, direction),
    onSuccess: invalidate,
  })
  const saveConfig = useMutation({
    mutationFn: () => api.setIntegrationConfig(connection.key, JSON.stringify({ pushUrl, apiKey, customerName })),
    onSuccess: invalidate,
  })
  const saveRepoUrl = useMutation({
    mutationFn: () => api.setIntegrationConfig(connection.key, JSON.stringify({ repoUrl })),
    onSuccess: invalidate,
  })
  const saveRawConfig = useMutation({
    mutationFn: () => api.setIntegrationConfig(connection.key, configJson),
    onSuccess: invalidate,
  })
  const saveSecret = useMutation({
    mutationFn: () => api.setIntegrationConnected(connection.key, connection.connected, secret),
    onSuccess: () => { setSecret(''); invalidate() },
  })

  return (
    <div className="card" style={{ marginTop: 12 }}>
      <div className="eyebrow" style={{ marginBottom: 8 }}>Configure "{connection.key}"</div>
      <div className="field">
        <label className="label">Owns <span className="hint muted">(what this system is for)</span></label>
        <input className="input" value={owns} onChange={(e) => setOwns(e.target.value)} />
      </div>
      <div className="field">
        <label className="label">Direction</label>
        <select className="input" value={direction} onChange={(e) => setDirection(e.target.value)}>
          <option value="OUTBOUND">OUTBOUND — Vyoog pushes to it</option>
          <option value="INBOUND">INBOUND — it pushes to Vyoog</option>
          <option value="BOTH">BOTH</option>
        </select>
      </div>
      <div className="field">
        <button className="btn pri" disabled={saveDetails.isPending} onClick={() => saveDetails.mutate()}>Save details</button>
      </div>

      {isPlanning ? (
        <>
          <div className="field">
            <label className="label">Push URL</label>
            <input className="input" placeholder="https://…" value={pushUrl} onChange={(e) => setPushUrl(e.target.value)} />
          </div>
          <div className="field">
            <label className="label">API key <span className="hint muted">(optional — sent as X-API-Key if the receiver needs one)</span></label>
            <input className="input" placeholder="e.g. local-dev-vyoog-push-key-change-me" value={apiKey} onChange={(e) => setApiKey(e.target.value)} />
          </div>
          <div className="field">
            <label className="label">Customer name <span className="hint muted">(sent to the receiver — defaults to "vyoog" if left blank)</span></label>
            <input className="input" placeholder="vyoog" value={customerName} onChange={(e) => setCustomerName(e.target.value)} />
          </div>
          <div className="field">
            <button className="btn pri" disabled={saveConfig.isPending} onClick={() => saveConfig.mutate()}>Save</button>
          </div>
        </>
      ) : isGit ? (
        <div className="field">
          <label className="label">Repo URL</label>
          <div style={{ display: 'flex', gap: 6 }}>
            <input
              className="input" style={{ flex: 1 }} placeholder="git@github.com:org/repo.git"
              value={repoUrl} onChange={(e) => setRepoUrl(e.target.value)}
            />
            <button className="btn pri" disabled={saveRepoUrl.isPending} onClick={() => saveRepoUrl.mutate()}>Save</button>
          </div>
        </div>
      ) : (
        <div className="field">
          <label className="label">Config (JSON) <span className="hint muted">(freeform — only matters once code is written to read it)</span></label>
          <textarea
            className="input" rows={4} style={{ fontFamily: 'monospace', fontSize: 12 }}
            value={configJson}
            onChange={(e) => { setConfigJson(e.target.value); setConfigJsonError(false) }}
          />
          {configJsonError && <p className="err-text">That isn't valid JSON.</p>}
          <button
            className="btn pri" style={{ marginTop: 6 }} disabled={saveRawConfig.isPending}
            onClick={() => {
              if (configJson.trim() && (() => { try { JSON.parse(configJson); return false } catch { return true } })()) {
                setConfigJsonError(true)
                return
              }
              saveRawConfig.mutate()
            }}
          >
            Save
          </button>
        </div>
      )}

      <div className="field">
        <label className="label">Shared secret {connection.webhookConfigured && <span className="hint muted">(one is already set — this replaces it)</span>}</label>
        <div style={{ display: 'flex', gap: 6 }}>
          <input className="input" style={{ flex: 1 }} type="password" value={secret} onChange={(e) => setSecret(e.target.value)} />
          <button className="btn" disabled={!secret.trim() || saveSecret.isPending} onClick={() => saveSecret.mutate()}>Save</button>
        </div>
        <p className="hint muted" style={{ fontSize: 10.5 }}>
          {direction === 'INBOUND'
            ? 'Verifies this system\'s signed webhook deliveries — it needs the same secret.'
            : 'Signs every push with HMAC-SHA256 (X-Vyoog-Signature header) — the receiving side needs the same secret to verify it.'}
        </p>
      </div>
      <button className="btn" onClick={onClose}>Close</button>
    </div>
  )
}

// ── Settings ──────────────────────────────────────────────────────────────────────

function SettingsTab() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['app-config'], queryFn: api.settings })
  const [prefix, setPrefix] = useState('')
  const [suspendReason, setSuspendReason] = useState('')
  const [showSuspendConfirm, setShowSuspendConfirm] = useState(false)
  const [previewDays, setPreviewDays] = useState<Record<string, string>>({})
  const [previewResult, setPreviewResult] = useState<Record<string, number>>({})

  const invalidate = () => void qc.invalidateQueries({ queryKey: ['app-config'] })
  const setPrefixMut = useMutation({ mutationFn: (p: string) => api.setReqKeyPrefix(p), onSuccess: invalidate })
  const setThresholds = useMutation({
    mutationFn: (thresholds: Record<string, number>) => api.setStageThresholds(thresholds), onSuccess: invalidate,
  })
  const suspend = useMutation({ mutationFn: (reason: string) => api.suspend(reason), onSuccess: invalidate })
  const resume = useMutation({ mutationFn: api.resume, onSuccess: invalidate })
  const exportMut = useMutation({ mutationFn: api.exportTenant })
  const exportBundleMut = useMutation({ mutationFn: api.downloadTenantExportBundle })

  // VYB-0757: every one of these was, until now, plain read-only mono text on this
  // screen — a backend setter existing for four of them didn't help while nothing
  // in the UI ever called it.
  const setMaxGrant = useMutation({ mutationFn: (n: number) => api.setMaxExternalGrantDays(n), onSuccess: invalidate })
  const setStaleKey = useMutation({ mutationFn: (n: number) => api.setStaleKeyAgeDays(n), onSuccess: invalidate })
  const setRotationOverlap = useMutation({ mutationFn: (n: number) => api.setKeyRotationOverlapDays(n), onSuccess: invalidate })
  const setAuditRetention = useMutation({ mutationFn: (n: number) => api.setAuditRetentionDays(n), onSuccess: invalidate })
  const setNoisyCeiling = useMutation({ mutationFn: (n: number) => api.setNoisyDetectorDismissalCeiling(n), onSuccess: invalidate })
  const setAiCalls = useMutation({ mutationFn: (n: number) => api.setAiCallsPerRunLimit(n), onSuccess: invalidate })
  const setEmbedModel = useMutation({ mutationFn: (m: string) => api.setEmbeddingModel(m), onSuccess: invalidate })
  const [resetPreview, setResetPreview] = useState<{ tables: { table: string; rowCount: number }[]; totalRows: number } | null>(null)
  const [showResetConfirm, setShowResetConfirm] = useState(false)
  const [resetPhrase, setResetPhrase] = useState('')
  const resetMut = useMutation({
    mutationFn: () => api.resetTenant(resetPhrase),
    onSuccess: () => { setShowResetConfirm(false); setResetPhrase(''); setResetPreview(null); invalidate() },
  })

  if (isLoading || !data) return <p className="eyebrow">Loading…</p>

  const preview = async (status: string) => {
    const days = Number(previewDays[status] ?? data.stageStallThresholdDays[status] ?? 0)
    const count = await api.previewStageThreshold(status, days)
    setPreviewResult((prev) => ({ ...prev, [status]: count }))
  }

  const downloadExport = async () => {
    const manifest = await exportMut.mutateAsync()
    const blob = new Blob([JSON.stringify(manifest, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `vyoog-export-${manifest.generatedAt}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  /** VYB-0732: the real full export — manifest.json plus every attachment's actual bytes, zipped. */
  const downloadExportBundle = async () => {
    const blob = await exportBundleMut.mutateAsync()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `vyoog-export-bundle-${new Date().toISOString()}.zip`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
      <div>
        <h4 className="section-h">Requirement key prefix</h4>
        <p className="hint muted" style={{ fontSize: 11.5 }}>Changing this only affects keys allocated from now on — existing keys are untouched.</p>
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="input" placeholder={data.reqKeyPrefix} value={prefix} onChange={(e) => setPrefix(e.target.value)} />
          <button className="btn pri" disabled={!prefix.trim() || setPrefixMut.isPending} onClick={() => setPrefixMut.mutate(prefix.trim())}>
            Save
          </button>
        </div>

        <h4 className="section-h" style={{ marginTop: 20 }}>Stage stall thresholds (days)</h4>
        {Object.entries(data.stageStallThresholdDays).map(([status, days]) => (
          <div key={status} style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
            <span className="mono muted" style={{ width: 90 }}>{status}</span>
            <input
              className="input" style={{ width: 80 }} placeholder={String(days)}
              value={previewDays[status] ?? ''}
              onChange={(e) => setPreviewDays((prev) => ({ ...prev, [status]: e.target.value }))}
            />
            <button className="btn" onClick={() => preview(status)}>Preview effect</button>
            {previewResult[status] != null && (
              <span className="hint" style={{ fontSize: 11 }}>{previewResult[status]} requirement(s) affected</span>
            )}
            <button
              className="btn pri"
              onClick={() => setThresholds.mutate({ ...data.stageStallThresholdDays, [status]: Number(previewDays[status] ?? days) })}
            >
              Apply
            </button>
          </div>
        ))}

        <h4 className="section-h" style={{ marginTop: 20 }}>Deployment</h4>
        {data.suspended ? (
          <div className="card" style={{ borderColor: 'var(--crit-bd)' }}>
            <strong style={{ color: 'var(--crit)' }}>Suspended</strong>
            <p className="muted" style={{ fontSize: 12 }}>{data.suspendedReason}</p>
            <button className="btn pri" onClick={() => resume.mutate()}>Resume</button>
          </div>
        ) : (
          <>
            <input className="input" placeholder="Reason for suspending" value={suspendReason} onChange={(e) => setSuspendReason(e.target.value)} style={{ marginBottom: 8, width: '100%' }} />
            <button className="btn" disabled={!suspendReason.trim()} onClick={() => setShowSuspendConfirm(true)}>Suspend deployment</button>
          </>
        )}
      </div>

      <div>
        <h4 className="section-h">Export</h4>
        <p className="hint muted" style={{ fontSize: 11.5 }}>
          Every table, as rows, in one JSON manifest. Attachment metadata is included; the underlying binary
          content lives in object storage, not this file — use the full bundle below to get both together.
        </p>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" disabled={exportMut.isPending} onClick={downloadExport}><Download /> Download manifest (JSON)</button>
          <button className="btn pri" disabled={exportBundleMut.isPending} onClick={downloadExportBundle}>
            <Download /> {exportBundleMut.isPending ? 'Bundling…' : 'Download full bundle (.zip)'}
          </button>
        </div>
        {exportBundleMut.isError && (
          <p className="hint" style={{ color: 'var(--crit)', fontSize: 11.5, marginTop: 6 }}>
            Could not build the export bundle — object storage may be unreachable.
          </p>
        )}

        <h4 className="section-h" style={{ marginTop: 20 }}>Other thresholds</h4>
        <EditableField label="Max external grant duration" unit="days" value={data.maxExternalGrantDays}
          onSave={(raw) => setMaxGrant.mutate(Number(raw))} pending={setMaxGrant.isPending} />
        <EditableField label="Stale service-account key age" unit="days" value={data.staleKeyAgeDays}
          onSave={(raw) => setStaleKey.mutate(Number(raw))} pending={setStaleKey.isPending} />
        <EditableField label="Key rotation overlap" unit="days" value={data.keyRotationOverlapDays}
          onSave={(raw) => setRotationOverlap.mutate(Number(raw))} pending={setRotationOverlap.isPending} />
        <EditableField label="Audit retention" unit="days" value={data.auditRetentionDays}
          onSave={(raw) => setAuditRetention.mutate(Number(raw))} pending={setAuditRetention.isPending} />
        <EditableField label="Noisy-detector dismissal ceiling (0–100, stored as a 0–1 fraction)" unit="%"
          value={Math.round(data.noisyDetectorDismissalCeiling * 100)}
          onSave={(raw) => setNoisyCeiling.mutate(Number(raw) / 100)} pending={setNoisyCeiling.isPending} />
        <EditableField label="AI calls per sweep" unit="calls" value={data.aiCallsPerRunLimit}
          onSave={(raw) => setAiCalls.mutate(Number(raw))} pending={setAiCalls.isPending} />
        <EditableField label="Embedding model" value={data.embeddingModel}
          onSave={(raw) => setEmbedModel.mutate(raw)} pending={setEmbedModel.isPending} />
        <p className="hint muted" style={{ fontSize: 10.5, marginTop: 6 }}>
          Every field above is live-read and live-written — nothing here is a stale copy, and nothing is
          view-only anymore.
        </p>

        <AiUsageCard />

        {/* VYB-0733, reframed: docs/DECISIONS.md D3 already ruled multi-tenant
            "delete a tenant" out of scope (there's exactly one tenant here) — this is
            the real substance instead: an irreversible wipe of every row this
            deployment owns, for operational use, not multi-tenant lifecycle. */}
        <h4 className="section-h" style={{ marginTop: 20, color: 'var(--crit)' }}>Hard reset</h4>
        <p className="hint muted" style={{ fontSize: 11.5 }}>
          Irreversible. Wipes every row this application owns — requirements, findings, reviews, everything —
          except the audit trail (append-only, and this action is recorded in it) and this settings row itself.
          Not "delete this tenant": this deployment has exactly one, per docs/DECISIONS.md D3 — this is a full
          data wipe, named for what it actually does.
        </p>
        <button className="btn" style={{ borderColor: 'var(--crit-bd)', color: 'var(--crit)' }}
                onClick={async () => setResetPreview(await api.resetPreview())}>
          Preview what would be wiped
        </button>
        {resetPreview && (
          <div className="card" style={{ marginTop: 8, borderColor: 'var(--crit-bd)' }}>
            <div className="mono" style={{ fontSize: 11.5 }}>{resetPreview.totalRows} row(s) across {resetPreview.tables.length} table(s)</div>
            <div className="mono muted" style={{ fontSize: 10.5, maxHeight: 120, overflow: 'auto', marginTop: 6 }}>
              {resetPreview.tables.filter((t) => t.rowCount > 0).map((t) => (
                <div key={t.table}>{t.table}: {t.rowCount}</div>
              ))}
            </div>
            <button className="btn pri" style={{ marginTop: 8, background: 'var(--crit)', borderColor: 'var(--crit)' }}
                    onClick={() => setShowResetConfirm(true)}>
              Continue to confirm
            </button>
          </div>
        )}
      </div>

      {showSuspendConfirm && (
        <ConfirmDialog
          title="Suspend this deployment?"
          description="Every request will be refused with the reason you gave, until resumed. Authentication still works; no data is touched."
          confirmLabel="Suspend"
          onConfirm={() => { suspend.mutate(suspendReason); setShowSuspendConfirm(false) }}
          onCancel={() => setShowSuspendConfirm(false)}
        />
      )}

      {showResetConfirm && (
        <Modal onClose={() => setShowResetConfirm(false)} title="Confirm hard reset">
          <h3 style={{ color: 'var(--crit)' }}>This cannot be undone</h3>
          <p className="hint muted">
            {resetPreview?.totalRows ?? 0} row(s) will be permanently deleted. Type <strong className="mono">WIPE ALL DATA</strong> exactly to confirm.
          </p>
          <input className="input" value={resetPhrase} onChange={(e) => setResetPhrase(e.target.value)} placeholder="WIPE ALL DATA" />
          {resetMut.isError && <p className="err-text">Confirmation phrase didn't match, or you don't hold the ADMINISTRATOR grant — nothing was wiped.</p>}
          <div className="actions">
            <button className="btn" onClick={() => setShowResetConfirm(false)}>Cancel</button>
            <button
              className="btn pri" style={{ background: 'var(--crit)', borderColor: 'var(--crit)' }}
              disabled={resetPhrase !== 'WIPE ALL DATA' || resetMut.isPending}
              onClick={() => resetMut.mutate()}
            >
              Wipe everything
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

/** VYB-0620 AC2: usage reported outside the JVM that ran the sweep, at last. */
/** VYB-0757: click-to-edit, in place — no separate "edit mode" toggle for the whole screen. */
function EditableField({ label, unit, value, onSave, pending }: {
  label: string
  unit?: string
  value: string | number
  onSave: (raw: string) => void
  pending: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(String(value))

  if (!editing) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, fontSize: 11.5 }}>
        <span className="muted" style={{ flex: 1 }}>{label}</span>
        <span className="mono">{value}{unit ? ` ${unit}` : ''}</span>
        <button className="btn" style={{ padding: '2px 8px', fontSize: 10.5 }}
                onClick={() => { setDraft(String(value)); setEditing(true) }}>
          Edit
        </button>
      </div>
    )
  }
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
      <span className="muted" style={{ flex: 1, fontSize: 11.5 }}>{label} {unit && <span className="hint">({unit})</span>}</span>
      <input className="input" style={{ width: 140 }} value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus />
      <button className="btn" style={{ padding: '2px 8px', fontSize: 10.5 }} onClick={() => setEditing(false)}>Cancel</button>
      <button className="btn pri" style={{ padding: '2px 8px', fontSize: 10.5 }} disabled={pending || draft.trim() === ''}
              onClick={() => { onSave(draft.trim()); setEditing(false) }}>
        Save
      </button>
    </div>
  )
}

function AiUsageCard() {
  const { data } = useQuery({ queryKey: ['ai-usage'], queryFn: api.aiUsage, refetchInterval: 15_000 })
  return (
    <div className="card" style={{ marginTop: 16 }}>
      <div className="eyebrow" style={{ marginBottom: 4 }}>AI usage, this sweep</div>
      {data ? (
        <div className="mono" style={{ fontSize: 13 }}>{data.used} / {data.limit} calls</div>
      ) : (
        <p className="muted" style={{ fontSize: 12 }}>Loading…</p>
      )}
    </div>
  )
}
