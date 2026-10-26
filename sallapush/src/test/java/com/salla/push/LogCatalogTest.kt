package com.salla.push

import com.salla.push.internal.log.LogCategory
import com.salla.push.internal.log.LogCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The SDK's codes are exactly the Android rows of the log catalogue (scripts/log-catalogue.txt), at the catalogued
 * levels.
 * That every catalogued code is also emitted by some test is checked by the verify<Variant>LogCatalog Gradle task,
 * over the codes the whole suite recorded.
 */
class LogCatalogTest {

    private val catalogue: Map<String, String> by lazy {
        val stream = LogCatalogTest::class.java.classLoader?.getResourceAsStream("log-catalog-android.txt") ?: throw AssertionError("missing catalogue")
        stream.bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
                val parts = line.split(' ')
                parts[0] to parts[1]
            }
        }
    }

    @Test
    fun test_everySdkCode_isCatalogued_atItsLevel() {
        for (code in LogCode.values()) {
            assertEquals("level of ${code.code}", catalogue[code.code], code.level.name.lowercase(Locale.ROOT))
        }
    }

    @Test
    fun test_everyCataloguedCode_existsInTheSdk() {
        assertEquals(catalogue.keys.toSortedSet(), LogCode.values().map { it.code }.toSortedSet())
    }

    @Test
    fun test_logoutSkipped_isCataloguedAtDebug_inTheIdentityCategory() {
        // A guest's launch logout is free and says so at DEBUG.
        assertEquals("debug", catalogue["logout.skipped"])
        assertEquals(SallaPushLogLevel.DEBUG, LogCode.LOGOUT_SKIPPED.level)
        assertEquals(LogCategory.IDENTITY, LogCode.LOGOUT_SKIPPED.category)
    }

    @Test
    fun test_codesAreUnique_wellFormed_andCategorised() {
        val codes = LogCode.values().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        val categories = setOf(
            LogCategory.LIFECYCLE, LogCategory.REGISTRATION, LogCategory.IDENTITY, LogCategory.NETWORK, LogCategory.TAGS,
            LogCategory.NOTIFICATIONS, LogCategory.EVENTS, LogCategory.STORAGE, LogCategory.CONSENT,
        )
        for (code in LogCode.values()) {
            assertTrue(code.code, code.code.matches(Regex("^[a-z]+\\.[a-z_]+$")))
            assertTrue("${code.code} has category ${code.category}", code.category in categories)
        }
    }
}
