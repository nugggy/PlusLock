package io.github.nugggy.pluslock.core

object Versions {
    /**
     * Turns a tag like "v1.2.3" or "v1.2.3-beta.4" into a sortable number.
     * Stable releases sort above any beta of the same version.
     * Keep in sync with versionCodeFor() in app/build.gradle.kts.
     */
    fun code(name: String): Long {
        val clean = name.trim().removePrefix("v")
        val core = clean.substringBefore("-")
        val pre = if (clean.contains("-")) clean.substringAfter("-") else null
        val nums = core.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        val preNum = if (pre == null) 99 else (Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0).coerceIn(0, 98)
        return nums[0] * 1_000_000L + nums[1].coerceAtMost(99) * 10_000L + nums[2].coerceAtMost(99) * 100L + preNum
    }
}
