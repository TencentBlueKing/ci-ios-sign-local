package com.tencent.bk.devops.atom

import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.fasterxml.jackson.core.type.TypeReference
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.tencent.bk.devops.atom.common.Status
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.ParamMap
import com.tencent.bk.devops.atom.pojo.AppexSignInfo
import com.tencent.bk.devops.atom.pojo.ArtifactData
import com.tencent.bk.devops.atom.pojo.AtomResult
import com.tencent.bk.devops.atom.pojo.IpaInfoPlist
import com.tencent.bk.devops.atom.pojo.IpaSignInfo
import com.tencent.bk.devops.atom.pojo.MobileProvisionInfo
import com.tencent.bk.devops.atom.pojo.SignAtomParam
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.atom.utils.CommandLineUtils
import com.tencent.bk.devops.atom.utils.FileMatcher
import com.tencent.bk.devops.atom.utils.FileUtil
import com.tencent.bk.devops.atom.utils.SignUtils
import com.tencent.bk.devops.atom.utils.SignUtils.APP_INFO_PLIST_FILENAME
import com.tencent.bk.devops.atom.utils.SignUtils.MAIN_APP_FILENAME
import com.tencent.bk.devops.atom.utils.UUIDUtil
import com.tencent.bk.devops.atom.utils.json.JsonUtil
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.ArrayList
import java.util.regex.Pattern

@AtomService(paramClass = SignAtomParam::class)
class IosCompanySignAtom : TaskAtom<SignAtomParam> {

    /**
     * 执行主入口
     *
     * @param atomContext 插件上下文
     */
    override fun execute(atomContext: AtomContext<SignAtomParam>) {

        val param = atomContext.param
        val result = atomContext.result
        val data = result.data
        checkParam(param, result)
        if (result.status != Status.success) {
            logger.error("Params error: {}", result.message)
            return
        }
        val workspace = File(param.bkWorkspace)
        val ipaPath: String = param.ipaPath!!
        val ipaResuleFiles = mutableListOf<File>()
        val paths = ipaPath.split(",".toRegex())
        for (path in paths) {
            val singlePaths = path.split(";".toRegex())
            for (single in singlePaths) {
                val list: List<File> = FileMatcher.matchFiles(workspace, single, "ipa")
                if (list.isEmpty()) {
                    logger.error("no ipa file found in: {}", single)
                    result.status = Status.failure
                    result.message = "get ipa file failed"
                    return
                }
                for (ipaFile in list) {
                    val filePath = ipaFile.absolutePath
                    val isMatch = Pattern.matches("[0-9A-Za-z\\-\\_\\.:/\\\\]+", filePath)
                    if(!isMatch) {
                        logger.error("$filePath ipa文件路径：目录、文件名只支持英文字母、数字、中划线、下划线、.")
                        result.status = Status.failure
                        result.message = "$filePath ipa文件路径：目录、文件名只支持英文字母、数字、中划线、下划线、."
                        return
                    } else {
                        logger.info("$filePath isMatch = $isMatch")
                    }
                }
            }
        }
        val info = getIpaSignInfo(param)
        for (path in paths) {
            val singlePaths = path.split(";".toRegex())
            for (single in singlePaths) {
                val list: List<File> = FileMatcher.matchFiles(workspace, single, "ipa")
                if (list.isEmpty()) {
                    logger.error("no ipa file found in: {}", single)
                    result.status = Status.failure
                    result.message = "get ipa file failed"
                    return
                }
                for (ipaFile in list) {
                    val fileSize = ipaFile.totalSpace
                    var md5: String? = null
                    md5 = if (!ipaFile.exists()) {
                        throw IOException(ipaFile.toString() + "file not found")
                    } else {
                        DigestUtils.md5Hex(FileInputStream(ipaFile))
                    }
                    info.md5 = md5
                    info.fileSize = fileSize
                    val resuleFile = signIpa(workspace, ipaFile, info, result)
                    if (resuleFile != null) ipaResuleFiles.add(resuleFile)
                }
            }
        }
        val resultFilenameList = StringBuilder()
        ipaResuleFiles.forEachIndexed() { index, file ->
            if (index == 0) resultFilenameList.append(file.name)
            else resultFilenameList.append(",${file.name}")
        }
        data["BK_CI_RESIGN_IPA_FILE_LIST"] = StringData(resultFilenameList.toString())
    }

