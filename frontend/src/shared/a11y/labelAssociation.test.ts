import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import ts from 'typescript'
import { associateLabels, type LabelLike, type ParentLike, type Root } from './associateLabels'

class El implements ParentLike {
  id = ''
  htmlFor = ''
  nextElementSibling: El | null = null
  constructor(public tagName: string, private attrs: Record<string, string> = {}, public inside: El[] = []) {}
  getAttribute(name: string) { return this.attrs[name] ?? null }
  querySelector(selector: string) { void selector; return this.inside[0] ?? null }
  querySelectorAll() { return this.inside }
}

const rootOf = (labels: El[]): Root => ({ querySelectorAll: () => labels as unknown as ArrayLike<LabelLike> })

describe('VYB-0958 — a field label is joined to its control', () => {
  it('VYB0958_AC13_aLabelIsJoinedToTheControlThatFollowsItDirectlyOrInsideItsWrapper', () => {
    const select = new El('SELECT'), label = new El('LABEL'); label.nextElementSibling = select
    const input = new El('INPUT'), wrapper = new El('DIV', {}, [input]), label2 = new El('LABEL'); label2.nextElementSibling = wrapper
    expect(associateLabels(rootOf([label, label2]))).toBe(2)
    expect(label.htmlFor).toBe(select.id)
    expect(select.id).not.toBe('')
    expect(label2.htmlFor).toBe(input.id)
    expect(input.id).not.toBe(select.id)
  })

  it('VYB0958_AC13_itKeepsAnIdAlreadyGivenAndLeavesAmbiguousOrAlreadyNamedControlsAlone', () => {
    const named = new El('INPUT', {}), l1 = new El('LABEL'); named.id = 'mine'; l1.nextElementSibling = named
    expect(associateLabels(rootOf([l1]))).toBe(1)
    expect(l1.htmlFor).toBe('mine')

    const two = new El('DIV', {}, [new El('INPUT'), new El('SELECT')]), l2 = new El('LABEL'); l2.nextElementSibling = two
    const hasName = new El('INPUT', { 'aria-label': 'x' }), l3 = new El('LABEL'); l3.nextElementSibling = hasName
    const nothing = new El('DIV', {}, []), l4 = new El('LABEL'); l4.nextElementSibling = nothing
    const l5 = new El('LABEL')                                             // the last element: nothing follows it
    expect(associateLabels(rootOf([l2, l3, l4, l5]))).toBe(0)
    expect([l2, l3, l4, l5].map((l) => l.htmlFor)).toEqual(['', '', '', ''])
  })
})

/**
 * The lasting repair is `htmlFor` in the source. This ratchet counts the labels in the source that are not joined (no `htmlFor`,
 * and not wrapping a control) and fails if there are more than there were: fix some and lower BASELINE; never raise it. It counts
 * `<label className="label">` the way the forms are written.
 */
const BASELINE = 100

function walk(dir: string, out: string[] = []): string[] {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (p.endsWith('.tsx')) out.push(p)
  }
  return out
}

function unjoinedLabels(file: string): number {
  const sf = ts.createSourceFile(file, readFileSync(file, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  let n = 0
  const hasControl = (node: ts.Node): boolean => {
    let found = false
    const look = (c: ts.Node) => {
      if ((ts.isJsxOpeningElement(c) || ts.isJsxSelfClosingElement(c)) && /^(input|select|textarea)$/.test(c.tagName.getText())) found = true
      ts.forEachChild(c, look)
    }
    look(node)
    return found
  }
  const visit = (node: ts.Node) => {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText() === 'label') {
      const attrs = node.openingElement.attributes.properties.filter(ts.isJsxAttribute)
      const cls = attrs.find((a) => a.name.getText() === 'className')
      const isFieldLabel = !!cls && !!cls.initializer && ts.isStringLiteral(cls.initializer) && cls.initializer.text.split(/\s+/).includes('label')
      const joined = attrs.some((a) => a.name.getText() === 'htmlFor')
      if (isFieldLabel && !joined && !hasControl(node)) n++
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return n
}

describe('VYB-0958 — unjoined labels in the source can only go down', () => {
  it('VYB0958_AC14_theNumberOfFieldLabelsWithNoHtmlForDoesNotGrow', () => {
    const total = walk(resolve(__dirname, '../..')).reduce((sum, f) => sum + unjoinedLabels(f), 0)
    expect(total).toBeLessThanOrEqual(BASELINE)
    // when this fails low, lower BASELINE to the new count so the gain cannot be lost
    expect(BASELINE - total, `fixed some? lower BASELINE from ${BASELINE} to ${total}`).toBeLessThan(5)
  })
})
