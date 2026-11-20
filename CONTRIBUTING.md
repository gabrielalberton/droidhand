# Contributing to DroidHand

Thanks for your interest! DroidHand is an original, Apache-2.0 project. By contributing you agree your contributions are licensed under Apache-2.0.

## Ground rules

- **No license contamination.** Do not paste code from GPL/other projects (notably Shizuku). Study mechanisms, then write original code. Any third-party dependency must be license-compatible and listed in `THIRD-PARTY-NOTICES.md`.
- **No secrets, no PII in the repo.** No tokens, keys, real IPs, names, or emails. Use placeholders (`<NUC_TAILSCALE_IP>`).
- **Security first.** New tools in a sensitive/privileged tier must ship with a confirmation gate and an audit record. See PRD §7 and §9.
- **Respect the non-goals.** No root, no work-profile/MDM circumvention (PRD §2.1, §3.2).

## Workflow

1. Open an issue describing the change (templates in `.github/ISSUE_TEMPLATE/`).
2. Branch from `main`; keep PRs focused.
3. **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`…). Semver.
4. Add/adjust tests (unit + contract; instrumented for app behavior).
5. Pass CI (build, lint, test).
6. Describe security implications of your change in the PR.

## Project layout

- `app/` — Kotlin Portal app.
- `mcp-server/` — TypeScript MCP server + relay.
- `relay/` — shared transport notes.
- `docs/PRD.md` — the source of truth for scope and design.

## Dev setup (once scaffolds land)

- App: Android Studio, min SDK 30.
- MCP server: Node LTS, `npm i`, `npm run build`, `npm test`.

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
