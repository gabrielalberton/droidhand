# Security Policy

DroidHand can fully control a personal phone. We take its security posture seriously.

## Reporting a vulnerability

Please report privately — **do not open a public issue** for security bugs.

- Open a [GitHub Security Advisory](../../security/advisories/new) (preferred), or
- Contact the maintainer through the repository's listed private channel.

Include: affected component (app / relay / mcp-server), version/commit, reproduction, and impact. We aim to acknowledge within a few days.

## Scope & design guarantees

- **Outbound-only phone.** The Portal app never opens an inbound port; it dials out to the relay. Reachable only over the operator's Tailscale network.
- **Authenticated + encrypted.** Bearer token + TLS; the relay binds to the Tailscale interface only, never `0.0.0.0`.
- **Defense in depth.** Authorization tiers (standard / privileged / sensitive) enforced both server-side and on-device.
- **Confirmation gates.** Sensitive and destructive actions require an on-device tap; the agent alone cannot authorize them (prompt-injection resistance).
- **Kill switch.** Persistent notification button + Quick Settings tile + `panic_disconnect` end the session and any ADB window immediately.
- **Stop button.** A small floating red button, visible while the agent acts, cancels the current action and tells the agent (`stopped_by_user`) without disconnecting.
- **Minimize exposure.** Debugging is off except during short, audited ADB-on-demand windows (default TTL 120s), always closed in a `finally`.
- **Audit.** Append-only logs on both NUC and phone; secrets redacted.
- **No telemetry. No cloud by default.**

## Out of scope / non-goals

- No root. No defeating an MDM/work-profile container or a device administrator's visibility on a device they administer (see PRD §2.1).
- Secrets (token, ADB key) are never committed; operators must keep them `chmod 600`, outside git.

## Operator responsibilities

- Run only against a device you own.
- Keep the token and ADB key private.
- Comply with any MDM/employer policy applicable to your device.
- Review the audit log; use confirmation gates for money/credential/wipe categories.
