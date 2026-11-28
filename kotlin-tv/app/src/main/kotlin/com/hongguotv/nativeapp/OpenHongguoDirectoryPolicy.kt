// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.text.Normalizer
import java.util.Locale

/** Fail-closed rules for mapping a Hongguo title to an OpenHongguo episode directory. */
internal object OpenHongguoDirectoryPolicy {
    fun normalizeTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    /** Extract a total only when the original catalog badge explicitly states an episode count. */
    fun expectedCountFromBadge(badge: String): Int? =
        Regex("""(?<!\d)(\d{1,5})\s*集""")
            .find(badge)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..10000 }

    fun isCompleteDirectory(expectedCount: Int, episodes: List<Pair<Int, String>>): Boolean =
        expectedCount in 1..10000 &&
            episodes.size == expectedCount &&
            episodes.withIndex().all { (index, episode) ->
                episode.first == index + 1 && episode.second.matches(Regex("[0-9]{1,30}"))
            } &&
            episodes.map { it.second }.distinct().size == episodes.size
}
