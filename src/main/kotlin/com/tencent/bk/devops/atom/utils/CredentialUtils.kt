package com.tencent.bk.devops.atom.utils

import com.tencent.bk.devops.atom.exception.ClientException
import com.tencent.bk.devops.atom.api.CertsResourceApi
import java.io.File
import java.util.Base64
import org.slf4j.LoggerFactory

/**
 * This util is to get the credential from core
 * It use DH encrypt and decrypt
 */
object CredentialUtils {

    fun getEnterpriseCertFile(certId: String, mobileProvisionDir: File ): File {
        val certNameAndContent = getEnterpriseCert(certId)
        val certFile = File(mobileProvisionDir.absolutePath + File.separator + certNameAndContent.first)
        certFile.writeText(certNameAndContent.second)
        return certFile
    }

    private fun getEnterpriseCert(certId: String): Pair<String, String> {
        if (certId.trim().isEmpty()) {
            throw RuntimeException("The CertEnterprise Id is empty")
        }
        try {
            val pair = DHUtil.initKey()
            val encoder = Base64.getEncoder()
            logger.info("Start to get the CertEnterprise($certId)")
            val result = CertsResourceApi().get(certId, encoder.encodeToString(pair.publicKey))
            val iosCert = result.data
            logger.info("Fetch CertEnterprise successfully: " +
                "mobileProvisionFileName=${iosCert.mobileProvisionFileName}" +
                "mobileProvisionSha1=${iosCert.mobileProvisionSha1}")
            return Pair(
                iosCert.mobileProvisionFileName,
                decode(iosCert.mobileProvisionContent, iosCert.publicKey, pair.privateKey)
            )
        } catch (e: Exception) {
            logger.error("Fail to get the CertEnterprise($certId)", e)
            throw e
        }
    }

    private fun decode(encode: String, publicKey: String, privateKey: ByteArray): String {
        val decoder = Base64.getDecoder()
        return String(DHUtil.decrypt(decoder.decode(encode), decoder.decode(publicKey), privateKey))
    }

    private val logger = LoggerFactory.getLogger(CredentialUtils::class.java)
}
