import { useState, type ReactNode } from 'react'
import { SHARE_TICKS, countAxis, gapBefore, runs } from './trendModel'

/**
 * VYB-0956 (D43): two small SVG charts, a bar chart and a line chart, drawn with no library. Thin marks, a quiet grid, a legend
 * whenever there are two series, a hover tooltip, and a gap where nothing was recorded (a line breaks, a bar is absent). Colour is
 * never the only carrier: every value is in the tooltip and in the table view the page offers.
 */

export interface Series { name: string; color: string; values: (number | null)[] }

const W = 720
const H = 220
const M = { left: 44, right: 64, top: 12, bottom: 28 }
const PLOT_W = W - M.left - M.right
const PLOT_H = H - M.top - M.bottom

const GRID = 'var(--line-3)'
const INK_MUTED = 'var(--tx-3)'
const INK = 'var(--tx-2)'

export const SERIES_1 = 'var(--series-1)'
export const SERIES_2 = 'var(--series-2)'

export function Legend({ series }: { series: Series[] }) {
  if (series.length < 2) return null
  return (
    <ul aria-label="Legend" style={{ display: 'flex', gap: 16, listStyle: 'none', margin: '0 0 6px', padding: 0, fontSize: 12, color: INK }}>
      {series.map((s) => (
        <li key={s.name} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span aria-hidden="true" style={{ width: 10, height: 10, borderRadius: 2, background: s.color, display: 'inline-block' }} />
          {s.name}
        </li>
      ))}
    </ul>
  )
}

function Frame({ label, children, tip }: { label: string; children: ReactNode; tip: { x: number; lines: string[] } | null }) {
  return (
    <div style={{ position: 'relative', maxWidth: W }}>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={label} style={{ width: '100%', height: 'auto', display: 'block' }}>{children}</svg>
      {tip && (
        <div role="status" style={{
          position: 'absolute', top: 4, left: `${Math.min(Math.max((tip.x / W) * 100, 14), 78)}%`, transform: 'translateX(-50%)', pointerEvents: 'none',
          background: 'var(--panel-3)', border: '1px solid var(--line-2)', borderRadius: 6, padding: '6px 8px', fontSize: 12, color: 'var(--tx)', whiteSpace: 'nowrap',
        }}>
          {tip.lines.map((l, i) => <div key={i} style={{ fontWeight: i === 0 ? 600 : 400 }}>{l}</div>)}
        </div>
      )}
    </div>
  )
}

function Axis({ ticks, top, format }: { ticks: number[]; top: number; format: (v: number) => string }) {
  return (
    <g>
      {ticks.map((t) => {
        const y = M.top + PLOT_H - (t / top) * PLOT_H
        return (
          <g key={t}>
            <line x1={M.left} x2={W - M.right} y1={y} y2={y} stroke={GRID} strokeWidth={1} />
            <text x={M.left - 8} y={y + 4} textAnchor="end" fontSize={11} fill={INK_MUTED}>{format(t)}</text>
          </g>
        )
      })}
    </g>
  )
}

/** At most eight x labels, whatever the number of points. */
function XLabels({ items }: { items: { x: number; text: string }[] }) {
  const every = Math.max(1, Math.ceil(items.length / 8))
  return (
    <g>
      {items.map((it, i) => i % every === 0 && (
        <text key={i} x={it.x} y={H - 8} textAnchor="middle" fontSize={11} fill={INK_MUTED}>{it.text}</text>
      ))}
    </g>
  )
}

/** A bar whose top corners are rounded and whose foot sits square on the baseline. */
function barPath(x: number, y: number, w: number, h: number, r = 4): string {
  const rr = Math.min(r, w / 2, h)
  return `M${x},${y + h} L${x},${y + rr} Q${x},${y} ${x + rr},${y} L${x + w - rr},${y} Q${x + w},${y} ${x + w},${y + rr} L${x + w},${y + h} Z`
}

