package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOrganizationTest {
    @Test
    fun `every platform has a distinct default folder and destination preference`() {
        val folders = Platform.entries.map { platform ->
            DownloadOrganization.folderName(
                platform,
                DownloadOrganization.SINGLE_FOLDER,
                author = "author",
                postId = "post",
            )
        }
        val preferences = Platform.entries.map(DownloadDestination::preference)

        assertEquals(Platform.entries.size, folders.distinct().size)
        assertEquals(Platform.entries.size, preferences.distinct().size)
        Platform.entries.zip(folders).forEach { (platform, folder) ->
            assertEquals("AllDownloader/${platform.downloadFolderName}", folder)
        }
    }

    @Test
    fun `by work folders remain below their platform folder`() {
        Platform.entries.forEach { platform ->
            val folder = DownloadOrganization.folderName(
                platform,
                DownloadOrganization.PER_POST_FOLDER,
                author = "author/name",
                postId = "post:1",
            )

            assertEquals(
                "AllDownloader/${platform.downloadFolderName}/author_name_post_1",
                folder,
            )
            assertTrue(folder.startsWith("AllDownloader/${platform.downloadFolderName}/"))
        }
    }
}
