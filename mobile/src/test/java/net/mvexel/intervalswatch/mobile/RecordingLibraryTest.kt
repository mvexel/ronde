package net.mvexel.intervalswatch.mobile

import net.mvexel.intervalswatch.shared.RecordingProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun lib(keep: Int = 30) = RecordingLibrary(tmp.root, keep)
    private fun add(sec: Long, name: String = "Tempo") =
        RecordingProtocol.fileName(sec * 1000, name).also { tmp.newFile(it).writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun listsNewestFirstWithStates() {
        val l = lib()
        val a = add(100); val b = add(200); val c = add(300)
        l.markUploaded(a); l.markFailed(b, "bad file\nmore")
        val r = l.recent()
        assertEquals(listOf(c, b, a), r.map { it.fileName })
        assertEquals(listOf(UploadState.PENDING, UploadState.FAILED, UploadState.UPLOADED), r.map { it.state })
        assertEquals("bad file", r[1].reason)
        assertEquals("Tempo", r[0].title)
        assertEquals(300L, r[0].startEpochSec)
    }

    @Test fun pruneKeepsNewestAndNeverPending() {
        val l = lib(keep = 1)
        val old = add(100); val mid = add(200); val pend = add(50)
        l.markUploaded(old); l.markUploaded(mid)
        l.prune()
        assertEquals(setOf(mid, pend), l.recent().map { it.fileName }.toSet())
        assertFalse(l.file(old).exists())
        assertTrue(l.recent().first { it.fileName == pend }.state == UploadState.PENDING)
    }

    @Test fun fitExportIsTheOriginalBytes() {
        val f = add(100)
        assertEquals(listOf<Byte>(1, 2, 3), lib().export(f, ExportFormat.FIT).toList())
    }

    @Test fun exportNameIsFileSafe() {
        val f = add(1_700_000_000, "Hills 6x/400m")
        val n = lib().exportName(f, ExportFormat.GPX)
        assertTrue(n, Regex("Hills_6x_400m_\\d{4}-\\d{2}-\\d{2}\\.gpx").matches(n))
    }
}
