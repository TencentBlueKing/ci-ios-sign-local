package com.tencent.bk.devops.atom.utils

import java.io.File
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * @ Author     ：Royal Huang
 * @ Date       ：Created in 14:28 2020/8/13
 */

object FileMatcher {

    private fun transfer(regexPath: String): String {
        var resultPath = regexPath
        resultPath = resultPath.replace(".", "\\.")
        resultPath = resultPath.replace("*", ".*")
        return resultPath
    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun matchFiles(workspace: File, filePath: String, extension: String? = null): List<File> {
        // 斜杠开头的，绝对路径
        val absPath = filePath.startsWith("/") || (filePath[0].isLetter() && filePath[1] == ':')

        val fileList: List<File>
        // 文件夹返回所有文件
        if (filePath.endsWith("/")) {
            // 绝对路径
            fileList = if (absPath) File(filePath).listFiles().filter { return@filter it.isFile }.toList()
            else File(workspace, filePath).listFiles().filter { return@filter it.isFile }.toList()
        } else {
            // 相对路径
            // get start path
            val file = File(filePath)
            val startPath = if (file.parent.isNullOrBlank()) "." else file.parent
            val regexPath = file.name

            // return result
            val pattern = Pattern.compile(transfer(regexPath))
            val startFile = if (absPath) File(startPath) else File(workspace, startPath)
            val path = Paths.get(startFile.canonicalPath)
            fileList = startFile.listFiles()?.filter {
                val rePath = path.relativize(Paths.get(it.canonicalPath)).toString()
                it.isFile && pattern.matcher(rePath).matches()
            }?.toList() ?: listOf()
        }
        val resultList = mutableListOf<File>()
        fileList.forEach { f ->
            // 文件名称不允许带有空格
            if (!f.name.contains(" ") && f.extension == extension) {
                resultList.add(f)
            }
        }
        return resultList
    }

    fun isContainChinese(str: String): Boolean {
        val p: Pattern = Pattern.compile("[\u4e00-\u9fa5]")
        val m: Matcher = p.matcher(str)
        return m.find()
    }
}