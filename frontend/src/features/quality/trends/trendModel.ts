import type { TrendReadinessDay, VyoogRelease } from '@/shared/api/client'

/**
 * VYB-0956 (D43): the rules of the trend charts that need no server: what a missing value is called, where a line breaks, how a
 * scale is rounded, which release is shown first. A week or a day with nothing recorded is a gap, never a zero.
 */
export const PERIODS = [30, 90, 180] as const
export type Period = (typeof PERIODS)[number]

export const NO_RESULTS = 'no results'
export const NO_DATA = 'no data'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** "2026-10-12" as "12 Oct", read as a plain date so a time zone cannot move it a day. */
export function dayLabel(iso: string): string {
  const [, m, d] = iso.split('-').map(Number)
  return `${d} ${MONTHS[(m ?? 1) - 1]}`
}

export function percentText(rate: number | null | undefined, missing = NO_RESULTS): string {
  return rate == null ? missing : `${Math.round(rate * 100)}%`
}

/** The share of a release's scope: null when the scope is empty (a share of nothing is not 0%). */
export function share(part: number, committed: number): number | null {
  return committed <= 0 ? null : part / committed
}

/**
 * Splits values into runs of present values: a null ends a run, so a line is never drawn across a gap. {@code breakBefore[i]} also
 * ends the run before point i (the day before it was not recorded). Each point keeps its index.
 */
export function runs(values: (number | null | undefined)[], breakBefore?: boolean[]): { index: number; value: number }[][] {
  const out: { index: number; value: number }[][] = []
  let current: { index: number; value: number }[] = []
  values.forEach((v, index) => {
    if (breakBefore?.[index] && current.length) {
      out.push(current)
      current = []
    }
    if (v == null) {
      if (current.length) out.push(current)
      current = []
    } else {
      current.push({ index, value: v })
    }
  })
  if (current.length) out.push(current)
  return out
}

function countStep(peak: number): number {
  for (let exp = 1; ; exp *= 10) {
    for (const m of [1, 2, 5]) if (Math.ceil(peak / (m * exp)) <= 4) return m * exp
  }
}

/**
 * A count axis in whole numbers: a step of 1, 2 or 5 times a power of ten, small enough that there are at most four of them, and
 * a top that is a whole number of steps (at least 1). Counts are never shown as 1.25.
 */
export function countAxis(max: number): { top: number; ticks: number[] } {
  const peak = Math.max(1, Math.ceil(max))
  const step = countStep(peak)
  const top = Math.ceil(peak / step) * step
  const out: number[] = []
  for (let v = 0; v <= top; v += step) out.push(v)
  return { top, ticks: out }
}

/** The axis of a rate or share: zero to one in quarters. */
export const SHARE_TICKS = [0, 0.25, 0.5, 0.75, 1]

const DAY_MS = 86_400_000

function dayIndex(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return Math.round(Date.UTC(y!, (m ?? 1) - 1, d) / DAY_MS)
}

/** Where each recorded day sits on a time axis that starts at the first day shown: days not recorded leave space, not a point. */
export function dayOffsets(days: string[], firstDay: string): number[] {
  const start = dayIndex(firstDay)
  return days.map((d) => dayIndex(d) - start)
}

/** True where a recorded day does not follow the one before it by a single day, so the line must not join them. */
export function gapBefore(offsets: number[]): boolean[] {
  return offsets.map((o, i) => i > 0 && o - offsets[i - 1]! > 1)
}

/** The days of a readiness trend as two shares of scope, ready to draw; an empty scope is a gap in both. */
export function readinessShares(days: TrendReadinessDay[]): { approved: (number | null)[]; verified: (number | null)[] } {
  return { approved: days.map((d) => share(d.approved, d.committed)), verified: days.map((d) => share(d.verified, d.committed)) }
}

/** The release shown before anyone chooses: the first one still being worked, else none. */
export function defaultRelease(releases: Pick<VyoogRelease, 'id' | 'state'>[] | undefined): string {
  return releases?.find((r) => r.state !== 'RELEASED')?.id ?? ''
}

/** True when there is nothing to plot: no week with a defect raised or open. */
export function noDefects(weeks: { opened: number; open: number }[]): boolean {
  return weeks.every((w) => w.opened === 0 && w.open === 0)
}

export function noResults(weeks: { rate?: number | null }[]): boolean {
  return weeks.every((w) => w.rate == null)
}
