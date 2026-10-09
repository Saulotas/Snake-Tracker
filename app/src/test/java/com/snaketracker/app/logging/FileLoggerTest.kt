package com.snaketracker.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLoggerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.root, "logs/snake_tracker_debug.log")
        FileLogger.initForFile(file)
    }

    @Test
    fun writesTimestampedLevelTagAndMessage_inOrder() {
        FileLogger.i("Notify", "first")
        FileLogger.w("Scheduler", "second")

        val lines = FileLogger.readAll().lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        assertTrue(lines[0].matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I/Notify: first""")))
        assertTrue(lines[1].endsWith("W/Scheduler: second"))
    }

    @Test
    fun rotatesToABackup_whenTheFileOutgrowsTheCap_andReadAllKeepsBoth() {
        val filler = "x".repeat(1024)
        repeat(((FileLogger.MAX_BYTES / 1024) + 20).toInt()) { FileLogger.d("Fill", filler) }
        FileLogger.i("After", "post-rotation line")

        FileLogger.flush()
        assertTrue(File(file.parentFile, "snake_tracker_debug.1.log").exists())
        val all = FileLogger.readAll()
        assertTrue(all.contains("Fill"))
        assertTrue(all.contains("post-rotation line"))
    }

    @Test
    fun clear_removesTheLogAndItsBackup() {
        FileLogger.i("Tag", "something")
        FileLogger.flush()
        assertTrue(file.exists())

        FileLogger.clear()
        FileLogger.flush()

        assertFalse(file.exists())
        assertEquals("", FileLogger.readAll())
    }
}
