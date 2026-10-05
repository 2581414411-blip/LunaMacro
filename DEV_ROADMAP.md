# 宏月开发路线与决策记录

## 一、产品定位
- 名称：宏月（英文 LunaMacro）
- 定位：Android 系统级 AI 操作助手
- 目标：语音唤醒 → 自动执行跨应用任务 / 非竞技游戏托管

## 二、游戏 Agent 架构决策
三层架构：
1. 高层规划（云端主模型）：理解任务，生成步骤列表，只在开局和异常时调用
2. 中层识别（本地小模型）：Qwen2-VL-1.5B，MNN 部署，识别当前界面和按钮
3. 底层执行（规则引擎）：if-then 逻辑，毫秒级决策，每秒循环 2-6 次

关键约束：
- 主模型不负责“怎么做”，只负责“做什么”
- 本地小模型只负责“看”，不负责“决策”
- 规则引擎负责“决策”，不做识别
- Macro（宏）降级为规则引擎的动作类型之一，不作为主路径
- 竞技游戏不可达（INJECT_EVENTS 是厂商签名独占）

## 三、规则引擎设计决策
- 预设模板为主：开发者为热门游戏做好默认规则包，用户装上即用
- 自然语言微调为辅：高级用户可以说一句话调整参数，入口隐藏（长按触发）
- 规则引擎不需要用户录制，配置方式是表单或自然语言生成
- 卡住检测：连续 30 秒无进展 → 通知用户 + 保存截图 + 停止

## 四、本地模型训练决策
- 训练硬件：E5-2670V3 + 16GB DDR4 + Tesla V100 16G + GTX 750 Ti（训练 PC）
- 训练系统：纯 Windows（放弃 Linux 双系统）
- 显卡分工：750 Ti 负责显示，V100 切 TCC 模式负责计算
- 微调方法：LoRA（r=16, alpha=32）+ 4-bit 量化
- 框架：HuggingFace transformers + peft + accelerate
- 精度：FP16（V100 不支持 BF16）
- Attention：eager（V100 不支持 Flash Attention 2）
- 训练数据来源：手机端采集 + 自动标注，导出 dataset.jsonl + images/

## 五、SFT 数据流
1. 手机端：游戏中运行 Agent，自动截图 + 自动标注
2. 手机端：导出 dataset.jsonl + images/ 打包为 zip
3. PC 端：解压，用 train_lora.py 微调 Qwen2-VL-1.5B
4. PC 端：LoRA 权重合并后转 MNN 格式
5. 手机端：放回 app/src/main/assets/models/，替换通用模型

## 六、待办事项
- [ ] 手机端 SFT 数据采集工具（DatasetCollector / DatasetAnnotator / DatasetExporter）
- [ ] 游戏层基础实现（如果 game/ 目录不完整）
- [ ] 采集 500-1000 张原神标注截图
- [ ] 训练 PC 上搭建 Windows Python 环境
- [ ] 跑通第一轮 LoRA 微调
- [ ] 微调模型转 MNN，部署回手机
- [ ] 验证微调后的识别效果

## 七、版本规则
详见 VERSION_RULES.md。默认每次打新包 patch +1。

## 八、品牌统一
- 应用名：宏月
- 所有用户可见文案中的 "Nexus Agent" 应替换为 "宏月"
- 包名 com.nexus.agent 保持不变（避免重构风险）
- 类名保持不变（NexusApplication / NexusAccessibilityService 等）
