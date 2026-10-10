package app.novelreader

import app.novelreader.platform.DesktopPlatform
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderScanTest {
    @Test
    fun `遞迴掃出 txt 與 epub，第一層子資料夾名當分類`() {
        val root = createTempDirectory("novelreader-folder-").toFile()
        try {
            File(root, "01武俠/金庸").mkdirs()
            File(root, "02同人").mkdirs()
            File(root, "01武俠/金庸/射鵰.txt").writeText("x")
            File(root, "02同人/a.epub").writeText("x")
            File(root, "02同人/skip.jpg").writeText("x")
            File(root, "root.TXT").writeText("x")

            val books = DesktopPlatform.listBooksInFolder(root)

            assertEquals(
                listOf("01武俠" to "射鵰.txt", "02同人" to "a.epub", null to "root.TXT"),
                books.map { it.folder to it.source.displayName },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
