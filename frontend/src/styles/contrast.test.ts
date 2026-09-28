import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * VYB-0770: an automated contrast-ratio pass, wired into the real build (`npm run
 * build` runs `tsc -b && vite build`; `npm test` — previously wired to a test runner
 * that had zero test files, so `npm test` had never actually passed or failed on
 * anything — now runs this). WCAG 2.1 AA: 4.5:1 for text-on-background (1.4.3; every
 * pair here is well under the 18pt/14pt-bold "large text" threshold that would relax
 * this to 3:1, so 4.5:1 applies uniformly), 3:1 for non-text UI component boundaries
 * (1.4.11 — a border against its background).
 *
 * Deliberately not a hand-copied list of "the pairs I think matter": this parses the
 * real `tokens.css` file at test time — every custom-property color, and every CSS
 * rule that declares `background`/`color`/`border-color` together via `var(--x)` — so
 * a colour changed in tokens.css without updating a parallel list here can't silently
 * stop being checked. A hand-maintained pair list is exactly the kind of drift this
 * codebase has already been burned by once (TenantExportService.TABLES, session 14).
 */

const __dirname = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(join(__dirname, 'tokens.css'), 'utf-8')
  .replace(/\/\*[\s\S]*?\*\//g, '') // strip comments

function extractVarBlock(css: string, selectorRegex: RegExp): Record<string, string> {
  const match = selectorRegex.exec(css)
  if (!match) throw new Error(`Could not find block for ${selectorRegex}`)
  const body = match[1]
  const vars: Record<string, string> = {}
  const re = /--([\w-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*[;}]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(body))) vars[m[1]] = m[2]
  return vars
}

const rootVars = extractVarBlock(css, /:root\s*\{([^}]*)\}/)
const lightVars = extractVarBlock(css, /\[data-theme="light"\]\s*\{([^}]*)\}/)
const themes: Record<string, Record<string, string>> = {
  dark: rootVars,
  light: { ...rootVars, ...lightVars },
}

// --- WCAG 2.1 contrast math ---
function hexToRgb(hex: string): [number, number, number] {
  let h = hex.replace('#', '')
  if (h.length === 3) h = h.split('').map((c) => c + c).join('')
  if (h.length === 4) h = h.slice(0, 3).split('').map((c) => c + c).join('') // #rgba shorthand, ignore alpha
  if (h.length === 8) h = h.slice(0, 6) // #rrggbbaa, ignore alpha
  const num = parseInt(h.slice(0, 6), 16)
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255]
}
function relLuminance([r, g, b]: [number, number, number]): number {
  const [rs, gs, bs] = [r, g, b].map((v) => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
}
function contrastRatio(hexA: string, hexB: string): number {
  const lA = relLuminance(hexToRgb(hexA))
  const lB = relLuminance(hexToRgb(hexB))
  const [lighter, darker] = lA > lB ? [lA, lB] : [lB, lA]
  return (lighter + 0.05) / (darker + 0.05)
}

// --- Parse every rule that pairs a background with a text colour and/or a border colour ---
interface RulePair { selector: string; bgVar: string; fgVar?: string; borderVar?: string }

function parseRules(css: string): RulePair[] {
  const pairs: RulePair[] = []
  const ruleRe = /([^{}]+)\{([^{}]*)\}/g
  let m: RegExpExecArray | null
  while ((m = ruleRe.exec(css))) {
    const selector = m[1].trim()
    const body = m[2]
    const bg = /background(?:-color)?\s*:\s*var\(--([\w-]+)\)/.exec(body)
    if (!bg) continue // no background var declared in this rule — nothing to pair against
    const fg = /(?:^|[;\s])color\s*:\s*var\(--([\w-]+)\)/.exec(body)
    // Broad enough to catch border-bottom/-top/-left/-right too, not just the bare
    // `border`/`border-color` shorthand — a first draft missed `.tbl thead th`'s
    // `border-bottom` entirely, which would have made this check quietly incomplete.
    const border = /border(?:-(?:top|bottom|left|right|color))?\s*:\s*(?:\d+px\s+\w+\s+)?var\(--([\w-]+)\)/.exec(body)
    if (fg || border) {
      pairs.push({
        selector,
        bgVar: bg[1],
        fgVar: fg ? fg[1] : undefined,
        borderVar: border ? border[1] : undefined,
      })
    }
  }
  return pairs
}

const rulePairs = parseRules(css)

describe('design token contrast (WCAG 2.1 AA)', () => {
  it('found real rules to check — a parser that matches nothing would pass vacuously', () => {
    expect(rulePairs.length).toBeGreaterThan(5)
  })

  for (const themeName of Object.keys(themes)) {
    const vars = themes[themeName]

    describe(`${themeName} theme`, () => {
      for (const pair of rulePairs) {
        const bgHex = vars[pair.bgVar]
        if (!bgHex) continue // background var isn't a colour this theme defines (e.g. a shadow token)

        if (pair.fgVar) {
          const fgHex = vars[pair.fgVar]
          if (fgHex) {
            it(`${pair.selector} — text (--${pair.fgVar}) on background (--${pair.bgVar}) ≥ 4.5:1`, () => {
              const ratio = contrastRatio(fgHex, bgHex)
              expect(ratio).toBeGreaterThanOrEqual(4.5)
            })
          }
        }

        if (pair.borderVar) {
          // A border set to the exact same token as its own background (e.g. `.btn.pri`
          // filling and bordering itself with --brand) isn't a boundary at all — there's
          // no edge to perceive, so WCAG 1.4.11 doesn't meaningfully apply. Not a real
          // finding; skip it rather than assert a check that can never pass or matter.
          if (pair.borderVar === pair.bgVar) continue
          const borderHex = vars[pair.borderVar]
          if (borderHex) {
            it(`${pair.selector} — border (--${pair.borderVar}) on background (--${pair.bgVar}) ≥ 3:1 (non-text UI boundary)`, () => {
              const ratio = contrastRatio(borderHex, bgHex)
              expect(ratio).toBeGreaterThanOrEqual(3)
            })
          }
        }
      }
    })
  }

  it('body text (--tx on --bg) meets AA in both themes', () => {
    for (const vars of Object.values(themes)) {
      expect(contrastRatio(vars.tx, vars.bg)).toBeGreaterThanOrEqual(4.5)
    }
  })
})