export function BarChart({ label, labels, series, tooltip }: {
  label: string
  labels: string[]
  series: Series[]
  tooltip: (index: number) => string[]
}) {
  const [hover, setHover] = useState<number | null>(null)
  const max = Math.max(0, ...series.flatMap((s) => s.values.map((v) => v ?? 0)))
  const { top, ticks } = countAxis(max)
  const slot = PLOT_W / Math.max(1, labels.length)
  const group = Math.min(slot * 0.7, 18 * series.length + 2 * (series.length - 1))
  const bar = (group - 2 * (series.length - 1)) / series.length
  const centre = (i: number) => M.left + slot * i + slot / 2
  return (
    <Frame label={label} tip={hover == null ? null : { x: centre(hover), lines: tooltip(hover) }}>
      <Axis ticks={ticks} top={top} format={(v) => String(v)} />
      {labels.map((_, i) => series.map((s, k) => {
        const v = s.values[i]
        if (v == null || v <= 0) return null
        const h = (v / top) * PLOT_H
        return <path key={`${i}-${k}`} d={barPath(centre(i) - group / 2 + k * (bar + 2), M.top + PLOT_H - h, bar, h)} fill={s.color} />
      }))}
      <line x1={M.left} x2={W - M.right} y1={M.top + PLOT_H} y2={M.top + PLOT_H} stroke={INK_MUTED} strokeWidth={1} />
      <XLabels items={labels.map((text, i) => ({ x: centre(i), text }))} />
      {hover != null && <line x1={centre(hover)} x2={centre(hover)} y1={M.top} y2={M.top + PLOT_H} stroke={INK_MUTED} strokeDasharray="3 3" />}
      {labels.map((_, i) => (
        <rect key={i} x={M.left + slot * i} y={M.top} width={slot} height={PLOT_H} fill="transparent"
          onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
      ))}
    </Frame>
  )
}

/**
 * A line chart of shares (0 to 100%). {@code positions} are where each point sits along the x axis (0 to {@code span}), so a day not
 * recorded leaves space; a null value, or a jump of more than one position, ends the line and starts a new one after it.
 */
export function LineChart({ label, positions, span, xLabels, series, tooltip }: {
  label: string
  positions: number[]
  span: number
  xLabels: { at: number; text: string }[]
  series: Series[]
  tooltip: (index: number) => string[]
}) {
  const [hover, setHover] = useState<number | null>(null)
  const apart = gapBefore(positions)
  const x = (p: number) => M.left + (span <= 0 ? PLOT_W / 2 : (p / span) * PLOT_W)
  const y = (v: number) => M.top + PLOT_H - v * PLOT_H
  const nearest = (clientX: number, rect: DOMRect): number | null => {
    if (positions.length === 0) return null
    const px = ((clientX - rect.left) / rect.width) * W
    let best = 0
    positions.forEach((p, i) => { if (Math.abs(x(p) - px) < Math.abs(x(positions[best]!) - px)) best = i })
    return best
  }
  return (
    <Frame label={label} tip={hover == null ? null : { x: x(positions[hover]!), lines: tooltip(hover) }}>
      <Axis ticks={SHARE_TICKS} top={1} format={(v) => `${Math.round(v * 100)}%`} />
      <XLabels items={xLabels.map((l) => ({ x: x(l.at), text: l.text }))} />
      {hover != null && <line x1={x(positions[hover]!)} x2={x(positions[hover]!)} y1={M.top} y2={M.top + PLOT_H} stroke={INK_MUTED} strokeDasharray="3 3" />}
      {series.map((s) => (
        <g key={s.name}>
          {runs(s.values, apart).map((run, r) => (
            <g key={r}>
              {run.length > 1 && <polyline fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round"
                points={run.map((pt) => `${x(positions[pt.index]!)},${y(pt.value)}`).join(' ')} />}
              {run.map((pt) => <circle key={pt.index} cx={x(positions[pt.index]!)} cy={y(pt.value)} r={4} fill={s.color} stroke="var(--panel)" strokeWidth={2} />)}
            </g>
          ))}
          {(() => {
            const last = runs(s.values).flat().at(-1)
            return last && <text x={x(positions[last.index]!) + 9} y={y(last.value) + 4} fontSize={11} fill="var(--tx)">{`${Math.round(last.value * 100)}%`}</text>
          })()}
        </g>
      ))}
      <rect x={M.left} y={M.top} width={PLOT_W} height={PLOT_H} fill="transparent"
        onMouseMove={(e) => setHover(nearest(e.clientX, e.currentTarget.ownerSVGElement!.getBoundingClientRect()))} onMouseLeave={() => setHover(null)} />
    </Frame>
  )
}
