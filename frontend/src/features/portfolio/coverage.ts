/**
 * The layout prototype's three-tier coverage banding (`covCol`), shared by the
 * portfolio cards and the product drill-down so the same percentage can never be
 * green in one view and amber in the other. Amber starts exactly where the platform
 * strip's "apps below 75%" figure starts.
 */
export function covColor(pct: number): string {
  return pct >= 88 ? 'var(--ok)' : pct >= 75 ? 'var(--high)' : 'var(--crit)'
}

/** Verified share of an app's requirements, as a whole percentage. */
export function verifiedPctOf(a: { reqCount: number; verifiedCount: number }): number {
  return a.reqCount > 0 ? Math.round((a.verifiedCount / a.reqCount) * 100) : 0
}