import type { ImportRowProblem, ImportValidationReport } from '@/shared/api/client'

/**
 * VYB-0957 (D44): the words and limits of the pre-upload file check. A file with a whole-file problem cannot be uploaded; a row's
 * own problem does not stop it (that row arrives unticked with its reason, as it always has). Everything the screen says about a
 * report is decided here, so it can be tested without rendering.
 */
export const FORMAT_LABEL: Record<string, string> = {
  xlsx: 'Excel workbook', ods: 'OpenDocument spreadsheet', xls: 'Excel 97-2003 workbook', csv: 'CSV text', docx: 'Word document', reqif: 'ReqIF',
}

export const ACCEPTED_FILES = '.xlsx,.ods,.xls,.csv,.docx,.reqif,.reqifz,.xml'

/** How many rows with problems the screen lists before saying how many more there are. */
export const SHOWN_ROWS = 25

export function formatName(format: string | null | undefined): string {
  return format ? (FORMAT_LABEL[format] ?? format) : 'unrecognised file'
}

/** True when the file can be uploaded: it was checked, and nothing is wrong with the file as a whole. */
export function canUpload(report: ImportValidationReport | undefined): boolean {
  return !!report && report.canUpload
}

/** One line on what was found. */
export function summaryLine(r: ImportValidationReport): string {
  if (!r.canUpload) return `${formatName(r.format)}: this file cannot be imported.`
  const rows = `${r.rows} ${r.rows === 1 ? 'requirement' : 'requirements'}`
  if (r.withProblems === 0) return `${formatName(r.format)}: ${rows}, all ready to import.`
  return `${formatName(r.format)}: ${rows}, ${r.ready} ready and ${r.withProblems} with ${r.withProblems === 1 ? 'a problem' : 'problems'}.`
}

/** The label of the Upload button: it says what will happen to the rows that have problems. */
export function uploadLabel(r: ImportValidationReport | undefined, checking: boolean): string {
  if (checking) return 'Checking…'
  if (r && r.canUpload && r.withProblems > 0) return `Upload (${r.withProblems} need${r.withProblems === 1 ? 's' : ''} attention)`
  return 'Upload'
}

export function shownRows(r: ImportValidationReport): ImportRowProblem[] {
  return r.rowProblems.slice(0, SHOWN_ROWS)
}

/** How many rows with problems are not listed: the ones past what the screen shows, and past what the server returned. */
export function hiddenRows(r: ImportValidationReport): number {
  return Math.max(0, r.withProblems - Math.min(SHOWN_ROWS, r.rowProblems.length))
}

/** The row as a person finds it: its own reference when it has one, and the sheet row. */
export function rowLabel(p: ImportRowProblem, format: string | null | undefined): string {
  const where = format === 'reqif' ? '' : `row ${p.row}`
  return [p.ref, where].filter(Boolean).join(' · ') || `row ${p.row}`
}