    /**
     * 检查参数
     *
     * @param param 请求参数
     * @param result 结果
     */
    private fun checkParam(param: SignAtomParam, result: AtomResult) {
        // 参数检查
        try {
            // 参数检查
            if (StringUtils.isBlank(param.ipaPath)) {
                result.status = Status.failure // 状态设置为失败
                result.message = "ipa path is blank" // 失败信息回传给插件执行框架会打印出结果
                return
            }

            // 参数检查
            if (StringUtils.isBlank(param.profileType)) {
                result.status = Status.failure
                result.message = "profileType didn't config"
                return
            }


            // 参数检查
            if (StringUtils.isBlank(param.certId)) {
                result.status = Status.failure
                result.message = "certId didn't config, must specify a local certificate ID."
                return
            }

            logger.info("Profile type：" + param.profileType)
            val appexStr = param.appexListOnLocal

            param.appexListResultMap = if (appexStr.isNullOrBlank() || param.profileType == "general" || param.profileType == "single") null
            else JsonUtil.fromJson(appexStr, object : TypeReference<ArrayList<ParamMap>?>() {})

            val replaceKeyStr = param.replaceKeyList
            param.replaceKeyArrayList = if (replaceKeyStr.isNullOrBlank()) null
            else JsonUtil.fromJson(replaceKeyStr, object : TypeReference<ArrayList<ParamMap>?>() {})

            //扩展应用检查
            val appexList = param.appexListResultMap
            if (appexList != null && appexList.isNotEmpty()) {
                for (appex in appexList) {
                    val kvs = appex.values
                    if (kvs == null || kvs.size < 2) {
                        result.status = Status.error
                        result.message = "appex info input with error"
                        return
                    }
                    for (kv in kvs) {
                        logger.info("kv:$kv")
                        Preconditions.checkArgument(!Strings.isNullOrEmpty(kv.value), "appex info cannot be blank")
                    }
                }
            }

            if(StringUtils.isNotEmpty(param.resultSuffix)){
                val isMatch = Pattern.matches("[0-9A-Za-z\\-\\_\\.]+", param.resultSuffix)
                if(!isMatch){
                    logger.error("${param.resultSuffix} 重签IPA包文件名后缀：只支持英文字母、数字、中划线、下划线、.")
                    result.status = Status.error
                    result.message = "${param.resultSuffix} 重签IPA包文件名后缀不匹配：只支持英文字母、数字、中划线、下划线、."
                    return
                }
            }
        } catch (e: Exception) {
            result.status = Status.failure
            result.message = e.localizedMessage
        }
    }

    //必须在 checkParam 后调用
    private fun getAppexSign(param: SignAtomParam): List<AppexSignInfo>? {
        val pList = param.appexListResultMap
        if (pList == null || pList.isEmpty()) {
            return null
        }
        val sList = mutableListOf<AppexSignInfo>()
        pList.forEach { appex ->
            val kvs = appex.values ?: return@forEach
            val signInfo = AppexSignInfo(kvs[0].value, kvs[1].value)
            logger.info("AppexSignInfo: $signInfo")
            sList.add(signInfo)
        }
        return sList
    }

    //必须在 checkParam 后调用
    private fun getReplaceMap(param: SignAtomParam): Map<String, String>? {
        val paramMap = param.replaceKeyArrayList
        if (paramMap == null || paramMap.isEmpty()) {
            return null
        }
        val map = mutableMapOf<String, String>()
        paramMap.forEach { m ->
            val kvs = m.values ?: return@forEach
            logger.info("ReplaceKeyValue: ${kvs[0].value} -> ${kvs[1].value}")
            map[kvs[0].value] = kvs[1].value
        }
        return map
    }

