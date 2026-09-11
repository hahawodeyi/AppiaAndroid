package cn.appia.im

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// 测试基建常驻冒烟：证明 JUnit Platform 测试发现生效
class SanityTest {
    @Test
    fun `junit platform discovers and runs tests`() {
        assertEquals(2, 1 + 1)
    }
}
