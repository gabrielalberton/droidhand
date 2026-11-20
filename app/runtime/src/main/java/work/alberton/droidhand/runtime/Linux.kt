package work.alberton.droidhand.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The minimal Linux userland: an Alpine minirootfs run through proot (both
 * community maintained). Nothing else is bundled; tools come from recipes.
 */
class Linux(private val ctx: Context) {
    val rootfs = File(ctx.filesDir, "alpine")
    private val tmp = File(ctx.filesDir, "tmp")
    private val libDir = ctx.applicationInfo.nativeLibraryDir
    private val marker = File(rootfs, ".droidhand-installed")

    val installed: Boolean get() = marker.exists()

    /** Downloads, verifies and unpacks the rootfs. Blocking; [log] gets progress lines. */
    fun install(log: (String) -> Unit) {
        val archive = File(ctx.cacheDir, ALPINE_FILE)
        log("Baixando $ALPINE_FILE …")
        URL(ALPINE_URL).openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha == ALPINE_SHA256) { "SHA-256 inválido: $sha" }
        log("SHA-256 ok. Extraindo…")
        rootfs.deleteRecursively(); rootfs.mkdirs(); tmp.mkdirs()
        // Android's toybox tar keeps symlinks and permissions.
        val p = ProcessBuilder("/system/bin/tar", "-xzf", archive.path, "-C", rootfs.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "tar falhou: $out" }
        archive.delete()
        configure()
        marker.writeText(ALPINE_FILE)
        log("Alpine pronto (${ALPINE_FILE.removePrefix("alpine-minirootfs-").removeSuffix("-aarch64.tar.gz")}).")
    }

    /** DNS from the phone's active network (Android has no /etc/resolv.conf), hosts, profile. */
    fun configure() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        val servers = (dns + listOf("1.1.1.1", "8.8.8.8")).distinct()
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("") { "nameserver $it\n" })
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "etc/profile.d").mkdirs()
        File(rootfs, "etc/profile.d/droidhand.sh").writeText(
            """
            export PATH="${'$'}HOME/.local/bin:/usr/local/sbin:/usr/local/bin:${'$'}PATH"
            # Claude Code on musl uses the system ripgrep.
            export USE_BUILTIN_RIPGREP=0
            export PS1='\[\e[32m\]droidhand\[\e[0m\]:\w\$ '
            """.trimIndent() + "\n",
        )
        // Longer recipes live as readable scripts inside Alpine.
        val dir = File(rootfs, "usr/local/share/droidhand/recipes").apply { mkdirs() }
        ctx.assets.list("recipes")?.forEach { name ->
            ctx.assets.open("recipes/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
    }

    /** proot invocation that runs [command] (argv) inside Alpine. */
    fun prootArgs(command: List<String>): List<String> = listOf(
        "proot",
        "--kill-on-exit", "--link2symlink", "-0",
        "-r", rootfs.path,
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", "/dev/urandom:/dev/random",
        "-b", "/proc/self/fd:/dev/fd",
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "COLORTERM=truecolor",
        "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    ) + command

    val prootPath: String get() = "$libDir/libproot.so"

    /** Environment for the proot process itself (outside Alpine). */
    fun prootEnv(): Array<String> {
        tmp.mkdirs()
        return arrayOf(
            "LD_LIBRARY_PATH=$libDir",
            "PROOT_LOADER=$libDir/libproot-loader.so",
            "PROOT_TMP_DIR=${tmp.path}",
            "TERM=xterm-256color",
        )
    }

    companion object {
        const val ALPINE_FILE = "alpine-minirootfs-3.24.4-aarch64.tar.gz"
        const val ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/$ALPINE_FILE"
        const val ALPINE_SHA256 = "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de"
    }
}
