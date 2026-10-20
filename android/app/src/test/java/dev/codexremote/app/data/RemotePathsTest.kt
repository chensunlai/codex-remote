package dev.codexremote.app.data

import org.junit.Assert.*
import org.junit.Test

class RemotePathsTest {
    @Test fun acceptsOnlyAbsoluteRemotePaths() {
        listOf("/", "/home/项目", "C:\\", "D:/Projects/项目", "\\\\server\\share", "//server/share/folder").forEach {
            assertTrue(it, isRemoteAbsolutePath(it))
        }
        listOf("", "project", "C:project", "\\project", "\\\\server", "//server", "/home/\u0000project").forEach {
            assertFalse(it, isRemoteAbsolutePath(it))
        }
    }

    @Test fun parentsAndBreadcrumbsStopAtTheCorrectRemoteRoot() {
        assertEquals("/", remotePathParent("/home"))
        assertEquals("/", remotePathParent("/"))
        assertEquals("/home", remotePathParent("/home/项目/"))
        assertEquals("C:/", remotePathParent("C:\\Users"))
        assertEquals("C:/", remotePathParent("C:\\"))
        assertEquals("//server/share/", remotePathParent("\\\\server\\share\\folder"))
        assertEquals("//server/share/", remotePathParent("\\\\server\\share"))
        assertEquals(listOf("C:/" to "C:/", "Projects" to "C:/Projects", "项目" to "C:/Projects/项目"), remotePathBreadcrumbs("C:\\Projects\\项目"))
        assertEquals(listOf("//server/share/" to "//server/share/"), remotePathBreadcrumbs("\\\\server\\share"))
        assertEquals(listOf("/" to "/", "home" to "/home", "csl" to "/home/csl"), remotePathBreadcrumbs("/home/csl"))
    }

    @Test fun attachmentPathsAndNamesUseTheRemotePlatform() {
        assertEquals("C:/Users/用户/.codex-remote-uploads", joinRemotePath("C:\\Users\\用户", ".codex-remote-uploads"))
        assertEquals("//server/share/image.png", joinRemotePath("\\\\server\\share", "image.png"))
        assertEquals("/file.txt", joinRemotePath("/", "file.txt"))
        assertEquals("image.png", remotePathName("C:\\Users\\用户\\image.png"))
        assertEquals("a\\b", remotePathName("/home/a\\b"))
        listOf("..", ".", "a/b", "a\\b", "a\u0000b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { joinRemotePath("C:/", name) }
        }
    }

    @Test fun fileUrisRoundTripWithoutAndroidFileSemantics() {
        val paths = mapOf(
            "/home/项目/a #?😀.png" to "/home/项目/a #?😀.png",
            "D:\\Project Files\\项目\\a #?😀.png" to "D:/Project Files/项目/a #?😀.png",
            "\\\\server\\share\\项目\\a #?😀.png" to "//server/share/项目/a #?😀.png",
            "C:\\" to "C:/",
            "\\\\server\\share" to "//server/share",
        )
        paths.forEach { (path, normalized) ->
            val uri = remotePathToFileUri(path)
            assertTrue(uri, uri.startsWith("file://"))
            assertFalse(uri, uri.contains(' '))
            assertEquals(path, normalized, remoteFileUriToPath(uri))
        }
        assertEquals("C:/Users/用户/image.png", remoteFileUriToPath("file:///C:/Users/%E7%94%A8%E6%88%B7/image.png"))
        listOf("https://example.com/a", "file:relative", "file:///a?query", "file:///a#fragment", "file:///a%00b").forEach {
            assertNull(it, remoteFileUriToPath(it))
        }
    }
}
