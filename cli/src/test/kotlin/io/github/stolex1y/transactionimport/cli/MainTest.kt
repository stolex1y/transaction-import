package io.github.stolex1y.transactionimport.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {
    @Test
    fun readsOnlyNonBlankEnvironmentValue() {
        assertEquals("key", readApiKey(mapOf(API_KEY_ENV to "key")))
        assertNull(readApiKey(emptyMap()))
        assertNull(readApiKey(mapOf(API_KEY_ENV to "  ")))
    }
}
