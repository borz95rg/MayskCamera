package com.maysk.camera.ar3d

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniJsonTest {
    @Test fun parsesNested() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":{"c":"d\"Ж"},"t":true,"n":null}""").obj()
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("d\"Ж", v["b"].obj()["c"])
        assertEquals(true, v["t"])
        assertEquals(null, v["n"])
    }
}
