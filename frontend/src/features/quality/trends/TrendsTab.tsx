import { useQuery } from '@tanstack/react-query'
import { useMemo, useState, type ReactNode } from 'react'
import { Download } from 'lucide-react'
import { ApiError, api, type TrendParams, type Trends } from '@/shared/api/client'
import { Empty } from '@/shared/ui/Page'
import { BarChart, LineChart, Legend, SERIES_1, SERIES_2, type Series } from './TrendCharts'
import { NO_DATA, NO_RESULTS, PERIODS, dayLabel, dayOffsets, defaultRelease, noDefects, noResults, percentText, readinessShares, share } from './trendModel'

/**
 * VYB-0956 (F24, D43): trends over time. Defects raised, closed and the backlog each week; the share of test results that passed
 * each week; and a release's readiness day by day. Filters by product, application and period, and a release for readiness. The same
 * figures download as an Excel workbook or a PDF. A week with no test results or a day with nothing recorded is a gap and says so,
 * never a zero. Counts and rates only: nothing here is per person.
 */
export function TrendsTab() {
  const [productId, setProductId] = useState('')
  const [applicationId, setApplicationId] = useState('')
  const [days, setDays] = useState<number>(90)
  const [chosenRelease, setChosenRelease] = useState<string | undefined>(undefined)
  const [table, setTable] = useState(false)
  const [exporting, setExporting] = useState<'' | 'xlsx' | 'pdf'>('')
  const [exportError, setExportError] = useState('')

  const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
  const { data: applications } = useQuery({ queryKey: ['applications', productId], queryFn: () => api.applications(productId), enabled: !!productId })
  const { data: releases } = useQuery({ queryKey: ['releases'], queryFn: api.releases })
  const releaseId = chosenRelease ?? defaultRelease(releases)

  const params: TrendParams = { productId: productId || undefined, applicationId: applicationId || undefined, days, releaseId: releaseId || undefined }
  const { data, isLoading, isError, refetch } = useQuery({ queryKey: ['trends', params], queryFn: () => api.trends(params) })

  const download = async (format: 'xlsx' | 'pdf') => {
    setExporting(format)
    setExportError('')
    try {
      const { blob, filename } = await api.downloadTrends(format, params)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      setExportError(e instanceof ApiError ? (e.detail ?? e.title) : 'Could not make the file.')
    } finally {
      setExporting('')
    }
  }

  return (
    <>
      <p className="hint muted" style={{ marginBottom: 12 }}>
        Weeks begin on Monday (UTC). Counts and rates only. A week with no test results has no rate, and a day on which readiness was not
        recorded is a gap: neither is shown as zero. Readiness is recorded once a day, from the day it was first recorded.
      </p>

      <div className="row toolbar" style={{ marginBottom: 12, alignItems: 'flex-end', flexWrap: 'wrap', gap: 12 }}>
        <div className="field">
          <label className="label" htmlFor="tr-product">Product</label>
          <select id="tr-product" className="select" value={productId} onChange={(e) => { setProductId(e.target.value); setApplicationId('') }}>
            <option value="">All products</option>
            {products?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="tr-app">Application</label>
          <select id="tr-app" className="select" value={applicationId} disabled={!productId} onChange={(e) => setApplicationId(e.target.value)}>
            <option value="">All applications</option>
            {applications?.filter((a) => !a.archived).map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="tr-period">Period</label>
          <select id="tr-period" className="select" value={days} onChange={(e) => setDays(Number(e.target.value))}>
            {PERIODS.map((d) => <option key={d} value={d}>Last {d} days</option>)}
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="tr-release">Release (for readiness)</label>
          <select id="tr-release" className="select" value={releaseId} onChange={(e) => setChosenRelease(e.target.value)}>
            <option value="">None</option>
            {releases?.map((r) => <option key={r.id} value={r.id}>{r.name} ({r.state.toLowerCase()})</option>)}
          </select>
        </div>
        <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 13, paddingBottom: 8 }}>
          <input type="checkbox" checked={table} onChange={(e) => setTable(e.target.checked)} /> View as tables
        </label>
        <div className="sp" />
        <button className="btn" disabled={!!exporting} onClick={() => void download('xlsx')}><Download /> {exporting === 'xlsx' ? 'Making…' : 'Excel'}</button>
        <button className="btn" disabled={!!exporting} onClick={() => void download('pdf')}><Download /> {exporting === 'pdf' ? 'Making…' : 'PDF'}</button>
      </div>
      {exportError && <p className="err-text" role="alert">{exportError}</p>}
      <p className="hint muted" style={{ marginBottom: 12 }}>The filters apply to defects and test results. A release has its own scope, so they do not apply to its readiness.</p>

      {isLoading && <p className="eyebrow">Loading…</p>}
      {isError && <p className="err-text" role="alert">The trends could not be loaded. <button className="btn" onClick={() => void refetch()}>Retry</button></p>}
      {data && <TrendSections data={data} table={table} />}
    </>
  )
}

