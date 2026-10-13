import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * VYB-0958 (D45): the size budget of the built application, checked after every build so code splitting cannot quietly come undone.
 *
 *   MAX_CHUNK   no single script may exceed this (a screen that grows past it should be split further)
 *   MAX_INITIAL what the first page load fetches: the entry script and everything index.html loads with it, before any screen
 *
 * Sizes are raw bytes as written to dist/. Before code splitting the whole application was one 861 kB script.
 */
export const MAX_CHUNK = 250 * 1024
export const MAX_INITIAL = 450 * 1024

/** The scripts index.html loads up front: its module script and every modulepreload. */
export function initialScripts(indexHtml) {
  return [...new Set([...indexHtml.matchAll(/(?:src|href)="\/?(assets\/[^"]+\.js)"/g)].map((m) => m[1]))]
}

/** @param sizes map of "assets/x.js" to bytes; @returns the problems, empty when within budget */
export function check(sizes, initial) {
  const problems = []
  for (const [file, bytes] of Object.entries(sizes)) {
    if (bytes > MAX_CHUNK) problems.push(`${file} is ${kb(bytes)} kB; the limit for one script is ${kb(MAX_CHUNK)} kB. Split it further.`)
  }
  const total = initial.reduce((sum, f) => sum + (sizes[f] ?? 0), 0)
  if (total > MAX_INITIAL) problems.push(`The first page load fetches ${kb(total)} kB of script; the limit is ${kb(MAX_INITIAL)} kB. Load more of it on demand.`)
  return problems
}

const kb = (bytes) => Math.round(bytes / 1024)

function main() {
  const dist = resolve(fileURLToPath(import.meta.url), '../../dist')
  const sizes = {}
  for (const f of readdirSync(join(dist, 'assets'))) if (f.endsWith('.js')) sizes[`assets/${f}`] = statSync(join(dist, 'assets', f)).size
  const initial = initialScripts(readFileSync(join(dist, 'index.html'), 'utf8'))
  const problems = check(sizes, initial)
  const total = initial.reduce((sum, f) => sum + (sizes[f] ?? 0), 0)
  console.log(`first page load: ${kb(total)} kB of script in ${initial.length} files (limit ${kb(MAX_INITIAL)} kB); largest script: ${kb(Math.max(...Object.values(sizes)))} kB (limit ${kb(MAX_CHUNK)} kB)`)
  if (problems.length) {
    for (const p of problems) console.error(`✕ ${p}`)
    process.exit(1)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
