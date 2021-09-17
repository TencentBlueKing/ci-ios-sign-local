package com.tencent.bk.devops.atom.utils

import com.tencent.bk.devops.atom.api.CertsResourceApi
import java.io.File
import java.util.Base64
import org.slf4j.LoggerFactory

/**
 * This util is to get the credential from core
 * It use DH encrypt and decrypt
 */
object CertUtils {

    fun getEnterpriseCertFile(certId: String, mobileProvisionDir: File ): File {
        val certNameAndContent = getEnterpriseCert(certId)
        val certFile = File(mobileProvisionDir.absolutePath + File.separator + certNameAndContent.first)
        certFile.writeBytes(certNameAndContent.second)
        return certFile
    }

    private fun getEnterpriseCert(certId: String): Pair<String, ByteArray> {
        if (certId.trim().isEmpty()) {
            throw RuntimeException("The CertEnterprise Id is empty")
        }
        try {
            val pair = DHUtil.initKey()
            logger.info("Start to get the CertEnterprise($certId)")
            val result = CertsResourceApi().get(certId, String(Base64.getEncoder().encode(pair.publicKey)))
            val iosCert = result.data
            logger.info("Fetch CertEnterprise successfully: " +
                "mobileProvisionFileName=${iosCert.mobileProvisionFileName}" +
                "mobileProvisionSha1=${iosCert.mobileProvisionSha1}")
            val publicKeyServer = Base64.getDecoder().decode(iosCert.publicKey)
            val mpContent = Base64.getDecoder().decode(iosCert.mobileProvisionContent)
            val mobileProvision = DHUtil.decrypt(mpContent, publicKeyServer, pair.privateKey)
            return Pair(
                iosCert.mobileProvisionFileName,
                mobileProvision
            )
        } catch (e: Exception) {
            logger.error("Fail to get the CertEnterprise($certId)", e)
            throw e
        }
    }

    private val logger = LoggerFactory.getLogger(CertUtils::class.java)
}
