package com.tencent.bk.devops.atom.pojo

import lombok.Data
import lombok.EqualsAndHashCode

@Data
@EqualsAndHashCode(callSuper = true)
class SignAtomParam : AtomBaseParam() {
    var ipaPath: String? = null
    var profileType: String? = null
    var certId: String? = null
    var profileStorage: String? = null
    var mainProfileInTicket: String? = null
    var mainProfileOnLocal: String? = null
    var replaceBundleId: Boolean? = true
    var customize = false
    var destPath: String? = null
    var appexListInTicket: String? = null
    var appexListOnLocal: String? = null
    var appexListResultMap: List<ParamMap>? = null
    var replaceKeyList: String? = null
    var replaceKeyArrayList: List<ParamMap>? = null
    var ul: String? = null
    var keychainAccessGroups: String? = null
    var resultSuffix: String? = null
}