function Section({ title, note, children }: { title: string; note: string; children: ReactNode }) {
  return (
    <section style={{ marginBottom: 28 }}>
      <h2 className="text-base font-semibold" style={{ margin: '0 0 2px' }}>{title}</h2>
      <p className="hint muted" style={{ margin: '0 0 8px' }}>{note}</p>
      {children}
    </section>
  )
}

function Table({ head, rows, label }: { head: string[]; rows: string[][]; label: string }) {
  return (
    <table className="tbl" aria-label={label} style={{ maxWidth: 720 }}>
      <thead><tr>{head.map((h) => <th key={h}>{h}</th>)}</tr></thead>
      <tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
    </table>
  )
}

function TrendSections({ data, table }: { data: Trends; table: boolean }) {
  const weekText = (iso: string) => `Week of ${dayLabel(iso)}`
  const weekLabels = data.defects.map((d) => dayLabel(d.weekStart))

  const backlog: Series = { name: 'Open at end of week', color: SERIES_1, values: data.defects.map((d) => d.open) }
  const raised: Series = { name: 'Raised', color: SERIES_1, values: data.defects.map((d) => d.opened) }
  const closed: Series = { name: 'Closed', color: SERIES_2, values: data.defects.map((d) => d.closed) }
  const passed: Series = { name: 'Pass rate', color: SERIES_1, values: data.passRate.map((p) => p.rate ?? null) }
  const nowOpen = data.defects.at(-1)?.open ?? 0

  return (
    <>
      <Section title="Defect backlog" note="Defects not closed (open or fixed) at the end of each week. A reopened defect is back in it.">
        {noDefects(data.defects) ? <Empty title="No defects" desc="None raised or open in this period for this scope." /> : table ? (
          <Table label="Defect backlog" head={['Week starting', 'Open at end of week']} rows={data.defects.map((d) => [d.weekStart, String(d.open)])} />
        ) : (
          <>
            <BarChart label={`Open defects at the end of each week, ${nowOpen} now`} labels={weekLabels} series={[backlog]}
              tooltip={(i) => [weekText(data.defects[i]!.weekStart), `Open at end of week: ${data.defects[i]!.open}`]} />
            <p className="hint muted" style={{ marginTop: 4 }}>{nowOpen} open at the end of this week.</p>
          </>
        )}
      </Section>

      <Section title="Defects raised and closed" note="How many were raised and how many were closed in each week.">
        {noDefects(data.defects) ? <Empty title="No defects" desc="None raised or open in this period for this scope." /> : table ? (
          <Table label="Defects raised and closed" head={['Week starting', 'Raised', 'Closed']} rows={data.defects.map((d) => [d.weekStart, String(d.opened), String(d.closed)])} />
        ) : (
          <>
            <Legend series={[raised, closed]} />
            <BarChart label="Defects raised and closed each week" labels={weekLabels} series={[raised, closed]}
              tooltip={(i) => [weekText(data.defects[i]!.weekStart), `Raised: ${data.defects[i]!.opened}`, `Closed: ${data.defects[i]!.closed}`]} />
          </>
        )}
      </Section>

      <Section title="Test pass rate" note="Of the results recorded each week (CI and manual together), the share that passed. A week with no results has no rate.">
        {noResults(data.passRate) ? <Empty title={NO_RESULTS} desc="No test results were recorded in this period for this scope." /> : table ? (
          <Table label="Test pass rate" head={['Week starting', 'Passed', 'Failed', 'Pass rate']}
            rows={data.passRate.map((p) => [p.weekStart, String(p.passed), String(p.failed), percentText(p.rate)])} />
        ) : (
          <LineChart label="Share of test results that passed, each week" positions={data.passRate.map((_, i) => i)} span={Math.max(1, data.passRate.length - 1)}
            xLabels={data.passRate.map((p, i) => ({ at: i, text: dayLabel(p.weekStart) }))} series={[passed]}
            tooltip={(i) => {
              const p = data.passRate[i]!
              return [weekText(p.weekStart), p.rate == null ? NO_RESULTS : `Pass rate: ${percentText(p.rate)} (${p.passed} passed, ${p.failed} failed)`]
            }} />
        )}
      </Section>

      <Section title="Release readiness" note="The share of the release's committed scope that is approved and that is verified, on each day it was recorded. A day not recorded is a gap.">
        <Readiness data={data} table={table} />
      </Section>
    </>
  )
}

