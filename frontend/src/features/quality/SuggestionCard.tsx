import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type TestCaseSuggestion, type TestCaseSuggestionCategory } from '@/shared/api/client'

/** VYB-0824: Individual first, Dependency second — the order every panel using this renders them in. */
export function groupByCategory<T extends { category: TestCaseSuggestionCategory }>(suggestions: T[]) {
  return {
    individual: suggestions.filter((s) => s.category === 'INDIVIDUAL'),
    dependency: suggestions.filter((s) => s.category === 'DEPENDENCY'),
  }
}

/**
 * VYB-0828: the AI prompt asks for a bulleted list of steps/checks ("- ..."/"• ..." per
 * line) rather than a paragraph. Returns null when the text has no bullet-style lines
 * (older test cases, or a manual entry someone wrote as prose) so the caller falls back
 * to a plain paragraph rather than showing one bare, unmarked line.
 */
export function parseBullets(text: string): string[] | null {
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean)
  const bulletLines = lines.filter((l) => l.startsWith('- ') || l.startsWith('• '))
  if (bulletLines.length === 0) return null
  return bulletLines.map((l) => l.replace(/^[-•]\s*/, ''))
}

/** Read-only rendering of a test case's description — a real `<ul>` when it's bulleted, plain text otherwise. */
export function DescriptionView({ text }: { text: string }) {
  const bullets = parseBullets(text)
  if (!bullets) return <p style={{ margin: 0 }}>{text}</p>
  return (
    <ul style={{ margin: 0, paddingLeft: 16 }}>
      {bullets.map((b, i) => <li key={i}>{b}</li>)}
    </ul>
  )
}

/** The fields the person changed; sent only if different, so an untouched suggestion is accepted exactly as proposed. */
export function editsOf(suggestion: TestCaseSuggestion, title: string, description: string): Record<string, string> | undefined {
  const edits: Record<string, string> = {}
  if (title.trim() !== suggestion.title.trim()) edits.title = title.trim()
  if (description.trim() !== (suggestion.description ?? '').trim() && description.trim()) edits.description = description.trim()
  return Object.keys(edits).length ? edits : undefined
}

/**
 * VYB-0824/0826: shared by the bulk review panel (`BulkTestCaseReviewPanel`) — one card, one accept/dismiss
 * behaviour. An AI-generated title/description is editable before it is ever saved.
 * VYB-0938: each suggestion is a recorded proposal. Adding sends a decision (ACCEPT, with the edited title and description
 * if they differ), which drafts the test case server-side; Dismiss sends a REJECT, so a discarded suggestion is on record too.
 * VYB-0828: a bulleted live preview renders under the editable textarea, so the bullet
 * structure the AI wrote is visible before accepting, not only after.
 */
export function SuggestionCard({
  suggestion, onDismiss, onAdded,
}: { suggestion: TestCaseSuggestion; onDismiss: () => void; onAdded: () => void }) {
  const [title, setTitle] = useState(suggestion.title)
  const [description, setDescription] = useState(suggestion.description)
  const accept = useMutation({
    mutationFn: () => api.decideProposal(suggestion.proposalId, { decision: 'ACCEPT', edits: editsOf(suggestion, title, description) }),
    onSuccess: onAdded,
  })
  const dismiss = useMutation({
    mutationFn: () => api.decideProposal(suggestion.proposalId, { decision: 'REJECT' }),
    onSuccess: onDismiss,
  })

  return (
    <div className="card" style={{ background: 'var(--ai-dim)', border: '1px solid var(--ai-bd)', marginBottom: 8 }}>
      <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} style={{ marginBottom: 6, fontWeight: 600 }} />
      <textarea className="textarea" rows={4} value={description} onChange={(e) => setDescription(e.target.value)} style={{ marginBottom: 6 }} />
      {description.trim() && (
        <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
          <DescriptionView text={description} />
        </div>
      )}
      <p className="hint" style={{ color: 'var(--ai-tx)', fontSize: 11, marginBottom: 8 }}>{suggestion.rationale}</p>
      {(accept.isError || dismiss.isError) && <p className="err-text" role="alert">Could not record that decision.</p>}
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="btn pri" style={{ flex: 1 }} disabled={!title.trim() || accept.isPending || dismiss.isPending} onClick={() => accept.mutate()}>
          Add
        </button>
        <button className="btn" style={{ flex: 1 }} disabled={accept.isPending || dismiss.isPending} onClick={() => dismiss.mutate()}>Dismiss</button>
      </div>
    </div>
  )
}
