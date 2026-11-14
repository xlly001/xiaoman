package dev.xiaoman.skills

import dev.xiaoman.api.SkillOrigin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserSkillStoreTest {
    private val tmp: File = Files.createTempDirectory("user-skill-store-test").toFile()
    private val bundled = File(tmp, "bundled")
    private val user = File(tmp, "user")
    private val store = FileUserSkillStore(bundled, user)

    @BeforeTest
    fun resetRoots() {
        // JUnit does not guarantee method order; keep every test independent of earlier folders.
        tmp.listFiles()?.forEach { it.deleteRecursively() }
    }

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun skillMd(name: String, description: String = "描述") =
        "---\nname: $name\ndescription: $description\n---\n说明 $name\n"

    private fun writeBundled(name: String, description: String = "内置描述") {
        val dir = File(bundled, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(skillMd(name, description))
    }

    @Test
    fun savesReadsAndDeletesUserSkill() {
        assertNull(store.read("taxi-home"))
        val path = store.save("taxi-home", skillMd("taxi-home"))
        assertEquals(File(user.canonicalFile, "taxi-home/SKILL.md"), path)
        assertTrue(path.isFile)
        // No temp files are left behind.
        assertEquals(listOf("SKILL.md"), File(user, "taxi-home").listFiles()!!.map { it.name })

        val stored = store.read("taxi-home")!!
        assertEquals("taxi-home", stored.name)
        assertEquals(skillMd("taxi-home"), stored.skillMd)
        assertEquals(SkillOrigin.USER, stored.origin)
        assertFalse(stored.hasFlow)
        assertTrue(store.isUser("taxi-home"))
        assertFalse(store.isBundled("taxi-home"))

        assertTrue(store.delete("taxi-home"))
        assertFalse(File(user, "taxi-home").exists())
        assertNull(store.read("taxi-home"))
        assertFalse(store.delete("taxi-home"))
    }

    @Test
    fun saveKeepsExistingFlowYaml() {
        val dir = File(user, "set-alarm").apply { mkdirs() }
        File(dir, "flow.yaml").writeText("steps:\n  - wait: 1000\n")
        store.save("set-alarm", skillMd("set-alarm"))
        assertEquals("steps:\n  - wait: 1000\n", File(dir, "flow.yaml").readText())
        assertTrue(store.read("set-alarm")!!.hasFlow)
    }

    @Test
    fun readFallsBackToBundledAndUserVersionWins() {
        writeBundled("taxi-home", "内置")
        val bundledSkill = store.read("taxi-home")!!
        assertEquals(SkillOrigin.BUNDLED, bundledSkill.origin)
        assertTrue(store.isBundled("taxi-home"))
        assertFalse(store.isUser("taxi-home"))

        store.save("taxi-home", skillMd("taxi-home", "用户"))
        val overridden = store.read("taxi-home")!!
        assertEquals(SkillOrigin.USER, overridden.origin)
        assertTrue("用户" in overridden.skillMd)
        // The bundled file stays untouched.
        assertTrue("内置" in File(bundled, "taxi-home/SKILL.md").readText())
    }

    @Test
    fun deleteOnlyRemovesTheUserFolder() {
        writeBundled("taxi-home")
        store.save("taxi-home", skillMd("taxi-home"))
        assertTrue(store.delete("taxi-home"))
        assertTrue(File(bundled, "taxi-home/SKILL.md").isFile)
        assertEquals(SkillOrigin.BUNDLED, store.read("taxi-home")!!.origin)
        // Nothing user-side left to delete.
        assertFalse(store.delete("taxi-home"))
    }

    @Test
    fun invalidNamesAreRejectedBeforeAnyFileAccess() {
        val bad = listOf("a", "Abc", "x".repeat(41), "ab/cd", "..", "a.b", "中文技能", "a b")
        for (name in bad) {
            assertFailsWith<IllegalArgumentException> { store.save(name, skillMd("ab")) }
            assertFailsWith<IllegalArgumentException> { store.read(name) }
            assertFailsWith<IllegalArgumentException> { store.delete(name) }
        }
        // Rejected names never created a folder (and never escaped either root).
        assertTrue(user.listFiles()?.none { it.name in bad } ?: true)
    }

    @Test
    fun validateAcceptsGoodMarkdownAndReportsProblems() {
        val (skill, problems) = store.validate("taxi-home", skillMd("taxi-home"))
        assertEquals(emptyList(), problems)
        assertEquals("taxi-home", skill?.name)
        assertEquals(File(user.canonicalFile, "taxi-home").path, skill?.source)

        val broken = "---\nname: taxi-home\ndescription: d\nchannel: nope\n---\n"
        val (noSkill, brokenProblems) = store.validate("taxi-home", broken)
        assertNull(noSkill)
        assertTrue(brokenProblems.isNotEmpty())
    }

    @Test
    fun repositoryLoadsUserVersionOverBundledAfterSave() = runTest {
        writeBundled("taxi-home", "内置")
        val repo = DirectorySkillRepository(listOf(bundled, user), StandardTestDispatcher(testScheduler))
        assertEquals("内置", repo.reload().skills.single { it.name == "taxi-home" }.description)

        store.save("taxi-home", skillMd("taxi-home", "用户"))
        val reloaded = repo.reload()
        val taxi = reloaded.skills.single { it.name == "taxi-home" }
        assertEquals("用户", taxi.description)
        assertTrue(taxi.source.startsWith(user.path))

        store.delete("taxi-home")
        val restored = repo.reload().skills.single { it.name == "taxi-home" }
        assertEquals("内置", restored.description)
        assertTrue(restored.source.startsWith(bundled.path))
    }
}
