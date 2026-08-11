package com.coxy.ghost.action

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the package-name validator shared by [SuExecutor] and [FrameworkExecutor].
 *
 * The bug these exist for: SuExecutor's copy of the regex was `"^[a-zA-Z0-9._]+\\$"`, where the
 * doubled backslash matches a *literal* dollar sign rather than end-of-input. Every real package
 * name was rejected, so `force_stop_app` / `disable_app` / `uninstall_app` could never run — and
 * nothing in the Phase 7 gate touched that path.
 */
@RunWith(AndroidJUnit4::class)
class PackageNamesTest {

    @Test
    fun accepts_real_package_names() {
        val valid = listOf(
            "com.spotify.music",
            "com.coxy.ghost",
            "com.google.android.as",
            "android",
            "com.example.app_2",
        )
        for (pkg in valid) {
            assertTrue("should accept '$pkg'", PackageNames.isValid(pkg))
            assertEquals(pkg, PackageNames.sanitize(pkg))
        }
    }

    @Test
    fun rejects_shell_metacharacters() {
        val invalid = listOf(
            "com.foo; rm -rf /",
            "com.foo && reboot",
            "com.foo\$(id)",
            "com.foo`id`",
            "com.foo | cat",
            "com.foo bar",
            "com.foo\nreboot",
            "",
            "   ",
        )
        for (pkg in invalid) {
            assertFalse("should reject '$pkg'", PackageNames.isValid(pkg))
            try {
                PackageNames.sanitize(pkg)
                fail("sanitize should have thrown for '$pkg'")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "message should name the offending input",
                    expected.message?.contains("invalid package name") == true
                )
            }
        }
    }

    @Test
    fun trailing_dollar_is_not_a_valid_name() {
        // The exact input the broken regex used to accept — and the only shape it accepted.
        assertFalse(PackageNames.isValid("com.spotify.music\$"))
    }

    @Test
    fun null_and_whitespace_are_rejected_and_trimmed() {
        assertFalse(PackageNames.isValid(null))
        assertEquals("com.coxy.ghost", PackageNames.sanitize("  com.coxy.ghost  "))
    }
}
