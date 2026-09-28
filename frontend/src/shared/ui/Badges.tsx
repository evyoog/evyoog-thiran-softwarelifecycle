import type { Requirement, RequirementPriority, RequirementStatus } from '@/shared/api/client'

const STATUS_LABEL: Record<RequirementStatus, string> = {
  DRAFT: 'Draft', IN_REVIEW: 'In review', REVIEWED: 'Reviewed', NEEDS_REVISION: 'Needs revision',
  APPROVED: 'Approved', REJECTED: 'Rejected',
}

export function StatusBadge({ status }: { status: RequirementStatus }) {
  return <span className={`badge st-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
}

export function PriorityBadge({ priority }: { priority: RequirementPriority }) {
  return <span className={`badge pr-${priority.toLowerCase()}`}>{priority}</span>
}

export function SeverityBadge({ severity }: { severity: string }) {
  return <span className={`badge sev-${severity.toLowerCase()}`}>{severity === 'ai' ? 'AI' : severity}</span>
}

/**
 * VYB-0181: upstream/design/code/test coverage, letter-coded so colour is never the
 * only signal (VYB-0770).
 */
export function CoveragePips({ req }: { req: Pick<Requirement, 'hasUpstream' | 'hasDesign' | 'hasCode' | 'hasTest'> }) {
  const pips: [string, boolean, string][] = [
    ['U', req.hasUpstream, 'Upstream link'],
    ['D', req.hasDesign, 'Design link'],
    ['C', req.hasCode, 'Code link'],
    ['T', req.hasTest, 'Passing test at the current revision'],
  ]
  return (
    <span className="pips">
      {pips.map(([letter, on, title]) => (
        <span key={letter} className={`pip${on ? ' on' : ''}`} title={`${title}: ${on ? 'yes' : 'missing'}`}>
          {letter}
        </span>
      ))}
    </span>
  )
}
