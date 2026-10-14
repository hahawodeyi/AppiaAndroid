package cn.appia.im.core.voice

/**
 * 语音网关环境常量（逐行为移植 appiaMobile/src/services/voice/voiceEnv.ts +
 * lib/voice/agoraVoiceConstants.ts）。
 */
object VoiceEnv {
    /** voiceEnv.ts:1-2 网关域名。 */
    const val PROD_HOST = "shengwang-server.appia.vip"
    const val QA_HOST = "shengwang-server-qa.appia.vip"

    /** voiceEnv.ts:3 / getAgoraAppId：声网 App ID（对齐旧版 Config）。 */
    const val AGORA_APP_ID = "fbb72b90d7f34c3487bf65d15c2b40bb"

    /** agoraVoiceConstants.ts:2 主叫固定 Agora UID（lib/AgoraVoiceConstants.kt 同值，此处聚合供 Controller）。 */
    const val VOICE_HOSTER_AGORA_UID: Long = 8888L

    /** voiceEnv.ts:5-6 getShengwangApiHost：`.dev.` → QA，否则 prod。 */
    fun shengwangApiHost(rocketServerUrl: String): String =
        if (rocketServerUrl.contains(".dev.")) QA_HOST else PROD_HOST

    /** voiceEnv.ts:11-12 resolveVoiceOrg：Enterprise_ID trim，空 → 'bitmain'（对齐旧版 mapState）。 */
    fun resolveVoiceOrg(enterpriseId: String?): String =
        enterpriseId?.trim()?.ifEmpty { null } ?: "bitmain"
}
