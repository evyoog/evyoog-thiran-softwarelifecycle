/**
 * VYB-0937 (F27): what the AI redaction card shows. Before text goes to a model provider, secrets are always removed and
 * these kinds of personal data are replaced by tokens (and put back in the reply). An administrator may switch each
 * personal-data kind off; a secret cannot be. The server decides all of it; this only labels it.
 */

export interface RedactionKind {
  key: string
  label: string
  detail: string
  /** True for secrets: always on, no switch. */
  locked: boolean
}

export const REDACTION_KINDS: RedactionKind[] = [
  { key: 'SECRET', label: 'Secrets', locked: true,
    detail: 'Keys, tokens, passwords, private keys and credentials in a URL. Removed and never put back. Cannot be switched off.' },
  { key: 'EMAIL', label: 'Email addresses', locked: false, detail: 'Replaced by a token and put back in the reply.' },
  { key: 'PHONE', label: 'Phone numbers', locked: false,
    detail: 'With a leading +, a bracketed area code, or written 415-555-2671 or 98765 43210. A bare run of digits is not treated as a phone number.' },
  { key: 'CARD', label: 'Card numbers', locked: false, detail: '13 to 19 digits that pass the card check.' },
  { key: 'IP_ADDRESS', label: 'IP addresses', locked: false, detail: 'IPv4. A version number written like 1.2.3.4 is also taken.' },
  { key: 'PERSON', label: 'Names of people in this system', locked: false,
    detail: "Display names from this system's own user list, matched exactly. A name of someone not in the list is not found." },
]

export function isOn(kind: RedactionKind, disabled: string[]): boolean {
  return kind.locked || !disabled.includes(kind.key)
}

/** The list to save after the administrator flips one kind. A locked kind is never added. */
export function toggled(disabled: string[], key: string): string[] {
  const kind = REDACTION_KINDS.find((k) => k.key === key)
  if (!kind || kind.locked) return [...disabled].sort()
  const next = disabled.includes(key) ? disabled.filter((d) => d !== key) : [...disabled, key]
  return next.sort()
}

export function stateText(on: boolean): string {
  return on ? 'On' : 'Off'
}

/** One line under the list: what is true now, in words. */
export function summary(disabled: string[]): string {
  const off = REDACTION_KINDS.filter((k) => !k.locked && disabled.includes(k.key)).map((k) => k.label.toLowerCase())
  if (off.length === 0) return 'Everything above is removed or replaced before text is sent to the AI provider.'
  return `Switched off: ${off.join(', ')}. That text is sent to the AI provider as written. Secrets are still removed.`
}
