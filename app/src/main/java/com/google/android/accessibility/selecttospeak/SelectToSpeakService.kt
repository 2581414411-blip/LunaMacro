package com.google.android.accessibility.selecttospeak

import com.nexus.agent.accessibility.NexusAccessibilityService

/**
 * 无障碍服务对外伪装类。
 *
 * 借鉴「喵喵助手」的保活手段：Manifest 中对外声明为
 * Google 官方「朗读」（Select to Speak）无障碍服务的完整类名
 * `com.google.android.accessibility.selecttospeak.SelectToSpeakService`，
 * 让 vivo/小米等厂商 ROM 的后台清理白名单将其误判为系统自带无障碍服务，
 * 从而降低被系统强杀的概率。
 *
 * 实际逻辑完全复用 [NexusAccessibilityService]。
 *
 * 注意：这是「规避」而非「合规」手段，仅适用于自用 sideload 场景。
 */
class SelectToSpeakService : NexusAccessibilityService()
