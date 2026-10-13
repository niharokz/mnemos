package com.nihars.mnemos.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {
    @Test fun ranksPrefixThenSubstringThenSubsequence() {
        val prefix = Fuzzy.score("dai", "daily.md")
        val inside = Fuzzy.score("ily", "daily.md")
        val subseq = Fuzzy.score("dly", "daily.md")
        assertTrue(prefix > inside && inside > subseq && subseq > 0)
        assertEquals(0, Fuzzy.score("xyz", "daily.md"))
    }
}
