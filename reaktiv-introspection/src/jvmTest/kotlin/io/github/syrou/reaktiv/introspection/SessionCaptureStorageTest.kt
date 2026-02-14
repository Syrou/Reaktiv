package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.CaptureStorage
import io.github.syrou.reaktiv.introspection.capture.FileCaptureStorage
import io.github.syrou.reaktiv.introspection.capture.InMemoryCaptureStorage
import io.github.syrou.reaktiv.introspection.capture.createCaptureStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionCaptureStorageTest {

    private val testDir = Path(SystemTemporaryDirectory, "reaktiv-introspection-test")
    private val storages = mutableListOf<CaptureStorage>()

    private fun fileStorage(name: String = "test"): FileCaptureStorage {
        if (!SystemFileSystem.exists(testDir)) {
            SystemFileSystem.createDirectories(testDir)
        }
        val storage = FileCaptureStorage(testDir, "$name.jsonl")
        storage.clear()
        storages.add(storage)
        return storage
    }

    private fun memoryStorage(): InMemoryCaptureStorage {
        val storage = InMemoryCaptureStorage()
        storages.add(storage)
        return storage
    }

    @AfterTest
    fun cleanup() {
        storages.forEach { it.delete() }
        storages.clear()
    }

    // --- FileCaptureStorage tests ---

    @Test
    fun `file storage appends and reads lines`() {
        val storage = fileStorage()

        storage.appendLine("""{"action":"A1"}""")
        storage.appendLine("""{"action":"A2"}""")
        storage.appendLine("""{"action":"A3"}""")

        val lines = storage.readLines()
        assertEquals(3, lines.size)
        assertEquals("""{"action":"A1"}""", lines[0])
        assertEquals("""{"action":"A2"}""", lines[1])
        assertEquals("""{"action":"A3"}""", lines[2])
    }

    @Test
    fun `file storage tracks line count`() {
        val storage = fileStorage()

        assertEquals(0, storage.lineCount())
        storage.appendLine("line1")
        assertEquals(1, storage.lineCount())
        storage.appendLine("line2")
        storage.appendLine("line3")
        assertEquals(3, storage.lineCount())
    }

    @Test
    fun `file storage clear removes all data`() {
        val storage = fileStorage()

        storage.appendLine("line1")
        storage.appendLine("line2")
        storage.clear()

        assertEquals(0, storage.lineCount())
        assertEquals(emptyList(), storage.readLines())
    }

    @Test
    fun `file storage trimTo keeps most recent lines`() {
        val storage = fileStorage()

        for (i in 1..10) {
            storage.appendLine("line$i")
        }
        assertEquals(10, storage.lineCount())

        storage.trimTo(3)

        assertEquals(3, storage.lineCount())
        val lines = storage.readLines()
        assertEquals(3, lines.size)
        assertEquals("line8", lines[0])
        assertEquals("line9", lines[1])
        assertEquals("line10", lines[2])
    }

    @Test
    fun `file storage trimTo is no-op when count within limit`() {
        val storage = fileStorage()

        storage.appendLine("line1")
        storage.appendLine("line2")

        storage.trimTo(5)

        assertEquals(2, storage.lineCount())
        assertEquals(listOf("line1", "line2"), storage.readLines())
    }

    @Test
    fun `file storage readLines returns empty when no file exists`() {
        val storage = fileStorage("nonexistent")
        assertEquals(emptyList(), storage.readLines())
    }

    @Test
    fun `file storage handles JSON content with special characters`() {
        val storage = fileStorage()

        val jsonLine = """{"key":"value with \"quotes\" and \\backslashes","num":42}"""
        storage.appendLine(jsonLine)

        val lines = storage.readLines()
        assertEquals(1, lines.size)
        assertEquals(jsonLine, lines[0])
    }

    @Test
    fun `file storage delete cleans up`() {
        val storage = fileStorage()

        storage.appendLine("data")
        storage.delete()

        assertEquals(0, storage.lineCount())
        assertEquals(emptyList(), storage.readLines())
    }

    @Test
    fun `file storage multiple instances use separate files`() {
        val storage1 = fileStorage("file1")
        val storage2 = fileStorage("file2")

        storage1.appendLine("from-storage1")
        storage2.appendLine("from-storage2-a")
        storage2.appendLine("from-storage2-b")

        assertEquals(1, storage1.lineCount())
        assertEquals(2, storage2.lineCount())
        assertEquals(listOf("from-storage1"), storage1.readLines())
        assertEquals(listOf("from-storage2-a", "from-storage2-b"), storage2.readLines())
    }

    // --- InMemoryCaptureStorage tests ---

    @Test
    fun `memory storage appends and reads lines`() {
        val storage = memoryStorage()

        storage.appendLine("line1")
        storage.appendLine("line2")

        assertEquals(listOf("line1", "line2"), storage.readLines())
    }

    @Test
    fun `memory storage tracks line count`() {
        val storage = memoryStorage()

        assertEquals(0, storage.lineCount())
        storage.appendLine("a")
        storage.appendLine("b")
        assertEquals(2, storage.lineCount())
    }

    @Test
    fun `memory storage clear removes all data`() {
        val storage = memoryStorage()

        storage.appendLine("data")
        storage.clear()

        assertEquals(0, storage.lineCount())
        assertEquals(emptyList(), storage.readLines())
    }

    @Test
    fun `memory storage trimTo keeps most recent lines`() {
        val storage = memoryStorage()

        for (i in 1..10) {
            storage.appendLine("line$i")
        }

        storage.trimTo(3)

        assertEquals(3, storage.lineCount())
        val lines = storage.readLines()
        assertEquals("line8", lines[0])
        assertEquals("line9", lines[1])
        assertEquals("line10", lines[2])
    }

    @Test
    fun `memory storage trimTo is no-op when count within limit`() {
        val storage = memoryStorage()

        storage.appendLine("a")
        storage.appendLine("b")
        storage.trimTo(5)

        assertEquals(2, storage.lineCount())
    }

    // --- createCaptureStorage factory tests ---

    @Test
    fun `createCaptureStorage returns working storage`() {
        val storage = createCaptureStorage("factory-test-${kotlin.random.Random.nextInt()}")
        storages.add(storage)

        storage.appendLine("test-line-1")
        storage.appendLine("test-line-2")

        assertEquals(2, storage.lineCount())
        val lines = storage.readLines()
        assertEquals(2, lines.size)
        assertEquals("test-line-1", lines[0])
        assertEquals("test-line-2", lines[1])

        storage.clear()
        assertEquals(0, storage.lineCount())
    }

    @Test
    fun `createCaptureStorage starts empty`() {
        val storage = createCaptureStorage("empty-test-${kotlin.random.Random.nextInt()}")
        storages.add(storage)

        assertEquals(0, storage.lineCount())
        assertEquals(emptyList(), storage.readLines())
    }

    // --- Behavioral contract tests (both implementations) ---

    @Test
    fun `file storage preserves insertion order`() {
        val storage = fileStorage("order-test")

        val expected = (1..50).map { """{"index":$it}""" }
        expected.forEach { storage.appendLine(it) }

        assertEquals(expected, storage.readLines())
    }

    @Test
    fun `memory storage preserves insertion order`() {
        val storage = memoryStorage()

        val expected = (1..50).map { """{"index":$it}""" }
        expected.forEach { storage.appendLine(it) }

        assertEquals(expected, storage.readLines())
    }

    @Test
    fun `file storage clear then reuse works`() {
        val storage = fileStorage("reuse-test")

        storage.appendLine("round1-a")
        storage.appendLine("round1-b")
        storage.clear()

        storage.appendLine("round2-a")
        assertEquals(1, storage.lineCount())
        assertEquals(listOf("round2-a"), storage.readLines())
    }

    @Test
    fun `memory storage clear then reuse works`() {
        val storage = memoryStorage()

        storage.appendLine("round1-a")
        storage.appendLine("round1-b")
        storage.clear()

        storage.appendLine("round2-a")
        assertEquals(1, storage.lineCount())
        assertEquals(listOf("round2-a"), storage.readLines())
    }

    @Test
    fun `file storage trimTo with large dataset keeps correct tail`() {
        val storage = fileStorage("large-trim")

        for (i in 1..500) {
            storage.appendLine("event-$i")
        }
        assertEquals(500, storage.lineCount())

        storage.trimTo(10)

        assertEquals(10, storage.lineCount())
        val lines = storage.readLines()
        assertEquals(10, lines.size)
        assertEquals("event-491", lines[0])
        assertEquals("event-500", lines[9])
    }

    @Test
    fun `file storage handles realistic JSONL captured action data`() {
        val storage = fileStorage("realistic")

        val actionJson1 = """{"clientId":"client-1","timestamp":1700000001,"actionType":"Increment","actionData":"CounterAction.Increment","stateDeltaJson":"{\"count\":1}","moduleName":"CounterModule"}"""
        val actionJson2 = """{"clientId":"client-1","timestamp":1700000002,"actionType":"Navigate","actionData":"NavigationAction.Navigate(route=/home)","stateDeltaJson":"{\"currentRoute\":\"/home\",\"backStack\":[]}","moduleName":"NavigationModule"}"""

        storage.appendLine(actionJson1)
        storage.appendLine(actionJson2)

        val lines = storage.readLines()
        assertEquals(2, lines.size)
        assertEquals(actionJson1, lines[0])
        assertEquals(actionJson2, lines[1])

        assertTrue(lines[0].contains("\"moduleName\":\"CounterModule\""))
        assertTrue(lines[1].contains("\"moduleName\":\"NavigationModule\""))
    }
}