    /**
     * 组装签名请求
     *
     * @param param 请求参数
     */
    @Throws(IOException::class)
    private fun getIpaSignInfo(param: SignAtomParam): IpaSignInfo {

        val userId = param.pipelineStartUserName


        // 是否使用通配方式
//        var wildcard = false
//        if (param.profileType == "general") {
//            wildcard = true
//        }

        // 使用两种不同证书的判断
        val certId = param.certId!!

        var archiveType = "PIPELINE"
        // 选择不同归档方式
        if (param.customize) {
            archiveType = "CUSTOM"
        }
        val archivePath = param.destPath
        val projectId = param.projectName
        val pipelineId = param.pipelineId
        val buildId = param.pipelineBuildId
        val taskId = param.pipelineTaskId
        val buildNum = param.pipelineBuildNum.toInt()

        // 主描述文件ID
        val localStorage = param.profileType == "local"

        val replaceBundleId = param.replaceBundleId == true

        // 组装ul数组
        var universalLinks: List<String>? = null
        if (!param.ul.isNullOrEmpty()) {
            universalLinks = param.ul?.split(';')
        }

        // 组装appGroup数
        var keychainAccessGroups: List<String>? = null
        if (!param.keychainAccessGroups.isNullOrEmpty()) {
            keychainAccessGroups = param.keychainAccessGroups?.split(';')
        }

        // 组装带替换的key值
        val replaceKeyList = getReplaceMap(param)

        // 组装拓展Info数组
        val appexSignInfo = getAppexSign(param)
        val mobileProvisionInfoMap = if (localStorage) {
            loadMobileProvisionOnLocal(param, appexSignInfo)
        } else {
            throw AtomException("暂时不支持Ticket获取")
        }

        return IpaSignInfo(
            userId = userId,
//            wildcard = wildcard,
            fileSize = null,
            md5 = null,
            certId = certId,
            archiveType = archiveType,
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            taskId = taskId,
            archivePath = archivePath,
            mobileProvisionInfoMap = mobileProvisionInfoMap,
            universalLinks = universalLinks,
            keychainAccessGroups = keychainAccessGroups,
            replaceBundleId = replaceBundleId,
            appexSignInfo = appexSignInfo,
            replaceKeyList = replaceKeyList,
            buildNum = buildNum,
            resultSuffix = param.resultSuffix
        )
    }

    private fun signIpa(
        workspace: File,
        ipaFile: File,
        ipaSignInfo: IpaSignInfo,
        result: AtomResult
    ): File? {
        logger.warn("start to resign ipa file: ${ipaFile.canonicalPath}")
        logger.warn(ipaSignInfo.toString())
        if (!ipaFile.exists() || !ipaFile.isFile) {
            result.status = Status.error
            result.message = "$ipaFile resign failed don't exist or is not file."
            logger.error("$ipaFile resign failed don't exist or is not file.")
            return null
        }
        if (FileMatcher.isContainChinese(ipaFile.name)) {
            result.status = Status.error
            result.message = "Filename [${ipaFile.name}] is contain Chinese, please check!"
            logger.error("Filename [${ipaFile.name}] is contain Chinese, please check!")
            return null
        }
        try {
            // 准备描述文件和ipa解压后的目录
            val ipaUnzipDir = getIpaUnzipDir(workspace)
            FileUtil.mkdirs(ipaUnzipDir)
            val mobileProvisionDir = getMobileProvisionDir(workspace)
            FileUtil.mkdirs(mobileProvisionDir)

            // 解压IPA包
            SignUtils.unzipIpa(ipaFile, ipaUnzipDir)
            logger.warn("[unzip] finished: ${ipaUnzipDir.canonicalPath}")

            // 签名操作
            val signFinished = resignIpaPackage(ipaUnzipDir, ipaSignInfo, ipaSignInfo.mobileProvisionInfoMap)
            if (!signFinished) {
                logger.error("[sign] sign ipa failed, please check the config!")
                throw Exception("IPA包签名失败")
            }
            logger.warn("[sign] finished: $signFinished")

            // 压缩目录
            val uploadFileName = getResultIpaFilename(ipaFile, ipaSignInfo.resultSuffix)
            val signedIpaFile = SignUtils.zipIpaFile(ipaUnzipDir, ipaUnzipDir.parent + File.separator + uploadFileName)
            if (signedIpaFile == null) {
                logger.error("[zip] zip to ipa[$uploadFileName] file failed.")
                throw Exception("IPA文件生成失败")
            }
            logger.warn("[zip] finished: ${signedIpaFile.canonicalPath}")

            // 做归档记录操作
            result.data["resignResultIpa"] = ArtifactData(setOf(signedIpaFile.absolutePath))
            return signedIpaFile
        } catch (e: Exception) {
            e.printStackTrace()
            result.status = Status.error
            result.message = "Load sign response with error: ${e.message}"
            logger.info("Load sign response with error: ${e.message}")
            return null
        }
    }

//    private fun downloadMobileProvision(
//        mobileProvisionDir: File,
//        ipaSignInfo: IpaSignInfo
//    ): Map<String, MobileProvisionInfo> {
//        val mobileProvisionMap = mutableMapOf<String, MobileProvisionInfo>()
//        if (ipaSignInfo.mobileProvisionId != null) {
//            val mpFile = mobileProvisionService.downloadMobileProvision(
//                mobileProvisionDir = mobileProvisionDir,
//                projectId = ipaSignInfo.projectId,
//                mobileProvisionId = ipaSignInfo.mobileProvisionId!!
//            )
//            mobileProvisionMap[MAIN_APP_FILENAME] = parseMobileProvision(mpFile)
//        }
//        ipaSignInfo.appexSignInfo?.forEach {
//            val mpFile = mobileProvisionService.downloadMobileProvision(
//                mobileProvisionDir = mobileProvisionDir,
//                projectId = ipaSignInfo.projectId,
//                mobileProvisionId = it.mobileProvisionId
//            )
//            mobileProvisionMap[it.appexName] = parseMobileProvision(mpFile)
//        }
//        return mobileProvisionMap
//    }

