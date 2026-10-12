import { describe, it, expect } from 'vitest'
import { buildPdf, exportFileName, pageSizePt, PDF_MAX_PT, rasterScale } from './diagramFile'

const text = (b: Uint8Array) => new TextDecoder('latin1').decode(b)
// SOI + a few bytes + EOI: enough of a JPEG for the container, which never decodes it.
const FAKE_JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 0xff, 0xd9])

describe('VYB-0945 — the PDF export', () => {
  it('VYB0945_AC4_theDocumentIsOneImagePageTheSizeOfTheDiagram', () => {
    const pdf = text(buildPdf(FAKE_JPEG, 1600, 800, 800, 400))
    expect(pdf.startsWith('%PDF-1.4')).toBe(true)
    expect(pdf).toContain('/MediaBox [0 0 600 300]')       // 800 x 400 px at 0.75 pt per px
    expect(pdf).toContain('/Count 1')
    expect(pdf).toContain('/Width 1600 /Height 800')       // the image keeps its own pixels
    expect(pdf).toContain('/Filter /DCTDecode')
    expect(pdf.trimEnd().endsWith('%%EOF')).toBe(true)
  })

  it('VYB0945_AC4_everyCrossReferenceOffsetPointsAtItsObject', () => {
    const bytes = buildPdf(FAKE_JPEG, 100, 50)
    const pdf = text(bytes)
    const xrefAt = Number(/startxref\n(\d+)\n/.exec(pdf)![1])
    expect(pdf.slice(xrefAt, xrefAt + 4)).toBe('xref')
    const entries = [...pdf.slice(xrefAt).matchAll(/(\d{10}) 00000 n /g)].map((m) => Number(m[1]))
    expect(entries).toHaveLength(5)
    entries.forEach((at, i) => expect(pdf.slice(at, at + `${i + 1} 0 obj`.length)).toBe(`${i + 1} 0 obj`))
  })

  it('VYB0945_AC4_theImageStreamIsTheJpegByteForByteAndItsLengthIsRight', () => {
    const bytes = buildPdf(FAKE_JPEG, 100, 50)
    const pdf = text(bytes)
    expect(pdf).toContain(`/Length ${FAKE_JPEG.length} >>\nstream\n`)
    const at = pdf.indexOf('stream\n', pdf.indexOf('/Subtype /Image')) + 'stream\n'.length
    expect(Array.from(bytes.slice(at, at + FAKE_JPEG.length))).toEqual(Array.from(FAKE_JPEG))
  })

  it('VYB0945_AC4_somethingThatIsNotAJpegIsRefused', () => {
    expect(() => buildPdf(new Uint8Array([1, 2, 3, 4]), 10, 10)).toThrow('Not a JPEG')
  })

  it('VYB0945_AC5_aHugeDiagramIsScaledToFitAPageAndNeverCut', () => {
    const { w, h } = pageSizePt(40000, 10000)
    expect(Math.max(w, h)).toBeLessThanOrEqual(PDF_MAX_PT)
    expect(w / h).toBeCloseTo(4, 1)
    expect(pageSizePt(800, 400)).toEqual({ w: 600, h: 300 })
  })
})

describe('VYB-0945 — the image export', () => {
  it('VYB0945_AC5_aLargeDiagramIsDrawnAtALowerScaleRatherThanFailing', () => {
    expect(rasterScale(1000, 500)).toBe(2)
    expect(rasterScale(10000, 500)).toBeCloseTo(0.8)
    expect(rasterScale(0, 0)).toBe(2)
  })

  it('VYB0945_AC5_fileNamesAreSafeAndDated', () => {
    const d = new Date('2026-10-12T09:00:00Z')
    expect(exportFileName('a1b2', 'png', d)).toBe('a1b2-workflow-2026-10-12.png')
    expect(exportFileName('My App / v2!', 'pdf', d)).toBe('My-App-v2-workflow-2026-10-12.pdf')
    expect(exportFileName('///', 'svg', d)).toBe('workflow-workflow-2026-10-12.svg')
  })
})
