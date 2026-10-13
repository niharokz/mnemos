package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.vaultfs.NioVaultFs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime

/** Mirrors internal/vault/attach_test.go: the web app and the phone name and link images alike. */
class AttachmentsTest {
    private val now = LocalDateTime.of(2026, 10, 13, 9, 30, 5)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 0x1a, 10, 0, 0)

    @Test fun namesAreSafe() {
        mapOf(
            "My photo (1).JPEG" to "My-photo-1.jpg",
            "image.png" to "image-20261013-093005.jpg",
            "" to "image-20261013-093005.jpg",
            "../../etc/passwd" to "passwd.jpg",
            """C:\Users\me\cat.png""" to "cat.jpg",
            "नमस्ते.png" to "नमस्ते.jpg",
            ".hidden" to "image-20261013-093005.jpg",
        ).forEach { (input, want) -> assertEquals(input, want, Attachments.safeName(input, ".jpg", now)) }
    }

    @Test fun sniffsRealImagesOnly() {
        assertEquals("png", Attachments.imageExt(png))
        assertEquals("jpg", Attachments.imageExt(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("gif", Attachments.imageExt("GIF89a".toByteArray()))
        assertEquals("webp", Attachments.imageExt("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("avif", Attachments.imageExt("\u0000\u0000\u0000\u001cftypavif".toByteArray()))
        assertNull(Attachments.imageExt("<svg xmlns='http://www.w3.org/2000/svg'>".toByteArray()))
        assertNull(Attachments.imageExt("plain text".toByteArray()))
    }

    @Test fun savesNextToTheNoteWithoutOverwriting() {
        val root = Files.createTempDirectory("att")
        val fs = NioVaultFs(root)
        val first = Attachments.saveImage(fs, "journal/day.md", "My photo.JPEG", png, now)
        assertEquals("journal/attachments/My-photo.png", first)
        assertArrayEquals(png, Files.readAllBytes(root.resolve(first)))
        val second = Attachments.saveImage(fs, "journal/day.md", "My photo.png", png, now)
        assertEquals("journal/attachments/My-photo 2.png", second)
        assertEquals("![My-photo 2](attachments/My-photo%202.png)", Attachments.markdown("journal/day.md", second))
        assertThrows(IllegalArgumentException::class.java) { Attachments.saveImage(fs, "a.md", "x.png", "<svg>".toByteArray(), now) }
    }

    @Test fun linksAreRelativeToTheNote() {
        assertEquals("attachments/a.png", Attachments.relLink("day.md", "attachments/a.png"))
        assertEquals("attachments/a%20b.png", Attachments.relLink("notes/day.md", "notes/attachments/a b.png"))
        assertEquals("../../img/a.png", Attachments.relLink("notes/x/day.md", "img/a.png"))
    }
}
