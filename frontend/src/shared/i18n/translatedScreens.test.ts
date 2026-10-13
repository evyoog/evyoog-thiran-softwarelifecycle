import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ts from 'typescript'

/**
 * VYB-0958 (D45): the screens that have moved into the message catalogue must stay there. This reads each file's syntax tree and
 * fails on any text written straight into the markup (JSX text) or into an attribute people hear or read (title, aria-label,
 * placeholder, alt), so a string added to one of these screens without going through the catalogue fails the build. Add a screen to
 * TRANSLATED when its text has been moved; the brand name is the one thing that is never translated.
 */
const TRANSLATED = [
  'app/Shell.tsx', 'app/CommandPalette.tsx', 'app/NotificationBell.tsx', 'app/AuthScreen.tsx', 'features/Home.tsx', 'features/MyWork.tsx',
]
const TEXT_ATTRIBUTES = new Set(['title', 'aria-label', 'placeholder', 'alt', 'aria-description', 'label'])
const NEVER_TRANSLATED = new Set(['Vyoog'])

function hardCoded(relative: string): string[] {
  const path = resolve(__dirname, '../..', relative)
  const sf = ts.createSourceFile(path, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const found: string[] = []
  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) {
      const text = node.getText().replace(/\s+/g, ' ').trim()
      if (/[A-Za-z]{2,}/.test(text) && !NEVER_TRANSLATED.has(text)) found.push(`text "${text}"`)
    } else if (ts.isJsxAttribute(node) && TEXT_ATTRIBUTES.has(node.name.getText())) {
      const init = node.initializer
      if (init && ts.isStringLiteral(init) && /[A-Za-z]{2,}/.test(init.text)) found.push(`${node.name.getText()}="${init.text}"`)
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return found.map((f) => `${relative}: ${f}`)
}

describe('VYB-0958 — translated screens have no hard-coded text', () => {
  it('VYB0958_AC10_noTranslatedScreenWritesEnglishStraightIntoItsMarkupOrItsLabels', () => {
    const all = TRANSLATED.flatMap(hardCoded)
    expect(all).toEqual([])
  })

  it('VYB0958_AC10_theGuardSeesTextAndLabelsWhenThereAreSome', () => {
    // prove the scanner is not blind: it must find plain text and a label in a file that has them
    const probe = ts.createSourceFile('probe.tsx', `export const A = () => <button title="Sign out" aria-label={x}>Hello there</button>`,
      ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
    const found: string[] = []
    const visit = (n: ts.Node) => {
      if (ts.isJsxText(n) && /[A-Za-z]{2,}/.test(n.getText())) found.push('text')
      if (ts.isJsxAttribute(n) && TEXT_ATTRIBUTES.has(n.name.getText()) && n.initializer && ts.isStringLiteral(n.initializer)) found.push('attr')
      ts.forEachChild(n, visit)
    }
    visit(probe)
    expect(found.sort()).toEqual(['attr', 'text'])
  })
})
