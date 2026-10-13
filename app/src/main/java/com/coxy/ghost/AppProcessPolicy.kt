package com.coxy.ghost

internal object AppProcessPolicy {
    fun initializeUi(processName: String, packageName: String): Boolean =
        processName == packageName
}
