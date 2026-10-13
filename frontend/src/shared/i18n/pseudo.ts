/**
 * VYB-0958 (D45): the built-in test language, "en-XA". It rewrites every English message so that anything shown on screen WITHOUT
 * going through the catalogue stands out (plain English among accented text), and so that a layout too tight for longer words
 * shows it (each message is padded by about a third and bracketed, so a truncated message loses its closing bracket).
 * Placeholders such as {name} and {count} are left exactly as they are. Deterministic: the same English gives the same output.
 */
const MAP: Record<string, string> = {
  a: 'å', b: 'ƀ', c: 'ç', d: 'ð', e: 'é', f: 'ƒ', g: 'ĝ', h: 'ĥ', i: 'î', j: 'ĵ', k: 'ķ', l: 'ļ', m: 'ɱ', n: 'ñ', o: 'ö', p: 'þ', q: 'ǫ', r: 'ŕ',
  s: 'š', t: 'ţ', u: 'û', v: 'ṽ', w: 'ŵ', x: 'ẋ', y: 'ý', z: 'ž',
  A: 'Å', B: 'Ɓ', C: 'Ç', D: 'Ð', E: 'É', F: 'Ƒ', G: 'Ĝ', H: 'Ĥ', I: 'Î', J: 'Ĵ', K: 'Ķ', L: 'Ļ', M: 'Ṁ', N: 'Ñ', O: 'Ö', P: 'Þ', Q: 'Ǫ', R: 'Ŕ',
  S: 'Š', T: 'Ţ', U: 'Û', V: 'Ṽ', W: 'Ŵ', X: 'Ẋ', Y: 'Ý', Z: 'Ž',
}

/** Letters outside placeholders are mapped; everything else (digits, punctuation, {placeholders}) is kept. */
export function pseudoLocalize(message: string): string {
  let out = ''
  let letters = 0
  const parts = message.split(/(\{[A-Za-z0-9_]+\})/)
  for (const part of parts) {
    if (/^\{[A-Za-z0-9_]+\}$/.test(part)) {
      out += part
      continue
    }
    for (const ch of part) {
      const mapped = MAP[ch]
      if (mapped) letters++
      out += mapped ?? ch
    }
  }
  const pad = '~'.repeat(Math.ceil(letters * 0.3))
  return `[${out}${pad ? ' ' + pad : ''}]`
}
