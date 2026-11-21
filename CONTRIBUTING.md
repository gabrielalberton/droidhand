# Contributing to DroidHand

Thanks for your interest! DroidHand is an original, Apache-2.0 project. By contributing you agree your contributions are licensed under Apache-2.0.

## Ground rules

- **No license contamination.** Do not paste code from GPL/other projects (notably Shizuku). Study mechanisms, then write original code. Any third-party dependency must be license-compatible and listed in `THIRD-PARTY-NOTICES.md`.
- **No secrets, no PII in the repo.** No tokens, keys, real IPs, names, or emails. Use placeholders (`<computer-tailnet-ip>`).
- **Security first.** New tools that act on the phone must go through the existing guards (Stop, main-screen confirmation, background-display controls) and be audited. See [SECURITY.md](SECURITY.md).
- **Respect the non-goals.** No root, no work-profile/MDM circumvention (PRD §2.1, §3.2).

## Workflow

1. Open an issue describing the change (templates in `.github/ISSUE_TEMPLATE/`).
2. Branch from `main`; keep PRs focused.
3. **Conventional Commits** (`feat:`, `fix:`, `docs:`, `refactor:`…) and semver.
4. Add or adjust tests.
5. CI must pass (typecheck, tests, builds, tools-doc check).
6. Describe the security implications of your change in the PR.

## Project layout

- `android/` — the DroidHand app (Kotlin, Compose). Layout in [docs/architecture.md](docs/architecture.md#inside-the-app).
- `mcp-server/` — relay daemon + MCP adapter (TypeScript). See its [README](mcp-server/README.md).
- `deploy/` — relay setup, systemd unit, phone install script.
- `docs/` — setup, architecture & protocol, generated tool reference, design doc.

Tool names, input schemas and the wire protocol are a contract between the two halves: change them together.

## Dev setup

**MCP server** (Node 20+):

```bash
cd mcp-server
npm ci
npm test            # no phone needed; a mock phone is used
npm run typecheck
npm run docs:tools  # after changing the catalog
```

**App** (JDK 17+, Android SDK 35):

```bash
cd android
./gradlew :app:testDebugUnitTest   # JVM unit tests (ToolPolicy, PinPad, …)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/droidhand-debug.apk
../deploy/install-phone.sh         # install over adb and re-enable the accessibility service
```

Release builds are signed only when `android/keystore.properties` (git-ignored) points at a key; maintainers cut releases with `scripts/release.sh`.

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
