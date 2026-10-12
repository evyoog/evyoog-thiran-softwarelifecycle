/**
 * VYB-0945 (F20): the diagram as a file. The SVG is already drawn with fixed colours (VYB-0538), so a PNG is that
 * drawing rasterised in the browser, and the PDF is the same raster inside a one-page PDF written here by hand
 * (no library): one JPEG image, a page the size of the diagram, nothing cut.
 */

/** PDF user units are points (1/72 in); the diagram is drawn in pixels at 96 per inch. */
export const PX_TO_PT = 0.75
/** The largest page a PDF reader is required to accept, in points. A bigger diagram is scaled down to fit. */
export const PDF_MAX_PT = 14400

export function pageSizePt(widthPx: number, heightPx: number): { w: number; h: number } {
  const w = Math.max(1, widthPx * PX_TO_PT)
  const h = Math.max(1, heightPx * PX_TO_PT)
  const k = Math.min(1, PDF_MAX_PT / Math.max(w, h))
  return { w: Math.round(w * k * 100) / 100, h: Math.round(h * k * 100) / 100 }
}

const enc = new TextEncoder()

/** A one-page PDF whose page is the size of the drawing (in CSS pixels) and shows only that JPEG (baseline, 8-bit RGB), however many pixels the image has. */
export function buildPdf(jpeg: Uint8Array, imageWidthPx: number, imageHeightPx: number, pageWidthPx = imageWidthPx, pageHeightPx = imageHeightPx): Uint8Array {
  if (jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8) throw new Error('Not a JPEG image')
  const { w, h } = pageSizePt(pageWidthPx, pageHeightPx)
  const content = `q ${w} 0 0 ${h} 0 0 cm /Im0 Do Q`

  const chunks: Uint8Array[] = []
  const offsets: number[] = []
  let length = 0
  const push = (c: Uint8Array | string) => {
    const b = typeof c === 'string' ? enc.encode(c) : c
    chunks.push(b)
    length += b.length
  }
  const startObject = (n: number) => { offsets[n] = length; push(`${n} 0 obj\n`) }

  push('%PDF-1.4\n%âãÏÓ\n')
  startObject(1); push('<< /Type /Catalog /Pages 2 0 R >>\nendobj\n')
  startObject(2); push('<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n')
  startObject(3)
  push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${w} ${h}] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>\nendobj\n`)
  startObject(4); push(`<< /Length ${enc.encode(content).length} >>\nstream\n${content}\nendstream\nendobj\n`)
  startObject(5)
  push(`<< /Type /XObject /Subtype /Image /Width ${imageWidthPx} /Height ${imageHeightPx} /ColorSpace /DeviceRGB `
    + `/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`)
  push(jpeg)
  push('\nendstream\nendobj\n')

  const xrefAt = length
  push('xref\n0 6\n0000000000 65535 f \n')
  for (let n = 1; n <= 5; n++) push(`${String(offsets[n]).padStart(10, '0')} 00000 n \n`)
  push(`trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefAt}\n%%EOF\n`)

  const out = new Uint8Array(length)
  let at = 0
  for (const c of chunks) { out.set(c, at); at += c.length }
  return out
}

const FONT = 'Inter, system-ui, -apple-system, "Segoe UI", sans-serif'
const SVG_NS = 'http://www.w3.org/2000/svg'

/** A standalone copy of the drawn SVG: namespace, size, a solid background, a font that exists outside the app. */
export function standaloneSvg(svg: SVGSVGElement, background = '#12161F'): { source: string; width: number; height: number } {
  const width = Math.ceil(Number(svg.getAttribute('width')) || svg.getBoundingClientRect().width)
  const height = Math.ceil(Number(svg.getAttribute('height')) || svg.getBoundingClientRect().height)
  const copy = svg.cloneNode(true) as SVGSVGElement
  copy.setAttribute('xmlns', SVG_NS)
  copy.setAttribute('width', String(width))
  copy.setAttribute('height', String(height))
  copy.setAttribute('viewBox', `0 0 ${width} ${height}`)
  copy.setAttribute('font-family', FONT)
  copy.removeAttribute('style')
  // The screen's own affordances are not part of the picture: the edge click targets.
  copy.querySelectorAll('[data-screen-only]').forEach((el) => el.remove())
  const bg = document.createElementNS(SVG_NS, 'rect')
  bg.setAttribute('x', '0'); bg.setAttribute('y', '0')
  bg.setAttribute('width', String(width)); bg.setAttribute('height', String(height))
  bg.setAttribute('fill', background)
  copy.insertBefore(bg, copy.firstChild)
  return { source: `<?xml version="1.0" encoding="UTF-8"?>\n${new XMLSerializer().serializeToString(copy)}`, width, height }
}

/** The largest canvas side the browsers agree on; a larger diagram is drawn at a lower scale rather than failing. */
const MAX_CANVAS_SIDE = 8000

export function rasterScale(width: number, height: number, wanted = 2): number {
  return Math.min(wanted, MAX_CANVAS_SIDE / Math.max(width, height, 1))
}

async function rasterise(svg: SVGSVGElement): Promise<{ canvas: HTMLCanvasElement; drawingWidth: number; drawingHeight: number }> {
  const { source, width, height } = standaloneSvg(svg)
  const img = new Image()
  img.decoding = 'sync'
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('The diagram could not be drawn as an image'))
    img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(source)}`
  })
  const k = rasterScale(width, height)
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(width * k))
  canvas.height = Math.max(1, Math.round(height * k))
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('The browser cannot draw the image')
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
  return { canvas, drawingWidth: width, drawingHeight: height }
}

function toBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('The image could not be made'))), type, quality)
  })
}

export function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}

export function exportFileName(base: string, ext: 'svg' | 'png' | 'pdf', today = new Date()): string {
  const slug = base.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'workflow'
  return `${slug}-workflow-${today.toISOString().slice(0, 10)}.${ext}`
}

export function exportSvgFile(svg: SVGSVGElement, filename: string) {
  download(new Blob([standaloneSvg(svg).source], { type: 'image/svg+xml' }), filename)
}

export async function exportPngFile(svg: SVGSVGElement, filename: string) {
  const { canvas } = await rasterise(svg)
  download(await toBlob(canvas, 'image/png'), filename)
}

export async function exportPdfFile(svg: SVGSVGElement, filename: string) {
  const { canvas, drawingWidth, drawingHeight } = await rasterise(svg)
  const jpeg = new Uint8Array(await (await toBlob(canvas, 'image/jpeg', 0.92)).arrayBuffer())
  download(new Blob([buildPdf(jpeg, canvas.width, canvas.height, drawingWidth, drawingHeight) as BlobPart], { type: 'application/pdf' }), filename)
}
