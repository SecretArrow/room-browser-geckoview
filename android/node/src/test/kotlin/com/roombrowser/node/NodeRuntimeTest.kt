package com.roombrowser.node

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class NodeRuntimeTest {

    @Test
    fun argv_names_the_program_then_the_script_then_the_runs_own_secrets() {
        val script = File("/data/user/0/com.roombrowser/files/node/bridge.js")
        val argv = NodeRuntime.argvFor(script, "roombrowser-node-1-1", "token")
        assertThat(argv.toList())
            .containsExactly(
                "node",
                "/data/user/0/com.roombrowser/files/node/bridge.js",
                "roombrowser-node-1-1",
                "token"
            ).inOrder()
    }

    @Test
    fun the_script_is_named_by_an_absolute_path_because_the_runtime_resolves_it() {
        val argv = NodeRuntime.argvFor(File("relative/bridge.js"), "socket", "token")
        assertThat(argv[1].startsWith("/")).isTrue()
    }
}
