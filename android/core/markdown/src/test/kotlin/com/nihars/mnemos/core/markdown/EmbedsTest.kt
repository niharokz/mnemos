package com.nihars.mnemos.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Obsidian embeds, read the same way as the web app (internal/render/embed.go). */
class EmbedsTest {
    @Test fun imagesSizesCaptionsAndNotes() {
        val out = Embeds.split(listOf(Inline.Text("a ![[Pasted image 2026.png]] b ![[p.jpg|300]] ![[p.jpg|a cap]] ![[Other note]]")))
        assertEquals(
            listOf(
                Inline.Text("a "),
                Inline.Image("Pasted image 2026.png", "Pasted image 2026", width = null, embed = true),
                Inline.Text(" b "),
                Inline.Image("p.jpg", "p", width = 300, embed = true),
                Inline.Text(" "),
                Inline.Image("p.jpg", "a cap", width = null, embed = true),
                Inline.Text(" "),
                Inline.Link("Other note.md", listOf(Inline.Text("Other note"))),
            ),
            out,
        )
    }

    @Test fun codeAndPlainTextStayAsTheyAre() {
        val list = listOf(Inline.Code("![[a.png]]"), Inline.Text("no embeds here [[x]]"))
        assertEquals(list, Embeds.split(list))
    }

    @Test fun parserTurnsEmbedsIntoImages() {
        val doc = MarkdownParser().parse("![[photo.png|240]]\n")
        val para = doc.blocks.single() as Block.Paragraph
        assertEquals(listOf(Inline.Image("photo.png", "photo", width = 240, embed = true)), para.inlines)
    }
}
