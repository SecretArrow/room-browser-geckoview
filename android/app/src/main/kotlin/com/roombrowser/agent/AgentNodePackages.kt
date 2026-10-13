package com.roombrowser.agent

import android.content.Context
import com.roombrowser.domain.agent.ToolResult
import com.roombrowser.domain.agent.UntrustedContent
import com.roombrowser.node.NodeRuntime
import com.roombrowser.node.packages.HttpRegistry
import com.roombrowser.node.packages.InstallReport
import com.roombrowser.node.packages.NodePackageStore
import com.roombrowser.node.packages.Registry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `node_packages`: the npm packages installed beside the embedded Node runtime,
 * which `node_run` then `require`s.
 *
 * The store is app-wide rather than per profile — it is the one directory the
 * runtime resolves `require` from — so no profile is taken here at all; the
 * profile is the tool's caller, not the store's scope.
 *
 * Installing is the only action that reaches the network, and it goes to the
 * public npm registry alone (see [HttpRegistry]). Listing and removing only
 * touch the directory: neither starts the runtime, so a chat that never runs a
 * script pays for nothing.
 *
 * Everything a package or the registry supplies — its name, version and
 * dependencies, and what was skipped — is fenced as data by [UntrustedContent],
 * exactly as `node_run`'s output is.
 */
class AgentNodePackages(
    context: Context,
    registry: Registry = HttpRegistry()
) {

    private val directory: File = NodeRuntime.directory(context)
    private val store = NodePackageStore(directory, registry)

    /** What is installed, and whether this build can run it at all. No runtime is started. */
    suspend fun list(): ToolResult = withContext(Dispatchers.IO) {
        val packages = store.installed()
        val runtime = if (NodeRuntime.available) {
            ""
        } else {
            "This build carries no Node runtime for this device's CPU, so none of these can be " +
                "used here.\n"
        }
        val body = if (packages.isEmpty()) {
            "No npm packages are installed beside the Node runtime. Use action=install to add one."
        } else {
            "${packages.size} package(s) installed beside the Node runtime:\n" +
                packages.joinToString("\n") { "  ${it.name}@${it.version ?: "(version unreadable)"}" }
        }
        ToolResult(true, UntrustedContent.wrap("$runtime$body\nThey live in ${directory.absolutePath}.", SOURCE))
    }

    suspend fun install(name: String, spec: String?): ToolResult = withContext(Dispatchers.IO) {
        val report = store.install(name, spec)
        if (!report.ok) return@withContext ToolResult(false, report.error ?: "the install failed")
        ToolResult(true, UntrustedContent.wrap(summary(report), SOURCE))
    }

    suspend fun remove(name: String): ToolResult = withContext(Dispatchers.IO) {
        if (!store.remove(name)) {
            return@withContext ToolResult(false, "nothing was removed: no valid package named \"$name\" is installed")
        }
        ToolResult(true, "Removed \"$name\". It is no longer available to node_run.")
    }

    private fun summary(report: InstallReport): String {
        val installed = report.installed
            .joinToString("\n") { "  ${it.name}@${it.version ?: "(version unreadable)"}" }
        val skipped = if (report.skipped.isEmpty()) {
            ""
        } else {
            "\nNot installed:\n" + report.skipped.joinToString("\n") { "  $it" }
        }
        return "Installed ${report.name}@${report.version ?: "(unknown version)"}. " +
            "${report.installed.size} package(s) are now available to node_run:\n$installed$skipped"
    }

    private companion object {
        /** What a package's own manifest and the registry supply: data, not instruction. */
        const val SOURCE = "the npm registry"
    }
}
