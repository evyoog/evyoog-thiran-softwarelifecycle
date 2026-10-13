/**
 * VYB-0958 (D45): connects a form field's visible label to its control, so a screen reader announces "Product, combo box" rather
 * than an unnamed control. The forms across the app lay a field out as {@code <label class="label">Text</label>} followed by the
 * control, with no {@code for} on the label, so the two were never joined (axe: "form elements must have labels"). Fixing each in its
 * source is the lasting repair and new forms must write {@code htmlFor}; until the older ones are moved, this joins them at runtime.
 * A ratchet test (labelAssociation.test.ts) stops the number of unjoined labels in the source from growing.
 *
 * <p>It acts only where the pairing is unambiguous: a label with no {@code for}, whose next sibling is (or holds exactly one of)
 * an input, select or textarea that has no accessible name of its own. It never overrides an id or a name already given.
 */
export interface FieldLike {
  id: string
  tagName: string
  getAttribute(name: string): string | null
}

export interface LabelLike {
  htmlFor: string
  getAttribute(name: string): string | null
  querySelector(selector: string): unknown
  nextElementSibling: ParentLike | null
}

export interface ParentLike extends Partial<FieldLike> {
  tagName: string
  querySelectorAll(selector: string): ArrayLike<FieldLike>
}

export interface Root {
  querySelectorAll(selector: string): ArrayLike<LabelLike>
}

const CONTROLS = 'input:not([type=hidden]):not([type=checkbox]):not([type=radio]), select, textarea'
let counter = 0

function controlFor(next: ParentLike | null): FieldLike | null {
  if (!next) return null
  if (/^(INPUT|SELECT|TEXTAREA)$/.test(next.tagName)) return next as FieldLike
  const inside = next.querySelectorAll(CONTROLS)
  return inside.length === 1 ? inside[0]! : null
}

/** Joins every unjoined label under {@code root} to its control; returns how many it joined. */
export function associateLabels(root: Root): number {
  let joined = 0
  const labels = root.querySelectorAll('label.label:not([for])')
  for (let i = 0; i < labels.length; i++) {
    const label = labels[i]!
    if (label.querySelector(CONTROLS)) continue                  // a label that wraps its control is already joined
    const control = controlFor(label.nextElementSibling)
    if (!control) continue
    if (control.getAttribute('aria-label') || control.getAttribute('aria-labelledby')) continue
    if (!control.id) control.id = `field-${++counter}`
    label.htmlFor = control.id
    joined++
  }
  return joined
}

/** Keeps the page's labels joined as screens render: one pass per animation frame however many changes happen. */
export function watchLabels(doc: Document): () => void {
  let scheduled = false
  const run = () => { scheduled = false; associateLabels(doc as unknown as Root) }
  const observer = new MutationObserver(() => {
    if (scheduled) return
    scheduled = true
    requestAnimationFrame(run)
  })
  observer.observe(doc.body, { childList: true, subtree: true })
  run()
  return () => observer.disconnect()
}
