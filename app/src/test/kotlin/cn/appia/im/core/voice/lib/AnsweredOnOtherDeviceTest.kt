package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isVoiceAnsweredOnOtherDevice（RN isVoiceAnsweredOnOtherDevice.test.ts 全用例转写）。
 */
class AnsweredOnOtherDeviceTest {

    /** RN callMsg 夹具。 */
    private fun callMsg(): VoiceCallMsg = VoiceCallMsg(
        status = "talking",
        userStatus = listOf(
            VoiceUserStatus(username = "bob", status = "in", operation = "accept", deviceId = "device-a"),
        ),
    )

    @Test
    fun `returns true when self is in on another device`() {
        // RN 'returns true when self is in on another device'
        assertTrue(isVoiceAnsweredOnOtherDevice(callMsg(), "bob", "device-b"))
    }

    @Test
    fun `returns false when deviceId matches`() {
        // RN 'returns false when deviceId matches'
        assertFalse(isVoiceAnsweredOnOtherDevice(callMsg(), "bob", "device-a"))
    }

    @Test
    fun `returns false for waiting invite`() {
        // RN 'returns false for waiting invite'
        val msg = VoiceCallMsg(
            userStatus = listOf(
                VoiceUserStatus(username = "bob", status = "out", operation = "waiting", deviceId = "device-a"),
            ),
        )
        assertFalse(isVoiceAnsweredOnOtherDevice(msg, "bob", "device-b"))
    }

    @Test
    fun `returns false when row has no deviceId`() {
        // RN 'returns false when row has no deviceId'
        val msg = VoiceCallMsg(
            userStatus = listOf(VoiceUserStatus(username = "bob", status = "in", operation = "accept")),
        )
        assertFalse(isVoiceAnsweredOnOtherDevice(msg, "bob", "device-b"))
    }
}