function Readiness({ data, table }: { data: Trends; table: boolean }) {
  const r = data.readiness
  const firstDay = useMemo(() => {
    const d = new Date(data.generatedAt)
    d.setUTCDate(d.getUTCDate() - data.days)
    return d.toISOString().slice(0, 10)
  }, [data.generatedAt, data.days])
  if (!r) return <Empty title="No release chosen" desc="Choose a release above to see how its readiness has moved." />

  const shares = readinessShares(r.history)
  const series: Series[] = [
    { name: 'Approved', color: SERIES_1, values: shares.approved },
    { name: 'Verified', color: SERIES_2, values: shares.verified },
  ]
  const positions = dayOffsets(r.history.map((h) => h.day), firstDay)
  const quarter = Math.round(data.days / 4)
  const xLabels = [0, quarter, quarter * 2, quarter * 3, data.days].map((at) => {
    const d = new Date(`${firstDay}T00:00:00Z`)
    d.setUTCDate(d.getUTCDate() + at)
    return { at, text: dayLabel(d.toISOString().slice(0, 10)) }
  })
  const now = r.current
  const live = `Now: ${now.approved} of ${now.committed} approved, ${now.verified} verified, ${now.blocked} blocked, ${now.criticalGaps} open critical ${now.criticalGaps === 1 ? 'gap' : 'gaps'} (live, not a recorded day).`

  return (
    <>
      <p className="hint" style={{ marginBottom: 6 }}><strong>{r.releaseName}</strong> ({r.state.toLowerCase()}). {live}</p>
      {r.history.length === 0 ? (
        <Empty title={NO_DATA} desc="No day has been recorded for this release in this period yet. Readiness is recorded each night." />
      ) : table ? (
        <Table label="Release readiness" head={['Day', 'Committed', 'Approved', 'Verified', 'Blocked', 'Open critical gaps', 'Verified share']}
          rows={r.history.map((h) => [h.day, String(h.committed), String(h.approved), String(h.verified), String(h.blocked), String(h.criticalGaps),
            percentText(share(h.verified, h.committed), 'no scope')])} />
      ) : (
        <>
          <Legend series={series} />
          <LineChart label={`Approved and verified share of ${r.releaseName}'s scope, by recorded day`} positions={positions} span={data.days} xLabels={xLabels} series={series}
            tooltip={(i) => {
              const h = r.history[i]!
              return [h.day, `Committed: ${h.committed}`, `Approved: ${h.approved}`, `Verified: ${h.verified}`, `Blocked: ${h.blocked}`, `Open critical gaps: ${h.criticalGaps}`]
            }} />
        </>
      )}
    </>
  )
}
