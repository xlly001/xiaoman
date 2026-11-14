package dev.xiaoman.skills

import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillOrigin
import dev.xiaoman.api.SkillProblem
import dev.xiaoman.api.StoredSkill
import dev.xiaoman.api.UserSkillStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

class FileUserSkillStore(private val bundledRoot: File, private val userRoot: File) : UserSkillStore {
    private fun folder(root: File, name: String): File {
        require(NAME.matches(name)) { "技能名必须匹配 ^[a-z0-9-]{2,40}$" }
        val expected = File(root.canonicalFile, name)
        require(expected.canonicalFile == expected) { "技能目录不能指向用户目录之外" }
        return expected
    }

    private fun skillFile(root: File, name: String): File {
        val file = File(folder(root, name), "SKILL.md")
        require(file.canonicalFile == file) { "SKILL.md 不能是符号链接" }
        return file
    }

    @Synchronized
    override fun read(name: String): StoredSkill? {
        val user = skillFile(userRoot, name)
        val file = if (user.isFile) user else skillFile(bundledRoot, name)
        if (!file.isFile) return null
        return StoredSkill(name, file.readText(), if (file == user) SkillOrigin.USER else SkillOrigin.BUNDLED,
            File(file.parentFile, "flow.yaml").isFile)
    }

    @Synchronized
    override fun save(name: String, skillMd: String): File {
        val target = skillFile(userRoot, name)
        val directory = target.parentFile
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建技能目录")
        val temporary = File.createTempFile(".SKILL-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(skillMd.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("无法保存技能文件")
        } finally {
            temporary.delete()
        }
        return target
    }

    @Synchronized
    override fun delete(name: String): Boolean {
        val directory = folder(userRoot, name)
        if (!directory.exists()) return false
        // walkFileTree does not follow links, including links inside a user's skill folder.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return true
    }

    @Synchronized
    override fun isUser(name: String): Boolean = skillFile(userRoot, name).isFile

    @Synchronized
    override fun isBundled(name: String): Boolean = skillFile(bundledRoot, name).isFile

    override fun validate(name: String, skillMd: String): Pair<Skill?, List<SkillProblem>> =
        SkillParser.parseFolder(name, skillMd, null, folder(userRoot, name).path)

    companion object {
        private val NAME = Regex("^[a-z0-9-]{2,40}$")
    }
}
