import type { ImportValidationReport } from '@/shared/api/client'
import { hiddenRows, rowLabel, shownRows, summaryLine } from './fileCheck'

/**
 * VYB-0957 (D44): what checking a file found, shown before anything is uploaded. A whole-file problem is an error and the upload is
 * off; a row's problems are listed by row so the file can be fixed in one pass, and those rows arrive unticked if it is uploaded as
 * it is. Words and glyphs, never colour alone.
 */
export function FileCheck({ report, checking, failed }: { report?: ImportValidationReport; checking: boolean; failed?: string }) {
  if (checking) return <p role="status" className="hint muted" style={{ marginTop: 8 }}>Checking this file…</p>
  if (failed) return <p role="alert" className="err-text" style={{ marginTop: 8 }}>This file could not be checked: {failed}</p>
  if (!report) return null
  return (
    <div style={{ marginTop: 10 }} aria-label="File check">
      {report.fileProblems.map((p) => (
        <p key={p.rule} role="alert" className="err-text" style={{ margin: '0 0 6px' }}>✕ {p.message}</p>
      ))}
      <p role="status" className="hint" style={{ margin: '0 0 6px' }}>
        {report.canUpload ? (report.withProblems === 0 ? '✓ ' : '! ') : ''}{summaryLine(report)}
        {report.canUpload && report.withProblems > 0 && ' Rows with problems arrive unticked, with the reason beside them; fix the file and upload again if you would rather not.'}
      </p>
      {report.rowProblems.length > 0 && (
        <table className="tbl" aria-label="Rows with problems" style={{ maxWidth: 900 }}>
          <thead><tr><th>Row</th><th>Requirement</th><th>Problems</th></tr></thead>
          <tbody>
            {shownRows(report).map((p) => (
              <tr key={`${p.row}-${p.ref ?? ''}`}>
                <td className="mono" style={{ whiteSpace: 'nowrap' }}>{rowLabel(p, report.format)}</td>
                <td>{p.title || '(no title)'}</td>
                <td>{p.problems.map((x, i) => <div key={i}>{x}</div>)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {hiddenRows(report) > 0 && <p className="hint muted" style={{ marginTop: 6 }}>…and {hiddenRows(report)} more rows with problems.</p>}
    </div>
  )
}
