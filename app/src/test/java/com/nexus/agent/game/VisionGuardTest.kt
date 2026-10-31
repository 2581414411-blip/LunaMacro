package com.nexus.agent.game

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉 side 的「防崩」判定单测。
 *
 * 背景：用户点「测试视觉模型」直接闪退，且 **Java 层一条日志都没有** ——
 * 这是 native 崩溃（SIGSEGV/SIGBUS/SIGABRT）的典型特征，`uncaughtException` 根本不会触发。
 * 所以这里的逻辑要保证两件事：
 *  1. 加载阶段的崩溃要能拦住自动重试，别让用户每开一次 App 就崩一次；
 *  2. 文件比官方包小（没下完）时，连 native 都别进。
 */
class VisionGuardTest {

    // ------------------------------------------------ 崩溃阶段 → 是否拦自动加载

    @Test
    fun `加载 so 时崩溃要拦住自动加载`() {
        assertTrue(VisionStage.blocksAutoLoad("native:loadLibrary:MNNOpenCV"))
        assertTrue(VisionStage.blocksAutoLoad("native:loadLibrary:llm"))
    }

    @Test
    fun `初始化模型时崩溃要拦住自动加载`() {
        assertTrue(VisionStage.blocksAutoLoad("native:init:/storage/emulated/0/Android/data/x/files/models"))
    }

    @Test
    fun `拆开后的创建与加载两步都要拦`() {
        // 0.8.8 把 native:init 拆成 create / load 两步，分别定位「建不出对象」和「加载权重崩」
        assertTrue(VisionStage.blocksAutoLoad("native:create"))
        assertTrue(VisionStage.blocksAutoLoad("native:load"))
    }

    @Test
    fun `推理时崩溃不拦自动加载`() {
        // 模型能起来，只是这一帧没算完；一崩就永久禁用反而像功能坏了
        assertFalse(VisionStage.blocksAutoLoad("native:recognize"))
    }

    @Test
    fun `没有崩溃记录时不拦`() {
        assertFalse(VisionStage.blocksAutoLoad(null))
        assertFalse(VisionStage.blocksAutoLoad(""))
        assertFalse(VisionStage.blocksAutoLoad("   "))
    }

    // ------------------------------------------------ 文件大小体检

    @Test
    fun `比官方包小就判定为截断`() {
        val c = LocalScreenRecognizer.FileCheck("llm.mnn.weight", actual = 500L, expected = 965_665_010L)
        assertFalse(c.ok)
    }

    @Test
    fun `和官方包一样大算正常`() {
        val c = LocalScreenRecognizer.FileCheck("llm.mnn.weight", actual = 965_665_010L, expected = 965_665_010L)
        assertTrue(c.ok)
    }

    @Test
    fun `比官方包大不拦（用户自己换了量化版本）`() {
        val c = LocalScreenRecognizer.FileCheck("llm.mnn.weight", actual = 2_000_000_000L, expected = 965_665_010L)
        assertTrue(c.ok)
    }

    @Test
    fun `没有官方尺寸的文件不做大小判定`() {
        val c = LocalScreenRecognizer.FileCheck("whatever.bin", actual = 1L, expected = -1L)
        assertTrue(c.ok)
    }

    // ------------------------------------------------ 内存门槛

    @Test
    fun `内存不足时不装载`() {
        assertFalse(LocalScreenRecognizer.ramSufficient(800L))
        assertFalse(LocalScreenRecognizer.ramSufficient(1799L))
        assertTrue(LocalScreenRecognizer.ramSufficient(1800L))
        assertTrue(LocalScreenRecognizer.ramSufficient(4096L))
    }

    @Test
    fun `读不到内存时不拦（宁可不拦也不能锁死功能）`() {
        assertTrue(LocalScreenRecognizer.ramSufficient(-1L))
        assertTrue(LocalScreenRecognizer.ramSufficient(0L))
    }

    // ------------------------------------------------ 大小格式化

    @Test
    fun `大小格式化按量级切换单位`() {
        // 1024 进制：417,802,720 B = 398.4 MiB（和文件管理器看到的一致）
        assertEquals("661 B", LocalScreenRecognizer.formatSize(661))
        assertEquals("4.0 KB", LocalScreenRecognizer.formatSize(4_108))
        assertEquals("398.4 MB", LocalScreenRecognizer.formatSize(417_802_720))
        // 不足 1 GB 仍按 MB 显示（965 MB 比 “0.90 GB” 更直观）
        assertEquals("920.9 MB", LocalScreenRecognizer.formatSize(965_665_010))
        assertEquals("2.00 GB", LocalScreenRecognizer.formatSize(2_147_483_648))
    }

    // ------------------------------------------------ create 的入参（0.8.9 的根因）

    /**
     * 钉死一件事：传给 native 的必须是 `config.json` 的路径，不是模型目录。
     *
     * 传目录时 MNN 的 LlmConfig 会把整串原样当 base_dir 且**不补结尾斜杠**，
     * 之后 `base_dir + "llm_config.json"` = `.../modelsllm_config.json`，
     * 配置读空、模型路径全错 —— 这份 libllm.so 的 load() 又不做存在性检查，
     * 于是必然崩在 Module::load。真机上的表现就是「create 成功、load 必崩」。
     */
    @Test
    fun `create 的入参是 config_json 而不是模型目录`() {
        val dir = File("/storage/emulated/0/Android/data/com.nexus.agent/files/models")
        val path = LocalScreenRecognizer.configPathFor(dir)
        assertTrue("必须以 config.json 结尾：$path", path.endsWith("config.json"))
        assertFalse("不能直接传目录（会让 MNN 拼出 modelsllm_config.json）：$path", path.endsWith("models"))
        assertTrue("仍要指向同一个模型目录：$path", path.contains("models"))
    }

    @Test
    fun `目录末尾带斜杠时也要拼出正确路径`() {
        val dir = File("/tmp/models/")
        val path = LocalScreenRecognizer.configPathFor(dir)
        assertTrue("必须以 config.json 结尾：$path", path.endsWith("config.json"))
        assertFalse("不能出现两个斜杠：$path", path.contains("//"))
    }
}
