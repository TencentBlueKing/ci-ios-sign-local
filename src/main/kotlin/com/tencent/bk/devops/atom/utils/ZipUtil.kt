/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bk.devops.atom.utils

import org.slf4j.LoggerFactory
import java.io.*
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


object ZipUtil {

    private val logger = LoggerFactory.getLogger(ZipUtil::class.java)

    private const val BUFFER_SIZE = 1024 // 缓冲区大小

    /**
     * 解压指定文件到指定目录
     * @param srcFile 待解压文件对象
     * @param srcFile 解压路径
     * @param createRootDirFlag 是否新建目录
     */
    fun unZipFile(srcFile: File, destDirPath: String, createRootDirFlag: Boolean? = true) {
        // 判断源文件是否存在
        if (!srcFile.exists()) {
            throw Exception(srcFile.path + "is not exist")
        }
        // 开始解压
        var zipFile: ZipFile? = null
        var inputStream: InputStream? = null
        var fos: OutputStream? = null
        try {
            zipFile = ZipFile(srcFile, Charset.forName("UTF-8"))
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement() as ZipEntry
                var entryName = entry.name
                if (null != createRootDirFlag && !createRootDirFlag) {
                    entryName = entryName.substring(entryName.indexOf("/") + 1) // 去掉根目录
                }
                // 如果是文件夹则需创建文件目录
                val pair = handleZipFile(entry, destDirPath, entryName, zipFile)
                fos = pair.first
                inputStream = pair.second
            }
        } catch (e: IOException) {
            logger.error("unzip error!", e)
        } finally {
            closeUnzipFileStream(fos, inputStream, zipFile)
        }
    }

    /**
     * 压缩目录到指定文件
     * @param srcDir 待压缩文件对象
     * @param zipFile zip文件路径
     */
    fun zipDir(srcDir: File, zipFile: String) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { it ->
            try {
                it.use {
                    zipFiles(it, srcDir, "")
                }
            } catch (e: Exception) {
                logger.error("zip error: ", e)
                it.closeEntry()
                it.close()
            }
        }
    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun zipFiles(zipOut: ZipOutputStream, sourceFile: File, parentDirPath: String) {

        val data = ByteArray(2048)

        for (f in sourceFile.listFiles()) {
            val basePath = if (parentDirPath.isNullOrBlank()) {
                f.name
            } else {
                parentDirPath + File.separator + f.name
            }
            if (f.isDirectory) {
                val entry = ZipEntry(basePath + File.separator)
                entry.time = f.lastModified()
                entry.size = f.length()
                logger.info("zip -> Adding directory: " + f.name)
                zipOut.putNextEntry(entry)

                zipFiles(zipOut, f, basePath)
            } else {
                FileInputStream(f).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        logger.info("zip -> Adding file: $basePath")
                        val entry = ZipEntry(basePath)
                        entry.time = f.lastModified()
                        entry.size = f.length()
                        zipOut.putNextEntry(entry)
                        while (true) {
                            val readBytes = origin.read(data)
                            if (readBytes == -1) {
                                break
                            }
                            zipOut.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }

    private fun handleZipFile(
        entry: ZipEntry,
        destDirPath: String,
        entryName: String?,
        zipFile: ZipFile
    ): Pair<OutputStream?, InputStream?> {
        var inputStream: InputStream? = null
        var fos: OutputStream? = null
        if (entry.isDirectory) {
            val dirPath = "$destDirPath/$entryName"
            val dir = File(dirPath)
            dir.mkdirs()
        } else {
            // 如果是文件，就先创建一个文件，然后用io流把内容copy过去
            val targetFile = File("$destDirPath/$entryName")
            if (!targetFile.parentFile.exists()) {
                targetFile.parentFile.mkdirs()
            }
            targetFile.createNewFile()
            // 将压缩文件内容写入到这个文件中
            inputStream = zipFile.getInputStream(entry)
            fos = FileOutputStream(targetFile)
            copyUnzipFile(inputStream, fos)
        }
        return Pair(fos, inputStream)
    }

    private fun copyUnzipFile(inputStream: InputStream, fos: FileOutputStream) {
        val buf = ByteArray(BUFFER_SIZE)
        var len = inputStream.read(buf)
        while (len != -1) {
            fos.write(buf, 0, len)
            len = inputStream.read(buf)
        }
    }

    private fun closeUnzipFileStream(fos: OutputStream?, inputStream: InputStream?, zipFile: ZipFile?) {
        if (null != fos) {
            try {
                fos.close()
            } catch (e: IOException) {
                logger.error("outputStream close error!", e)
            } finally {
                closeInputStream(inputStream, zipFile)
            }
        }
    }

    private fun closeInputStream(inputStream: InputStream?, zipFile: ZipFile?) {
        if (null != inputStream) {
            try {
                inputStream.close()
            } catch (e: IOException) {
                logger.error("inputStream close error!", e)
            } finally {
                closeZipFile(zipFile)
            }
        }
    }

    private fun closeZipFile(zipFile: ZipFile?) {
        if (zipFile != null) {
            try {
                zipFile.close()
            } catch (e: IOException) {
                logger.error("zipFile close error!", e)
            }
        }
    }
}
