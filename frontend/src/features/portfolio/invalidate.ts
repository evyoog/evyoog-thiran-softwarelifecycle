import type { QueryClient } from '@tanstack/react-query'

/**
 * Every cache a capability change touches, in one place.
 *
 * <p>Capabilities are read under `['capabilities', applicationId]` by the requirement
 * author, the import queue, the scope tree, Delivery, the capability picker, the
 * hierarchy tab and Administration — and separately under `['capability-summary', …]`
 * and `['product-dashboard']`, which carry counts rather than the list.
 *
 * <p>Each mutation used to invalidate whichever of those the screen it lived on happened
 * to read, so creating a capability on the Hierarchy tab left the app detail cards and
 * the portfolio counts showing the old set until a reload. Anything that creates,
 * renames or archives a capability calls this instead of picking keys by hand.
 */
export function invalidateCapabilities(qc: QueryClient, applicationId: string) {
  void qc.invalidateQueries({ queryKey: ['capabilities', applicationId] })
  void qc.invalidateQueries({ queryKey: ['capability-summary', applicationId] })
  // Capability counts, requirement rollups and gap totals all hang off the dashboard.
  void qc.invalidateQueries({ queryKey: ['product-dashboard'] })
}
