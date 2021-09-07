package com.tencent.bk.devops.atom.pojo

import com.fasterxml.jackson.annotation.JsonProperty

data class CertId(
    @JsonProperty("id")
    val id: String? = ""
)