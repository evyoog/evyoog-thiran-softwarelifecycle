import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'

/**
 * VYB-0958 (D45): an automated accessibility audit of the running application, with axe-core, in both themes. It opens each
 * screen in Chromium against a stubbed API (so it needs no backend), runs the WCAG 2.0/2.1 A and AA rules plus axe's best-practice
 * rules, and lists every violation with the elements it is on. It exits non-zero on a serious or critical violation.
 *
 *   npm run dev                          (in one terminal; set VITE_SKIP_AUTH=true to audit the signed-in screens)
 *   node scripts/a11y-audit.mjs http://localhost:5175
 *   VITE_SKIP_AUTH=false npm run dev -- --port 5180   then   node scripts/a11y-audit.mjs http://localhost:5180 --signin
 *
 * It needs Playwright with a Chromium (set PLAYWRIGHT_MODULE to its path and CHROMIUM to the browser binary when they are not on the
 * default paths). Automated rules find about a third of real problems: this audit does not replace a person using a keyboard and a
 * screen reader, and a clean run says only that axe found nothing.
 */
const require = createRequire(import.meta.url)
const base = process.argv[2] ?? 'http://localhost:5175'
const signIn = process.argv.includes('--signin')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE ?? '/opt/node22/lib/node_modules/playwright')
const axeSource = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8')

const SIGNED_IN_ROUTES = [
  '/', '/my-work', '/requirements', '/requirements/new', '/requirements/board', '/requirements/import', '/portfolio',
  '/quality', '/analytics', '/compliance', '/delivery', '/releases', '/design', '/admin',
]

const page200 = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 }

async function stub(page) {
  await page.route('**/api/v1/**', (route) => {
    const p = new URL(route.request().url()).pathname.replace('/api/v1', '')
    const json = (b, s = 200) => route.fulfill({ status: s, contentType: 'application/json', body: JSON.stringify(b) })
    if (signIn && (p.startsWith('/auth'))) return json({ title: 'Unauthorized' }, 401)
    if (p === '/me') return json({ id: 'u1', email: 'a@x.test', displayName: 'Ada Lovelace', platformAdministrator: true, activeGrants: [] })
    if (p === '/products') return json([{ id: 'p1', key: 'VY', name: 'Vyoog', archived: false }])
    if (p === '/requirements' || p === '/findings' || p.endsWith('/page')) return json(page200)
    if (p === '/releases/current') return route.fulfill({ status: 204 })
    if (p === '/products/dashboard') return json({ totals: { productCount: 0, appCount: 0, reqCount: 0, gapCount: 0, appsBelowThreshold: 0 }, products: [] })
    if (p === '/tasks/mine' || p.startsWith('/tasks')) return json([])
    return json([])
  })
}

const failures = []
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] })
const routes = signIn ? ['/'] : SIGNED_IN_ROUTES
for (const theme of ['dark', 'light']) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  await stub(page)
  await page.addInitScript((t) => { try { localStorage.setItem('vyoog-theme', t) } catch { /* ignore */ } }, theme)
  for (const route of routes) {
    await page.goto(base + route, { waitUntil: 'networkidle' })
    await page.waitForTimeout(400)
    await page.evaluate((t) => { document.documentElement.dataset.theme = t }, theme)
    await page.evaluate(axeSource)
    const result = await page.evaluate(() => window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'] } }))
    for (const v of result.violations) {
      failures.push({ theme, route, id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.length, sample: v.nodes[0]?.target?.join(' ') })
    }
    console.log(`${theme} ${route}: ${result.violations.length} violation${result.violations.length === 1 ? '' : 's'}`)
  }
  await context.close()
}
await browser.close()

const byRule = new Map()
for (const f of failures) {
  const key = f.id
  const entry = byRule.get(key) ?? { ...f, where: new Set(), total: 0 }
  entry.where.add(`${f.theme}:${f.route}`)
  entry.total += f.nodes
  byRule.set(key, entry)
}
for (const e of byRule.values()) console.log(`\n[${e.impact}] ${e.id}: ${e.help}\n  ${e.total} elements on ${e.where.size} page/theme runs, e.g. ${e.sample}`)
const blocking = failures.filter((f) => f.impact === 'serious' || f.impact === 'critical')
console.log(`\n${failures.length} violations (${blocking.length} serious or critical) across ${routes.length} screens in 2 themes.`)
process.exit(blocking.length ? 1 : 0)
