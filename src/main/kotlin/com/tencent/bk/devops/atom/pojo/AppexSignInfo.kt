package com.tencent.bk.devops.atom.pojo

//@ApiModel("项目信息")
data class AppexSignInfo(
//    @ApiModelProperty("appex拓展应用名", required = true)
    val appexName: String,
//    @ApiModelProperty("扩展App对应描述文件ID", required = true)
    val mobileProvisionId: String
)