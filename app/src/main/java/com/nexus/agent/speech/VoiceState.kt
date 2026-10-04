package com.nexus.agent.speech

/**
 * 语音助手状态机。
 *
 * 取代原先的 listening/busy 两个布尔标志，用单一 [currentState] 表达当前状态，
 * 通过 [VoiceAssistant.transitionTo] 校验合法性，避免自由切换导致的状态错乱。
 */
enum class VoiceState {
    /** 待机，KWS 监听中 */
    IDLE,

    /** 录音中 */
    LISTENING,

    /** ASR 识别中 */
    TRANSCRIBING,

    /** 指令澄清中（指代澄清时使用） */
    CLARIFYING,

    /** Agent 执行中 */
    EXECUTING,

    /** TTS 播报中 */
    SPEAKING,

    /** 续听窗口 */
    FOLLOW_UP,

    /** 错误状态 */
    ERROR
}
