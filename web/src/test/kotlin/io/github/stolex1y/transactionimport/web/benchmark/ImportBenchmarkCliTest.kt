package io.github.stolex1y.transactionimport.web.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportBenchmarkCliTest {
    @Test
    fun codexModeRequiresTheExplicitEgressFlag() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=codex"))
        }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkOptions.parse(arrayOf("--mode=offline", "--allow-codex-egress"))
        }

        val options = BenchmarkOptions.parse(arrayOf("--mode=codex", "--allow-codex-egress"))
        assertEquals(BenchmarkMode.CODEX, options.mode)
        assertTrue(options.allowCodexEgress)
    }

    @Test
    fun nonNetworkModesCannotWriteOrEnableCodexEgress() {
        listOf("inspect", "preflight").forEach { mode ->
            assertFailsWith<IllegalArgumentException> {
                BenchmarkOptions.parse(arrayOf("--mode=$mode", "--report=report.json"))
            }
        }
        assertFalse(BenchmarkOptions.parse(arrayOf("--mode=offline")).allowCodexEgress)
    }
}
