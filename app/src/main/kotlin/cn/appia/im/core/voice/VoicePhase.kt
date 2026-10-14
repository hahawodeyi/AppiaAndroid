package cn.appia.im.core.voice

/**
 * RN lib/voice/voiceTypes.ts:3-13 VoicePhase —— 通话会话相位（状态机/决策函数共用）。
 */
enum class VoicePhase {
    Idle,
    OutgoingRinging,
    IncomingRinging,
    Connecting,
    InCall,
    Ending,
    Rejected,
    Busy,
    Timeout,
    Cancelled,
}
