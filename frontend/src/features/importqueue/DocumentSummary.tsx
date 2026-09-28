import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { api, parseAnalysisStrings } from '@/shared/api/client'

/**
 * The analysis behind the toggle. Shared with the panel below rather than fetched
 * twice: BatchPanel needs to know an analysis exists before it can offer a button to
 * open one, and TanStack dedupes on the key, so both callers ride one request.
 */
export function useDocumentAnalysis(batchId: string) {
  return useQuery({
    queryKey: ['document-analysis', batchId],
    queryFn: () => api.documentAnalysis(batchId),
  })
}

/**
 * VYB-0667: what the agents understood the document to be about, from the same run that
 * produced the candidates.
 *
 * This is not a separate step with its own run button — extraction produces it, so there
 * is no "not analysed yet" state and nothing here to trigger. It is behind a toggle in
 * the batch header rather than always open: the candidates are the working surface, and
 * a summary that pushes them below the fold is read once and in the way afterwards.
 *
 * Amber throughout, because it is model output and amber is reserved for exactly that.
 * The coverage line is deliberately visible: a description that hides which sections
 * went unread, or how many quoted claims were thrown away for not being in the document,
 * reads as more authoritative than it is.
 */
export function DocumentSummary({ batchId, open, onClose }: { batchId: string; open: boolean; onClose: () => void }) {
  const { data: analysis } = useDocumentAnalysis(batchId)

  if (!analysis || !open) return null

  const themes = parseAnalysisStrings(analysis.themes)
  const unsupported = parseAnalysisStrings(analysis.unsupportedClaims)

  return (
    <section className="doc-sum">
      <div className="doc-sum-h">
        <span className="eyebrow" style={{ color: 'var(--ai-tx)' }}>What this document is about</span>
        <div className="sp" />
        <span className="mono doc-sum-meta">
          {analysis.findingsKept} finding{analysis.findingsKept === 1 ? '' : 's'} ·{' '}
          {analysis.chunksAnalysed}/{analysis.chunksTotal} section{analysis.chunksTotal === 1 ? '' : 's'} read
          {analysis.noiseBlocksDiscarded > 0 && ` · ${analysis.noiseBlocksDiscarded} boilerplate blocks ignored`}
          {analysis.findingsRejected > 0 && ` · ${analysis.findingsRejected} unquotable claims dropped`}
          {' · '}{analysis.model}
        </span>
        <button className="btn" style={{ padding: 4 }} onClick={onClose} title="Hide the document summary" aria-label="Hide the document summary">
          <X />
        </button>
      </div>

      {analysis.partial && (
        <p className="doc-sum-warn">
          The AI budget stopped this run after {analysis.chunksAnalysed} of {analysis.chunksTotal} sections.
          The rest was not read — it was not read and found empty.
        </p>
      )}

      {unsupported.length > 0 && (
        <div className="doc-sum-warn">
          <strong>{unsupported.length} claim{unsupported.length === 1 ? '' : 's'} could not be traced back to the document:</strong>
          <ul style={{ margin: '4px 0 0 18px', padding: 0 }}>
            {unsupported.map((c) => <li key={c}>{c}</li>)}
          </ul>
        </div>
      )}

      <div className="doc-sum-body">
        {analysis.description.split(/\n{2,}/).map((para, i) => <p key={i}>{para}</p>)}
      </div>

      {themes.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 }}>
          {themes.map((t) => <span key={t} className="badge sev-ai">{t}</span>)}
        </div>
      )}
    </section>
  )
}
