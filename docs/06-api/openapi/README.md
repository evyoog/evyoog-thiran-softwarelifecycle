# OpenAPI

The running API publishes its OpenAPI document at `/v3/api-docs` and Swagger UI at `/swagger-ui.html` (springdoc; both are readable without sign-in).

[`openapi.json`](openapi.json) in this folder is a generated copy of that document (VYB-0912): OpenAPI 3.0.1, every path and schema the API publishes, keys sorted, `servers` removed. **Do not edit it.** The backend integration test `OpenApiDocumentIT` compares it with what the running application publishes and fails when they differ, so a controller or DTO change cannot be committed without it. To rewrite it, run that test with `-Dopenapi.write=true` (the command is in the test's Javadoc and in `frontend/README.md`), then `cd frontend && npm run generate-api` to regenerate the TypeScript types from it.

Limits of the document today: the backend does not mark fields required or describe enums, so every field is optional and a status or type is a plain string. Fixing that means annotating the DTOs, which also changes what the generated types can be used for.
