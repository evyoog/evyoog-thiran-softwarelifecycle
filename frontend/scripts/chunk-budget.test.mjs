import { describe, it, expect } from 'vitest'
import { MAX_CHUNK, check, initialScripts } from './chunk-budget.mjs'

describe('VYB-0958 — the build stays split', () => {
  it('VYB0958_AC12_theFirstPageLoadIsTheEntryScriptAndWhatItPreloadsAndNothingElse', () => {
    const html = '<script type="module" src="/assets/index-a.js"></script><link rel="modulepreload" href="/assets/react-b.js"><link rel="stylesheet" href="/assets/index-c.css"><link rel="modulepreload" href="/assets/react-b.js">'
    expect(initialScripts(html)).toEqual(['assets/index-a.js', 'assets/react-b.js'])
  })

  it('VYB0958_AC12_aScriptOverTheLimitAndAnOverweightFirstLoadAreEachReported', () => {
    expect(check({ 'assets/index-a.js': 100 * 1024, 'assets/Home-b.js': 5 * 1024 }, ['assets/index-a.js'])).toEqual([])
    expect(check({ 'assets/index-a.js': MAX_CHUNK + 1 }, [])).toHaveLength(1)
    const heavy = { 'assets/a.js': 200 * 1024, 'assets/b.js': 200 * 1024, 'assets/c.js': 100 * 1024 }
    expect(check(heavy, Object.keys(heavy))).toHaveLength(1)           // 500 kB at first load, no single script over the limit
    expect(check({ 'assets/a.js': MAX_CHUNK + 1, 'assets/b.js': 200 * 1024 }, ['assets/a.js', 'assets/b.js'])).toHaveLength(2)   // one script too big, and the first load too
  })
})
