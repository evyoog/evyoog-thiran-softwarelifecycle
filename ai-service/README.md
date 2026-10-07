# ai-service

Reserved for a separate AI service (the reference structure names Python/FastAPI). **There is none today.** The optional AI features (requirement rewrite suggestions, test-case suggestions, trace-relation and conflict adjudication, document analysis, embeddings) run inside the backend, in `com.vyoog.ai`, and call OpenAI through one model gateway (`ModelGateway`, VYB-0936, D29); they are off unless `AI_ENABLED=true` and `AI_API_KEY` are set, and their output is only ever a proposal a person accepts (`CLAUDE.md` rule 6).

Phase 6 builds the gateway (VYB-0936, done) and plans redaction, budgets and a single review endpoint (VYB-0937 to VYB-0940, AI governance). Whether that becomes a separate service here or stays in the backend is not decided; record the decision in `docs/DECISIONS.md` before adding code to this folder.
