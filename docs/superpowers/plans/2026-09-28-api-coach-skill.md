# API and coaching Skill implementation plan

**Goal:** Deliver the user's selected HTTP API + Skill interface for shared human/AI training management, with structured context for future examination reports. No MCP service.

**Architecture:** Reuse the authenticated training/running APIs and optimistic versions. Add a small health-context API using the existing versioned JSON store; preserve report facts, declared clinician advice, constraints and lifestyle preferences without a diagnostic rules engine. Ship a versioned `stridehub-coach` Skill and a standard-library HTTP client, installed locally with an external credential reference.

**Stack:** Symfony/PHP 8.5, Doctrine DBAL/SQLite, Python 3 standard library, Agent Skills Markdown, PHPUnit and HTTP integration checks.

## Scope and boundaries

- The user selected this approach explicitly and plans to supply health reports later. No actual report has been provided; no health findings or recommendations will be generated for the user during implementation.
- Store structured summaries and source references, not binary report uploads. The Skill reads original reports supplied in a later conversation using that environment's document capabilities and preserves ambiguous readings as unresolved.
- `GET/PUT /api/v1/health/context` provides report observations, sourced constraints and lifestyle preferences. PUT is partial at the top level; explicitly supplied arrays replace those arrays. Reference integrity and optimistic version checks protect existing data. This is storage/validation, not medical verification or enforcement of prose constraints.
- Current API credentials are instance-wide. The Skill operates only within the user's instruction, does not independently enable recurring automation, and does not overwrite completed training or conflicting edits.
- Persist only necessary report fields. No health documents, credentials or personal medical data enter Git, example files or test fixtures; behavioral evaluation uses labeled synthetic reports.

## Work

- [x] Establish baseline behavior for version conflicts, whole-history analysis and report persistence without a Skill.
- [x] Implement the health-context API and machine-readable OpenAPI, with tests for auth, partial updates, conflict protection, nested validation and report references.
- [x] Implement the Python API helper; verify real HTTP behavior, denied redirects, path validation, no mutation retries and credential redaction.
- [x] Author Skill entrypoint plus API, dynamic planning and report interpretation references. Keep medical observations, declared clinician guidance and AI suggestions distinct.
- [x] Forward-test the Skill with independent synthetic scenarios, fixing demonstrated gaps.
- [x] Install the Skill without overwriting an existing installation; configure connection outside Git, referencing existing local credentials.
- [x] Run real local read/write/readback checks with empty or disposable data, scoped regression, static analysis and skill validation; document exact limits.
- [x] Commit and push the completed changes, updating the existing feature PR.
