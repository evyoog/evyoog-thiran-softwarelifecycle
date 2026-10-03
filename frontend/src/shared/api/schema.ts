/**
 * VYB-0912 (F12): the API types generated from the backend's OpenAPI document.
 *
 *   docs/06-api/openapi/openapi.json   committed copy of what the running API publishes
 *                                      (backend test OpenApiDocumentIT keeps it current)
 *   generated/schema.d.ts              `npm run generate-api` turns that into TypeScript
 *
 * Never edit the generated file by hand. Import from here, not from `generated/`:
 *
 *   import type { Schemas } from '@/shared/api/schema'
 *   type Row = Schemas['RequirementView']
 *
 * What the generated types are, and are not, today: they carry every field name and primitive type
 * the API sends, but the backend does not yet mark fields required or describe its enums, so every
 * field is optional and a status or type is `string`. `client.ts` keeps its richer hand-written
 * types for that reason, and `apiContract.test.ts` checks them against this document so they cannot
 * silently drift. Moving screens onto the generated types needs the backend DTOs annotated first.
 */
import type { components, paths } from './generated/schema'

export type Schemas = components['schemas']
export type ApiPaths = keyof paths
export type { components, paths }
