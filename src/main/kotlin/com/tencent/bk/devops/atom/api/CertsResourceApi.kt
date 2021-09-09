package com.tencent.bk.devops.atom.api

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.bk.devops.atom.exception.AtomException
import com.tencent.bk.devops.atom.pojo.ticket.CertEnterprise
import com.tencent.bk.devops.atom.utils.json.JsonUtil
import com.tencent.bk.devops.atom.pojo.Result

class CertsResourceApi : BaseApi() {
    fun get(certId: String, publicKey: String): Result<CertEnterprise> {
        try {
            val path = "/ticket/api/build/certs/enterprise/$certId?publicKey=${encode(publicKey)}"
            val request = buildGet(path)
            val responseContent = request(request, "获取凭据失败")
            return JsonUtil.fromJson(responseContent, object : TypeReference<Result<CertEnterprise>>() {})
        } catch (e: Throwable) {
            throw AtomException(e.message)
        }
    }
}