    private fun loadMobileProvisionOnLocal(param: SignAtomParam, appexSignInfo: List<AppexSignInfo>?): Map<String, MobileProvisionInfo> {
        val mobileProvisionMap = mutableMapOf<String, MobileProvisionInfo>()
        if (!param.mainProfileOnLocal.isNullOrBlank()) {
            val mpFile = File(param.mainProfileOnLocal!!)
            mobileProvisionMap[MAIN_APP_FILENAME] = parseMobileProvision(mpFile)
        }
        appexSignInfo?.forEach {
            val mpFile = File(it.mobileProvisionId)
            mobileProvisionMap[it.appexName] = parseMobileProvision(mpFile)
        }
        return mobileProvisionMap
    }

    /*
    * 通用逻辑-对解压后的ipa目录进行签名
    * 对主App，扩展App和框架文件进行签名
    * */
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun resignIpaPackage(
        unzipDir: File,
        ipaSignInfo: IpaSignInfo,
        mobileProvisionInfoList: Map<String, MobileProvisionInfo>
    ): Boolean {
        val payloadDir = File(unzipDir.absolutePath + File.separator + "Payload")
        val appDirs = payloadDir.listFiles { dir, name ->
            dir.extension == "app" || name.endsWith("app")
        }.toList()
        if (appDirs.isEmpty()) throw Exception("IPA包解析失败")
        val appDir = appDirs.first()

        // 检查是否将包内所有app/appex对应的签名信息传入
        val allAppsInPackage = mutableListOf<File>()
        SignUtils.getAllAppsInDir(appDir, allAppsInPackage)
        allAppsInPackage.forEach { app ->
            if (!mobileProvisionInfoList.keys.contains(app.nameWithoutExtension)) {
                logger.error("Not found appex <${app.name}> MobileProvisionInfo")
                throw Exception("缺少${app.name}签名信息，请检查参数")
            }
        }

        logger.info("Start to resign ${appDir.name} with $mobileProvisionInfoList")
        return SignUtils.resignApp(
            appDir = appDir,
            certId = ipaSignInfo.certId,
            infoMap = mobileProvisionInfoList,
            appName = MAIN_APP_FILENAME,
            replaceBundleId = ipaSignInfo.replaceBundleId ?: true,
            universalLinks = ipaSignInfo.universalLinks,
            keychainAccessGroups = ipaSignInfo.keychainAccessGroups,
            replaceKeyList = ipaSignInfo.replaceKeyList
        )
    }

