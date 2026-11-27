package com.streamlinemyjob.inspect.photo

import java.nio.file.Files
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** `PhotoFolderContract` (designs/stage-2-camera.md 4.12, section 6) under Robolectric, over `DirPhotoFolder` on a temporary directory. */
@RunWith(RobolectricTestRunner::class)
class DirPhotoFolderContractTest : PhotoFolderContract() {
    private val dirs = ArrayList<File>()

    override fun newFolder(): PhotoFolder {
        val dir = Files.createTempDirectory("photo-folder-contract").toFile()
        dirs += dir
        return DirPhotoFolder(dir)
    }

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }
}
