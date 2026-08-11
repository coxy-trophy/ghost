package com.coxy.ghost.action

/**
 * Single source of truth for package-name validation, shared by [SuExecutor] and [FrameworkExecutor].
 *
 * This is a shell-injection boundary: [SuExecutor] interpolates the result straight into a
 * `su -c` command line, so anything outside [PATTERN] has to be rejected before it gets there.
 *
 * It is shared rather than duplicated because it used to be two private copies that drifted:
 * SuExecutor's had `Regex("^[a-zA-Z0-9._]+\\$")`, where the doubled backslash makes `\$` match a
 * *literal* dollar sign instead of end-of-input. That copy rejected every real package name, so
 * `force_stop_app` / `disable_app` / `uninstall_app` always failed with "invalid package name".
 */
object PackageNames {

    /** Package names are dot-separated `[a-zA-Z0-9_]` segments. Anchored at both ends. */
    private val PATTERN = Regex("^[a-zA-Z0-9._]+\$")

    fun isValid(pkg: String?): Boolean = pkg?.trim()?.matches(PATTERN) == true

    /**
     * @return the trimmed package name.
     * @throws IllegalArgumentException if [pkg] is not a valid package name.
     */
    fun sanitize(pkg: String?): String {
        val raw = pkg?.trim() ?: ""
        require(isValid(raw)) { "invalid package name '$raw'" }
        return raw
    }
}
