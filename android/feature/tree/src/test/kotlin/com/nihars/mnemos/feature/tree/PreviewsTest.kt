package com.nihars.mnemos.feature.tree

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewsTest {
    @Test fun skipsFrontMatterAndMarkers() {
        val md = "---\ntags: [a]\n---\n# Title\n\n- [ ] **task** one\n> quote\n1. first\n```\ncode\n"
        assertEquals("Title\ntask one\nquote\nfirst\ncode", Previews.text(md))
    }
}
