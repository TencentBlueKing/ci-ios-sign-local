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
import com.tencent.bk.devops.atom.pojo.SignLocalAtomParam
import com.tencent.bk.devops.atom.pojo.StringData
import com.tencent.bk.devops.atom.spi.AtomService
import com.tencent.bk.devops.atom.spi.TaskAtom
import com.tencent.bk.devops.atom.utils.CommandLineUtils
import com.tencent.bk.devops.atom.utils.CertUtils
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

@AtomService(paramClass = SignLocalAtomParam::class)
class IosSignLocalAtom : TaskAtom<SignLocalAtomParam> {

    companion object {
        private val logger = LoggerFactory.getLogger(IosSignLocalAtom::class.java)
        private const val KEYCHAIN_ACCESS_GROUPS_KEY = "keychain-access-groups"
        private const val SIGN_TEMP_DIR = ".sign_tmp"
        private const val MP_TEMP_FILE = ".mp"
    }

    /**
     * ???????????????
     *
     * @param atomContext ???????????????
     */
    override fun execute(atomContext: AtomContext<SignLocalAtomParam>) {

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
                        logger.error("$filePath ipa??????????????????????????????????????????????????????????????????????????????????????????.")
                        result.status = Status.failure
                        result.message = "$filePath ipa??????????????????????????????????????????????????????????????????????????????????????????."
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
        ipaResuleFiles.forEachIndexed { index, file ->
            if (index == 0) resultFilenameList.append(file.name)
            else resultFilenameList.append(",${file.name}")
        }
        data["BK_CI_RESIGN_IPA_FILE_LIST"] = StringData(resultFilenameList.toString())
    }

