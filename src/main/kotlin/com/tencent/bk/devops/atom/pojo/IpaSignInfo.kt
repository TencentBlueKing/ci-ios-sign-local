package com.tencent.bk.devops.atom.pojo

data class IpaSignInfo(

    var projectId: String,

    var pipelineId: String,

    var buildId: String,

    var wildcard: Boolean,

    var certId: String,

    var userId: String = "",

    var fileSize: Long? = null,

    var md5: String? = null,

    var archiveType: String? = "PIPELINE",

    var taskId: String? = null,

    var archivePath: String? = "/",

    var mobileProvisionInfoMap: Map<String, MobileProvisionInfo>,

    var universalLinks: List<String>? = null,

    var keychainAccessGroups: List<String>? = null,

    var replaceBundleId: Boolean? = false,

    var appexSignInfo: List<AppexSignInfo>? = null,

    var replaceKeyList: Map<String, String>? = null,

    var buildNum: Int? = null,

    var resultSuffix: String? = null
)