    /*
    * 通用逻辑-对解压后的ipa目录进行通配符签名
    * 对主App，扩展App和框架文件进行通配符签名
    * */
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun resignIpaPackageWildcard(
        unzipDir: File,
        ipaSignInfo: IpaSignInfo,
        wildcardInfo: MobileProvisionInfo?
    ): Boolean {
        if (wildcardInfo == null) {
            throw Exception("通配符描述文件不存在")
        }
        val payloadDir = File(unzipDir.absolutePath + File.separator + "Payload")
        val appDirs = payloadDir.listFiles { dir, name ->
            dir.extension == "app" || name.endsWith("app")
        }.toList()
        if (appDirs.isEmpty()) throw Exception("IPA包解析失败")
        val appDir = appDirs.first()

        return SignUtils.resignAppWildcard(
            appDir = appDir,
            certId = ipaSignInfo.certId,
            wildcardInfo = wildcardInfo
        )
    }

    /*
    * 通用逻辑-解析描述文件的内容
    * */
    private fun parseMobileProvision(mobileProvisionFile: File): MobileProvisionInfo {
        val plistFile = File("${mobileProvisionFile.canonicalPath}.plist")
        val entitlementFile = File("${mobileProvisionFile.canonicalPath}.entitlement.plist")
        // 描述文件转为plist文件
        val mpToPlistCommand = "/usr/bin/security cms -D -i ${mobileProvisionFile.canonicalPath}"
        val plistResult =
            CommandLineUtils.execute(mpToPlistCommand, mobileProvisionFile.parentFile, true)
        // 将plist写入到文件
        plistFile.writeText(plistResult)
        // 从plist文件抽离出entitlement文件
        val plistToEntitlementCommand = "/usr/libexec/PlistBuddy -x -c 'Print:Entitlements' ${plistFile.canonicalPath}"
        // 将entitlment写入到文件
        val entitlementResult = CommandLineUtils.execute(
            command = plistToEntitlementCommand,
            workspace = mobileProvisionFile.parentFile,
            print2Logger = true
        )
        entitlementFile.writeText(entitlementResult)

        // 解析bundleId
        val rootDict = PropertyListParser.parse(plistFile) as NSDictionary
        // entitlement
        if (!rootDict.containsKey("Entitlements")) throw RuntimeException("no Entitlements find in plist")
        val entitlementDict = rootDict.objectForKey("Entitlements") as NSDictionary
        // application-identifier
        if (!entitlementDict.containsKey("application-identifier")) {
            throw RuntimeException("no Entitlements.application-identifier find in plist")
        }
        val bundleIdString = (entitlementDict.objectForKey("application-identifier") as NSString).toString()
        val bundleId = bundleIdString.substring(bundleIdString.indexOf(".") + 1)
        // 统一处理entitlement文件
        handleEntitlement(entitlementFile)
        return MobileProvisionInfo(
            mobileProvisionFile = mobileProvisionFile,
            plistFile = plistFile,
            entitlementFile = entitlementFile,
            bundleId = bundleId
        )
    }

    /*
    * 寻找Info.plist的信息
    * */
    private fun findInfoPlist(
        unzipDir: File
    ): File {
        return fetchPlistFileInDir(File(unzipDir, "payload"))
            ?: throw Exception("ipa文件解压并检查签名信息失败")
    }