    /**
     * ????????????
     *
     * @param param ????????????
     * @param result ??????
     */
    private fun checkParam(param: SignLocalAtomParam, result: AtomResult) {
        // ????????????
        try {
            // ????????????
            if (StringUtils.isBlank(param.ipaPath)) {
                result.status = Status.failure // ?????????????????????
                result.message = "ipa path is blank" // ?????????????????????????????????????????????????????????
                return
            }

            // ????????????
            if (StringUtils.isBlank(param.profileType)) {
                result.status = Status.failure
                result.message = "profileType didn't config"
                return
            }


            // ????????????
            if (StringUtils.isBlank(param.certId)) {
                result.status = Status.failure
                result.message = "certId didn't config, must specify a local certificate ID."
                return
            }

            logger.info("Profile type???" + param.profileType)
            val appexStr = if (param.profileStorage == "local") param.appexListOnLocal else param.appexListInTicket

            param.appexListResultMap = if (appexStr.isNullOrBlank() || param.profileType == "general" || param.profileType == "single") null
            else JsonUtil.fromJson(appexStr, object : TypeReference<ArrayList<ParamMap>?>() {})

            val replaceKeyStr = param.replaceKeyList
            param.replaceKeyArrayList = if (replaceKeyStr.isNullOrBlank()) null
            else JsonUtil.fromJson(replaceKeyStr, object : TypeReference<ArrayList<ParamMap>?>() {})

            //??????????????????
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
                    logger.error("${param.resultSuffix} ??????IPA??????????????????????????????????????????????????????????????????????????????.")
                    result.status = Status.error
                    result.message = "${param.resultSuffix} ??????IPA???????????????????????????????????????????????????????????????????????????????????????."
                    return
                }
            }
        } catch (e: AtomException) {
            result.status = Status.failure
            result.message = e.localizedMessage
        }
    }

    //????????? checkParam ?????????
    private fun getAppexSignInfo(param: SignLocalAtomParam): List<AppexSignInfo>? {
        val pList = param.appexListResultMap
        if (pList == null || pList.isEmpty()) {
            return null
        }
        val sList = mutableListOf<AppexSignInfo>()
        pList.forEach { appex ->
            val kvs = appex.values ?: return@forEach
            val signInfo = AppexSignInfo(kvs[0].value.removeSuffix(".appex"), kvs[1].value)
            logger.info("AppexSignInfo: $signInfo")
            sList.add(signInfo)
        }
        return sList
    }

    //????????? checkParam ?????????
    private fun getReplaceMap(param: SignLocalAtomParam): Map<String, String>? {
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
     * ??????????????????
     *
     * @param param ????????????
     */
    @Throws(IOException::class)
    private fun getIpaSignInfo(param: SignLocalAtomParam): IpaSignInfo {

        val userId = param.pipelineStartUserName

        // ?????????????????????????????????
        val certId = param.certId!!

        var archiveType = "PIPELINE"
        // ????????????????????????
        if (param.customize) {
            archiveType = "CUSTOM"
        }
        val archivePath = param.destPath
        val projectId = param.projectName
        val pipelineId = param.pipelineId
        val buildId = param.pipelineBuildId
        val taskId = param.pipelineTaskId
        val buildNum = param.pipelineBuildNum.toInt()
        val wildcard = param.profileType == "general"
        val localStorage = param.profileStorage == "local"
        val replaceBundleId = param.replaceBundleId == true

        // ??????ul??????
        var universalLinks: List<String>? = null
        if (!param.ul.isNullOrEmpty()) {
            universalLinks = param.ul?.split(';')
        }

        // ??????appGroup???
        var keychainAccessGroups: List<String>? = null
        if (!param.keychainAccessGroups.isNullOrEmpty()) {
            keychainAccessGroups = param.keychainAccessGroups?.split(';')
        }

        // ??????????????????key???
        val replaceKeyList = getReplaceMap(param)

        // ????????????Info??????
        val appexSignInfo = getAppexSignInfo(param)
        val mobileProvisionInfoMap = if (localStorage) {
            loadMobileProvisionOnLocal(param, appexSignInfo)
        } else {
            val mobileProvisionDir = File(param.bkWorkspace + File.separator + MP_TEMP_FILE)
            mobileProvisionDir.mkdirs()
            downloadMobileProvisionFromTicket(param, appexSignInfo, mobileProvisionDir)
        }

        return IpaSignInfo(
            userId = userId,
            fileSize = null,
            md5 = null,
            wildcard = wildcard,
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
        val signTmpDir = getSignTmpDir(workspace)
        try {
            // ?????????????????????ipa??????????????????
            val ipaUnzipDir = getIpaUnzipDir(signTmpDir)
            FileUtil.mkdirs(ipaUnzipDir)
            val mobileProvisionDir = getMobileProvisionDir(signTmpDir)
            FileUtil.mkdirs(mobileProvisionDir)

            // ??????IPA???
            SignUtils.unzipIpa(ipaFile, ipaUnzipDir)
            logger.warn("[unzip] finished: ${ipaUnzipDir.canonicalPath}")

            // ????????????
            val signFinished = if (ipaSignInfo.wildcard) {
                resignIpaPackageWildcard(ipaUnzipDir, ipaSignInfo, ipaSignInfo.mobileProvisionInfoMap[MAIN_APP_FILENAME])
            } else {
                resignIpaPackage(ipaUnzipDir, ipaSignInfo, ipaSignInfo.mobileProvisionInfoMap)
            }
            if (!signFinished) {
                logger.error("[sign] sign ipa failed, please check the config!")
                throw AtomException("IPA???????????????")
            }
            logger.warn("[sign] finished: $signFinished")

            // ????????????
            val uploadFileName = getResultIpaFilename(ipaFile, ipaSignInfo.resultSuffix)
            val signedIpaFile = SignUtils.zipIpaFile(ipaUnzipDir, workspace.absolutePath + File.separator + uploadFileName)
            if (signedIpaFile == null) {
                logger.error("[zip] zip to ipa[$uploadFileName] file failed.")
                throw AtomException("IPA??????????????????")
            }
            logger.warn("[zip] finished: ${signedIpaFile.canonicalPath}")

            // ?????????????????????
            result.data["resignResultIpa"] = ArtifactData(setOf(signedIpaFile.absolutePath))
            return signedIpaFile
        } catch (e: AtomException) {
            e.printStackTrace()
            result.status = Status.error
            result.message = "Load sign response with error: ${e.message}"
            logger.info("Load sign response with error: ${e.message}")
            return null
        } finally {
            logger.info("Finish sign and clean temp dir: ${signTmpDir.absolutePath}")
            signTmpDir.deleteRecursively()
        }
    }

    private fun downloadMobileProvisionFromTicket(
        param: SignLocalAtomParam,
        appexSignInfo: List<AppexSignInfo>?,
        mobileProvisionDir: File
    ): Map<String, MobileProvisionInfo> {
        val mobileProvisionMap = mutableMapOf<String, MobileProvisionInfo>()
        if (!param.mainProfileInTicket.isNullOrBlank()) {
            val mpFile = CertUtils.getEnterpriseCertFile(param.mainProfileInTicket.toString(), mobileProvisionDir)
            mobileProvisionMap[MAIN_APP_FILENAME] = parseMobileProvision(mpFile)
        }
        appexSignInfo?.forEach {
            val mpFile = CertUtils.getEnterpriseCertFile(it.mobileProvisionId, mobileProvisionDir)
            mobileProvisionMap[it.appexName] = parseMobileProvision(mpFile)
        }
        return mobileProvisionMap
    }

    private fun loadMobileProvisionOnLocal(param: SignLocalAtomParam, appexSignInfo: List<AppexSignInfo>?): Map<String, MobileProvisionInfo> {
        val mobileProvisionMap = mutableMapOf<String, MobileProvisionInfo>()
        if (!param.mainProfileOnLocal.isNullOrBlank()) {
            val mpFile = File(param.mainProfileOnLocal!!)
            if (!mpFile.exists()) {
                throw AtomException("???????????????[${param.mainProfileOnLocal}]????????????????????????")
            }
            mobileProvisionMap[MAIN_APP_FILENAME] = parseMobileProvision(mpFile)
        }
        appexSignInfo?.forEach {
            val mpFile = File(it.mobileProvisionId)
            if (!mpFile.exists()) {
                throw AtomException("??????????????????[${it.mobileProvisionId}]????????????????????????")
            }
            mobileProvisionMap[it.appexName] = parseMobileProvision(mpFile)
        }
        return mobileProvisionMap
    }

    /*
    * ????????????-???????????????ipa??????????????????
    * ??????App?????????App???????????????????????????
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
        if (appDirs.isEmpty()) throw AtomException("IPA???????????????")
        val appDir = appDirs.first()

        // ???????????????????????????app/appex???????????????????????????
        val allAppsInPackage = mutableListOf<File>()
        SignUtils.getAllAppsInDir(appDir, allAppsInPackage)
        allAppsInPackage.forEach { app ->
            if (!mobileProvisionInfoList.keys.contains(app.nameWithoutExtension)) {
                logger.error("Not found appex <${app.name}> MobileProvisionInfo")
                throw AtomException("??????${app.name}??????????????????????????????")
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
    * ????????????-???????????????ipa???????????????????????????
    * ??????App?????????App????????????????????????????????????
    * */
    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun resignIpaPackageWildcard(
        unzipDir: File,
        ipaSignInfo: IpaSignInfo,
        wildcardInfo: MobileProvisionInfo?
    ): Boolean {
        if (wildcardInfo == null) {
            throw AtomException("??????????????????????????????")
        }
        val payloadDir = File(unzipDir.absolutePath + File.separator + "Payload")
        val appDirs = payloadDir.listFiles { dir, name ->
            dir.extension == "app" || name.endsWith("app")
        }.toList()
        if (appDirs.isEmpty()) throw AtomException("IPA???????????????")
        val appDir = appDirs.first()

        return SignUtils.resignAppWildcard(
            appDir = appDir,
            certId = ipaSignInfo.certId,
            wildcardInfo = wildcardInfo
        )
    }

    /*
    * ????????????-???????????????????????????
    * */
    private fun parseMobileProvision(mobileProvisionFile: File): MobileProvisionInfo {
        val plistFile = File("${mobileProvisionFile.canonicalPath}.plist")
        val entitlementFile = File("${mobileProvisionFile.canonicalPath}.entitlement.plist")
        // ??????????????????plist??????
        val mpToPlistCommand = "/usr/bin/security cms -D -i ${mobileProvisionFile.canonicalPath}"
        val plistResult =
            CommandLineUtils.execute(mpToPlistCommand, mobileProvisionFile.parentFile, true)
        // ???plist???????????????
        plistFile.writeText(plistResult)
        // ???plist???????????????entitlement??????
        val plistToEntitlementCommand = "/usr/libexec/PlistBuddy -x -c 'Print:Entitlements' ${plistFile.canonicalPath}"
        // ???entitlment???????????????
        val entitlementResult = CommandLineUtils.execute(
            command = plistToEntitlementCommand,
            workspace = mobileProvisionFile.parentFile,
            print2Logger = true
        )
        entitlementFile.writeText(entitlementResult)

        // ??????bundleId
        val rootDict = PropertyListParser.parse(plistFile) as NSDictionary
        // entitlement
        if (!rootDict.containsKey("Entitlements")) throw AtomException("no Entitlements find in plist")
        val entitlementDict = rootDict.objectForKey("Entitlements") as NSDictionary
        // application-identifier
        if (!entitlementDict.containsKey("application-identifier")) {
            throw AtomException("no Entitlements.application-identifier find in plist")
        }
        val bundleIdString = (entitlementDict.objectForKey("application-identifier") as NSString).toString()
        val bundleId = bundleIdString.substring(bundleIdString.indexOf(".") + 1)
        // ????????????entitlement??????
        handleEntitlement(entitlementFile)
        return MobileProvisionInfo(
            mobileProvisionFile = mobileProvisionFile,
            plistFile = plistFile,
            entitlementFile = entitlementFile,
            bundleId = bundleId
        )
    }

    /*
    * ??????Info.plist?????????
    * */
    private fun findInfoPlist(
        unzipDir: File
    ): File {
        return fetchPlistFileInDir(File(unzipDir, "payload"))
            ?: throw AtomException("ipa???????????????????????????????????????")
    }

    /*
    * ??????IPA???Info.plist?????????
    * */
    private fun parsInfoPlist(
        infoPlist: File,
        zhStrings: File?
    ): IpaInfoPlist {
        try {
            val rootDict = PropertyListParser.parse(infoPlist) as NSDictionary
            // ????????????
            if (!rootDict.containsKey("CFBundleIdentifier")) {
                throw AtomException("no CFBundleIdentifier find in plist")
            }
            var parameters = rootDict.objectForKey("CFBundleIdentifier") as NSString
            val bundleIdentifier = parameters.toString()
            // ????????????
            if (!rootDict.containsKey("CFBundleName")) throw AtomException("no CFBundleName find in plist")
            parameters = rootDict.objectForKey("CFBundleName") as NSString
            val appTitle = parameters.toString()
            // ????????????
            if (!rootDict.containsKey("CFBundleShortVersionString")) {
                throw AtomException("no CFBundleShortVersionString find in plist")
            }
            parameters = rootDict.objectForKey("CFBundleShortVersionString") as NSString
            val bundleVersion = parameters.toString()
            // ??????????????????
            if (!rootDict.containsKey("CFBundleVersion")) throw AtomException("no CFBundleVersion find in plist")
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
            } catch (e: AtomException) {
                ""
            }
            // ????????????
            val appName = try {
                val nameDictionary = if (zhStrings != null) {
                    PropertyListParser.parse(zhStrings) as NSDictionary
                } else {
                    rootDict
                }
                nameDictionary.objectForKey("CFBundleDisplayName").toString()
            } catch (e: AtomException) {
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
        } catch (e: AtomException) {
            throw AtomException("??????Info.plist??????: ${e.message}")
        }
    }

    /*
    * ??????????????????????????????
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

    private fun getIpaUnzipDir(signTmpDir: File): File {
        return File("$signTmpDir.unzipDir")
    }

    private fun getSignTmpDir(workspace: File): File {
        val dir = File(workspace, "${SIGN_TEMP_DIR}_${UUIDUtil.generate()}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMobileProvisionDir(signTmpDir: File): File {
        return File("$signTmpDir.mobileProvisionDir")
    }

    private fun getResultIpaFilename(file: File, suffix: String?): String {
        val suffixName = if (suffix.isNullOrBlank()) {
            "_enterprise_sign"
        } else suffix
        return file.nameWithoutExtension + suffixName + "." + file.extension
    }

    private fun handleEntitlement(entitlementFile: File) {
        val rootDict = PropertyListParser.parse(entitlementFile) as NSDictionary

        // ??????keychain-access-groups????????????com.apple.token
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
}
