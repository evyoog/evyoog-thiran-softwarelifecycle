# ai-service

Reserved for a separate AI service (the reference structure names Python/FastAPI). **There is none today.** The optional AI features (requirement rewrite suggestions, test-case suggestions, trace-relation and conflict adjudication, document analysis, embeddings) run inside the backend, in `com.vyoog.ai`, and call OpenAI directly; they are off unless `AI_ENABLED=true` and `AI_API_KEY` are set, and their output is only ever a proposal a person accepts (`CLAUDE.md` rule 6).

Phase 6 plans an AI gateway with redaction, budgets and a single review endpoint (rows VYB-0936 to VYB-0940, AI governance). Whether that becomes a separate service here or stays in the backend is not decided; record the decision in `docs/DECISIONS.md` before adding code to this folder.