    /*
    * 解析IPA包Info.plist的信息
    * */
    private fun parsInfoPlist(
        infoPlist: File,
        zhStrings: File?
    ): IpaInfoPlist {
        try {
            val rootDict = PropertyListParser.parse(infoPlist) as NSDictionary
            // 应用包名
            if (!rootDict.containsKey("CFBundleIdentifier")) {
                throw RuntimeException("no CFBundleIdentifier find in plist")
            }
            var parameters = rootDict.objectForKey("CFBundleIdentifier") as NSString
            val bundleIdentifier = parameters.toString()
            // 应用标题
            if (!rootDict.containsKey("CFBundleName")) throw RuntimeException("no CFBundleName find in plist")
            parameters = rootDict.objectForKey("CFBundleName") as NSString
            val appTitle = parameters.toString()
            // 应用版本
            if (!rootDict.containsKey("CFBundleShortVersionString")) {
                throw RuntimeException("no CFBundleShortVersionString find in plist")
            }
            parameters = rootDict.objectForKey("CFBundleShortVersionString") as NSString
            val bundleVersion = parameters.toString()
            // 应用构建版本
            if (!rootDict.containsKey("CFBundleVersion")) throw RuntimeException("no CFBundleVersion find in plist")
            parameters = rootDict.objectForKey("CFBundleVersion") as NSString
            val bundleVersionFull = parameters.toString()
            // scheme
            val scheme = try {
                val schemeArray = rootDict.objectForKey("CFBundleURLTypes") as NSArray
                schemeArray.array
                    .map { it as NSDictionary }
                    .map { it.objectForKey("CFBundleURLSchemes") }
                    .map { it as NSArray }
                    .map { it.array }
                    .flatMap { it.toList() }
                    .map { it as NSString }
                    .map { it.toString() }
                    .maxBy { it.length } ?: ""
            } catch (e: Exception) {
                ""
            }
            // 应用名称
            val appName = try {
                val nameDictionary = if (zhStrings != null) {
                    PropertyListParser.parse(zhStrings) as NSDictionary
                } else {
                    rootDict
                }
                nameDictionary.objectForKey("CFBundleDisplayName").toString()
            } catch (e: Exception) {
                ""
            }

            return IpaInfoPlist(
                bundleIdentifier = bundleIdentifier,
                appTitle = appTitle,
                bundleVersion = bundleVersion,
                bundleVersionFull = bundleVersionFull,
                scheme = scheme,
                appName = appName
            )
        } catch (e: Exception) {
            throw Exception("解析Info.plist失败: ${e.message}")
        }
    }

    /*
    * 寻找目录下的指定文件
    * */
    private fun fetchPlistFileInDir(dir: File): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val appPattern = Pattern.compile(".+\\.app")
        dir.listFiles().forEach {
            if (appPattern.matcher(it.name).matches()) {
                val matchFile = File(it, APP_INFO_PLIST_FILENAME)
                if (it.isDirectory && matchFile.isFile) {
                    return matchFile
                }
            }
        }
        return null
    }

    private fun getIpaUnzipDir(workspace: File): File {
        return File("${getIpaTmpDir(workspace)}.unzipDir")
    }

    private fun getIpaTmpDir(workspace: File): File {
        val dir = File(workspace, UUIDUtil.generate())
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMobileProvisionDir(workspace: File): File {
        return File("${getIpaTmpDir(workspace)}.mobileProvisionDir")
    }

    private fun getResultIpaFilename(file: File, suffix: String?): String {
        val suffixName = if (suffix.isNullOrBlank()) {
            "_enterprise_sign"
        } else suffix
        return file.nameWithoutExtension + suffixName + "." + file.extension
    }

    private fun handleEntitlement(entitlementFile: File) {
        val rootDict = PropertyListParser.parse(entitlementFile) as NSDictionary

        // 处理keychain-access-groups中无用的com.apple.token
        if (rootDict.containsKey(KEYCHAIN_ACCESS_GROUPS_KEY)) {
            val keychainArray = (rootDict.objectForKey(KEYCHAIN_ACCESS_GROUPS_KEY) as NSArray).array.withIndex()
            for ((index, e) in keychainArray) {
                if (e.toString() == "com.apple.token") {
                    val removeKeyChainGroupCMD =
                        "plutil -remove keychain-access-groups.$index ${entitlementFile.canonicalPath}"
                    CommandLineUtils.execute(removeKeyChainGroupCMD, entitlementFile.parentFile, true)
                    break
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IosCompanySignAtom::class.java)
        private val TEAM_IDENTIFIER_KEY = "com.apple.developer.team-identifier"
        private val KEYCHAIN_ACCESS_GROUPS_KEY = "keychain-access-groups"
    }
}
