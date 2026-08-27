package com.wts.smartscore.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DirectoryZipExporter {
    fun export(sourceDir: File, output: File) {
        require(sourceDir.isDirectory) { "Source directory does not exist" }
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            sourceDir.walkTopDown().filter { it.isFile && it != output }.forEach { file ->
                val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
