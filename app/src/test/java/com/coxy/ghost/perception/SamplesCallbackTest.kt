package com.coxy.ghost.perception

import org.junit.Assert.*
import org.junit.Test

class SamplesCallbackTest {
    @Test fun cancellationReachesNativeCallback() {
        val callback = SamplesCallback { 0 }
        assertEquals(0, callback.invoke(floatArrayOf(.2f)))
        assertEquals(1, SamplesCallback { 1 }.invoke(floatArrayOf(.2f)))
    }
    @Test fun exactJniDescriptorRemainsBoxedInteger() {
        val method = SamplesCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java, method.returnType)
    }
}
