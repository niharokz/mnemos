package com.nihars.mnemos.core.data

import com.nihars.mnemos.core.vaultfs.NioVaultFs
import com.nihars.mnemos.core.vaultfs.SaveCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime

class CaptureTest {
    private val now = LocalDateTime.of(2026, 10, 13, 8, 5)

    @Test fun formatsEntryAndIndentsExtraLines() {
        assertEquals("- 2026-10-13 08:05 buy milk", Capture.entry("- {date} {time} {text}", "buy milk", now))
        assertEquals("- 2026-10-13 08:05 first\n  second", Capture.entry("- {date} {time} {text}", " first\nsecond ", now))
    }

    @Test fun appendsCreatingFolderAndKeepingCrlf() = runBlocking {
        val root = Files.createTempDirectory("cap")
        val fs = NioVaultFs(root)
        val settings = AppSettings(captureNote = "inbox/note.md")
        Capture.append(fs, SaveCoordinator(fs), settings, "one", now)
        assertEquals("- 2026-10-13 08:05 one\n", String(Files.readAllBytes(root.resolve("inbox/note.md"))))
        Files.write(root.resolve("inbox/note.md"), "# Inbox\r\n- old".toByteArray())
        Capture.append(fs, SaveCoordinator(fs), settings, "two", now)
        assertEquals("# Inbox\r\n- old\r\n- 2026-10-13 08:05 two\r\n", String(Files.readAllBytes(root.resolve("inbox/note.md"))))
    }

    @Test fun sharedFilesGoToAttachmentsAndGetLinked() = runBlocking {
        val root = Files.createTempDirectory("share")
        val fs = NioVaultFs(root)
        val rel = Capture.saveAttachment(fs, "inbox/shared.md", "photo 1.jpg", byteArrayOf(1, 2, 3))
        assertEquals("attachments/photo 1.jpg", rel)
        assertEquals("attachments/photo 1 2.jpg", Capture.saveAttachment(fs, "inbox/shared.md", "photo 1.jpg", byteArrayOf(4)))
        val line = Capture.attachmentEntry("- {date} {time} {text}", rel, "photo 1.jpg", isImage = true, now = now)
        assertEquals("- 2026-10-13 08:05 ![photo 1.jpg](attachments/photo%201.jpg)", line)
        Capture.appendTo(fs, SaveCoordinator(fs), "inbox/shared.md", line)
        assertEquals(line + "\n", String(Files.readAllBytes(root.resolve("inbox/shared.md"))))
    }

    @Test fun relativeTimesAndSizes() {
        assertEquals("just now", Format.relative(1_000, 1_500))
        assertEquals("5 min ago", Format.relative(1, 5 * 60_000L + 1))
        assertEquals("1.5 KB", Format.size(1536))
    }
}
