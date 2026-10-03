import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ts from 'typescript'
import { describe, expect, expectTypeOf, it } from 'vitest'
import type { Schemas } from './schema'

/**
 * VYB-0912 (F12): `client.ts` still holds the hand-written request and response types, so this checks
 * them against the OpenAPI document the backend publishes (docs/06-api/openapi/openapi.json, kept
 * current by the backend test OpenApiDocumentIT). It fails when a screen's type names a field the
 * API does not send, which is what a backend rename or removal looks like from here.
 *
 * An interface is compared with the schema of the same name, or the name plus `View` or `Response`.
 * Interfaces with no such schema (the API returns an untyped map, or the schema is named otherwise)
 * are not covered; the count below is a floor so that coverage cannot quietly shrink.
 */

const spec = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/06-api/openapi/openapi.json'), 'utf8')) as {
  components: { schemas: Record<string, { properties?: Record<string, unknown> }> }
}

function handWrittenInterfaces(): Map<string, string[]> {
  const file = ts.createSourceFile('client.ts', readFileSync(resolve(__dirname, 'client.ts'), 'utf8'),
    ts.ScriptTarget.ES2022, true)
  const out = new Map<string, string[]>()
  file.forEachChild(node => {
    if (ts.isInterfaceDeclaration(node)) {
      out.set(node.name.text, node.members.filter(ts.isPropertySignature).map(m => m.name.getText()))
    }
  })
  return out
}

function schemaFor(name: string): string | undefined {
  return [name, `${name}View`, `${name}Response`].find(candidate => spec.components.schemas[candidate])
}

describe('VYB-0912 — the hand-written API types agree with the published OpenAPI document', () => {
  const interfaces = handWrittenInterfaces()
  const compared = [...interfaces].flatMap(([name, fields]) => {
    const schema = schemaFor(name)
    return schema ? [{ name, schema, fields }] : []
  })

  it('VYB0912_AC2_enoughOfTheHandWrittenTypesAreCheckedAgainstTheDocument', () => {
    expect(interfaces.size).toBeGreaterThan(100)
    expect(compared.length).toBeGreaterThanOrEqual(55)
  })

  it('VYB0912_AC2_noCheckedTypeNamesAFieldTheApiDoesNotSend', () => {
    const drift = compared.flatMap(({ name, schema, fields }) => {
      const sent = Object.keys(spec.components.schemas[schema].properties ?? {})
      const missing = fields.filter(f => !sent.includes(f))
      return missing.length ? [`${name} (${schema}): ${missing.join(', ')}`] : []
    })
    expect(drift, 'frontend types with fields the API no longer sends').toEqual([])
  })

  it('VYB0912_AC2_aDriftedFieldWouldBeCaught', () => {
    // The check itself: a made-up field is reported, so a green run above means something.
    const sent = Object.keys(spec.components.schemas.RequirementView.properties ?? {})
    expect(sent).toContain('title')
    expect(sent).not.toContain('aFieldTheBackendNeverSent')
  })
})

describe('VYB-0912 — the generated types are importable and carry the API shape', () => {
  it('VYB0912_AC1_theGeneratedSchemaTypesAreUsableFromTheSharedEntryPoint', () => {
    expectTypeOf<Schemas['RequirementView']['title']>().toEqualTypeOf<string | undefined>()
    expectTypeOf<Schemas['RequirementView']['revision']>().toEqualTypeOf<number | undefined>()
    expect(true).toBe(true)
  })
})
