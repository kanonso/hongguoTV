// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenHongguoDirectoryPolicyTest {
    @Test
    fun titleNormalizationOnlyRemovesPresentationDifferences() {
        assertEquals(
            OpenHongguoDirectoryPolicy.normalizeTitle(" 咱家剑宗团宠小师妹 第六季 "),
            OpenHongguoDirectoryPolicy.normalizeTitle("咱家剑宗团宠小师妹第六季"),
        )
        assertFalse(
            OpenHongguoDirectoryPolicy.normalizeTitle("咱家剑宗团宠小师妹第六季") ==
                OpenHongguoDirectoryPolicy.normalizeTitle("咱家剑宗团宠小师妹第五季"),
        )
    }

    @Test
    fun expectedCountRequiresAnExplicitEpisodeBadge() {
        assertEquals(154, OpenHongguoDirectoryPolicy.expectedCountFromBadge("全 154 集"))
        assertEquals(12, OpenHongguoDirectoryPolicy.expectedCountFromBadge("更新至第12集"))
        assertNull(OpenHongguoDirectoryPolicy.expectedCountFromBadge("玄幻 · 仙侠"))
    }

    @Test
    fun onlyACompleteOrderedUniqueDirectoryCanBeUsedAsFallback() {
        val episodes = (1..3).map { it to (1000 + it).toString() }
        assertTrue(OpenHongguoDirectoryPolicy.isCompleteDirectory(3, episodes))
        assertFalse(OpenHongguoDirectoryPolicy.isCompleteDirectory(4, episodes))
        assertFalse(OpenHongguoDirectoryPolicy.isCompleteDirectory(3, listOf(1 to "1", 3 to "3", 2 to "2")))
        assertFalse(OpenHongguoDirectoryPolicy.isCompleteDirectory(3, listOf(1 to "1", 2 to "1", 3 to "3")))
        assertFalse(OpenHongguoDirectoryPolicy.isCompleteDirectory(3, listOf(1 to "1", 2 to "2", 3 to "bad")))
    }
}
