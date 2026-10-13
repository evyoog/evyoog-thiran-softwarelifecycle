import { describe, it, expect } from 'vitest'
import type { ImportValidationReport } from '@/shared/api/client'
import { ACCEPTED_FILES, canUpload, formatName, hiddenRows, rowLabel, shownRows, summaryLine, uploadLabel } from './fileCheck'

const report = (o: Partial<ImportValidationReport> = {}): ImportValidationReport =>
  ({ format: 'docx', rows: 3, ready: 3, withProblems: 0, canUpload: true, fileProblems: [], rowProblems: [], ...o })

describe('VYB-0957 — the file check before upload', () => {
  it('VYB0957_AC22_aWholeFileProblemStopsTheUploadAndARowProblemDoesNot', () => {
    expect(canUpload(undefined)).toBe(false)
    expect(canUpload(report())).toBe(true)
    expect(canUpload(report({ withProblems: 2, ready: 1 }))).toBe(true)
    expect(canUpload(report({ canUpload: false, rows: 0, ready: 0, fileProblems: [{ rule: 'no-table', message: 'x' }] }))).toBe(false)
  })

  it('VYB0957_AC23_theSummaryNamesTheFormatAndHowManyAreReadyOrHaveProblems', () => {
    expect(summaryLine(report())).toBe('Word document: 3 requirements, all ready to import.')
    expect(summaryLine(report({ rows: 1, ready: 1 }))).toBe('Word document: 1 requirement, all ready to import.')
    expect(summaryLine(report({ withProblems: 1, ready: 2 }))).toBe('Word document: 3 requirements, 2 ready and 1 with a problem.')
    expect(summaryLine(report({ format: 'reqif', withProblems: 3, ready: 0 }))).toBe('ReqIF: 3 requirements, 0 ready and 3 with problems.')
    expect(summaryLine(report({ format: null, canUpload: false }))).toBe('unrecognised file: this file cannot be imported.')
    expect(formatName('xlsx')).toBe('Excel workbook')
    expect(formatName('odd')).toBe('odd')
  })

  it('VYB0957_AC23_theUploadButtonSaysWhatWillHappenToRowsWithProblems', () => {
    expect(uploadLabel(undefined, true)).toBe('Checking…')
    expect(uploadLabel(report(), false)).toBe('Upload')
    expect(uploadLabel(report({ withProblems: 1 }), false)).toBe('Upload (1 needs attention)')
    expect(uploadLabel(report({ withProblems: 4 }), false)).toBe('Upload (4 need attention)')
  })

  it('VYB0957_AC24_onlyTheFirstRowsAreListedAndTheRestAreCounted', () => {
    const rowProblems = Array.from({ length: 40 }, (_, i) => ({ row: i + 3, ref: `R-${i}`, title: 't', problems: ['x'] }))
    const r = report({ withProblems: 40, rowProblems })
    expect(shownRows(r)).toHaveLength(25)
    expect(hiddenRows(r)).toBe(15)
    expect(hiddenRows(report({ withProblems: 300, rowProblems: rowProblems.slice(0, 40) }))).toBe(275)   // past what the server returned
    expect(hiddenRows(report({ withProblems: 2, rowProblems: rowProblems.slice(0, 2) }))).toBe(0)
  })

  it('VYB0957_AC24_aRowIsNamedByItsOwnReferenceAndItsSheetRowAndAReqIfByReferenceOnly', () => {
    expect(rowLabel({ row: 7, ref: 'R-7', title: 't', problems: [] }, 'xlsx')).toBe('R-7 · row 7')
    expect(rowLabel({ row: 7, ref: '', title: 't', problems: [] }, 'xlsx')).toBe('row 7')
    expect(rowLabel({ row: 7, ref: 'REQ-1', title: 't', problems: [] }, 'reqif')).toBe('REQ-1')
  })

  it('VYB0957_AC25_theFilePickerOffersEveryFormatTheImportReads', () => {
    for (const ext of ['.xlsx', '.ods', '.xls', '.csv', '.docx', '.reqif', '.reqifz']) expect(ACCEPTED_FILES).toContain(ext)
  })
})
