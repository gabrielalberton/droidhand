package work.alberton.droidhand.runtime

/**
 * Install recipes: plain shell typed into the terminal, so the user sees (and can
 * stop) everything. Each recipe is optional; nothing third-party is bundled.
 */
data class Recipe(val label: String, val script: String)

object Recipes {
    private const val DEPS = "apk add bash ca-certificates curl git libgcc libstdc++ ripgrep"

    val all = listOf(
        Recipe("Base", "apk update && $DEPS && echo '✔ base pronta'"),
        Recipe(
            "Claude Code",
            // Official installer; Alpine/musl is a supported platform. The user signs in with their own account.
            "$DEPS && curl -fsSL https://claude.ai/install.sh | bash && echo '✔ rode: claude'",
        ),
        Recipe(
            "Codex",
            "$DEPS && curl -fsSL https://github.com/openai/codex/releases/latest/download/codex-aarch64-unknown-linux-musl.tar.gz" +
                " | tar xz -C /usr/local/bin && mv -f /usr/local/bin/codex-aarch64-unknown-linux-musl /usr/local/bin/codex" +
                // No kernel sandbox (bwrap/landlock) under proot: the app's own Android sandbox is the boundary.
                " && mkdir -p ~/.codex && (grep -q '^sandbox_mode' ~/.codex/config.toml 2>/dev/null || echo 'sandbox_mode = \"danger-full-access\"' >> ~/.codex/config.toml)" +
                " && codex --version && echo '✔ rode: codex'",
        ),
        // T3 is glibc-only: the recipe adds a private glibc layer just for it (see the script).
        Recipe("T3 (opcional)", "$DEPS && sh /usr/local/share/droidhand/recipes/t3.sh"),
        Recipe("T3 serve", "mkdir -p ~/work && t3 serve --lan ~/work"),
        Recipe(
            "Atualizar",
            "apk update && apk upgrade && (command -v claude >/dev/null && claude update || true)" +
                " && (command -v t3 >/dev/null && sh /usr/local/share/droidhand/recipes/t3.sh || true)",
        ),
        Recipe("Espaço", "df -h /root | tail -1; du -sh /root/.local/share/claude /usr/local/bin/codex /opt/t3 /opt/glibc 2>/dev/null"),
    )
}
