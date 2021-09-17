package com.tencent.bk.devops.atom.pojo

class KvParam {
    var id: String = ""
    var value: String = ""

    override fun toString(): String {
        return "{$id,$value}"
    }
}