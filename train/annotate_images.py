#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 DashScope Qwen-VL 批量标注触摸采集的游戏截图（v2）。

相比 annotate_dataset.py 的差异：
- 直接遍历 dataset/images/*.jpg（不依赖 labels/*.json，因为导出 zip 不带 labels）
- 每次标注额外判断「是否游戏画面」（is_game），用于过滤 App 自身界面、多任务页、照片等无效帧
- 结果写入 dataset/annotations.json（断点续传：已标注的跳过）
- 支持并发（默认 3），避免限流

依赖：
    pip install dashscope
    export DASHSCOPE_API_KEY=sk-xxx

用法：
    python annotate_images.py
"""
import os
import json
import glob
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    from dashscope import MultiModalConversation
except ImportError:
    print("错误：需要安装 dashscope，请先执行 pip install dashscope")
    sys.exit(1)

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
DATASET_DIR = "dataset"
IMAGES_DIR = os.path.join(DATASET_DIR, "images")
ANNOTATIONS_FILE = os.path.join(DATASET_DIR, "annotations.json")
MODEL = "qwen-vl-max"
CONCURRENCY = 3

PROMPT = (
    "这是一张手机截图。请先判断它是不是【游戏画面】（3D游戏/二次元游戏等，有角色、场景、UI按钮的游戏界面）。"
    "如果是游戏画面，请识别：\n"
    "1. 当前是什么界面（如：主界面、委托界面、战斗界面、对话界面、加载中、背包、地图等）\n"
    "2. 画面中有哪些按钮或可交互元素，输出每个元素的文本和中心坐标（用图片比例坐标 0.0-1.0）\n\n"
    "如果不是游戏画面（比如：手机桌面、系统设置、应用商店、多任务切换、纯黑屏、真实照片等），"
    "screenType 填 \"非游戏\"，buttons 填空数组。\n\n"
    '严格输出 JSON 格式（不要任何解释、不要 markdown）：'
    '{"is_game": true/false, "screenType": "...", "buttons": [{"text": "...", "x": 0.5, "y": 0.8}]}'
)


def extract_json(text):
    """从模型输出中提取 JSON 对象。"""
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    try:
        return json.loads(text)
    except Exception:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(text[start:end + 1])
        except Exception:
            pass
    return None


def annotate_one(image_path):
    """调用 Qwen-VL 标注单张图，返回 (result_dict, error)。"""
    try:
        response = MultiModalConversation.call(
            model=MODEL,
            api_key=DASHSCOPE_API_KEY,
            messages=[{
                "role": "user",
                "content": [
                    {"image": f"file://{image_path}"},
                    {"text": PROMPT},
                ],
            }],
        )
        content = response.output.choices[0].message.content
        text = content[0]["text"] if isinstance(content, list) else content
        result = extract_json(text)
        if result is None:
            return None, f"无法解析输出：{text[:100]}"
        return result, None
    except Exception as e:
        return None, str(e)


def main():
    if not DASHSCOPE_API_KEY:
        print("错误：未设置 DASHSCOPE_API_KEY 环境变量")
        print("用法：export DASHSCOPE_API_KEY=sk-xxx && python annotate_images.py")
        sys.exit(1)

    if not os.path.isdir(IMAGES_DIR):
        print(f"错误：{IMAGES_DIR} 不存在，请先解压采集数据")
        sys.exit(1)

    # 加载已有标注（断点续传）
    annotations = {}
    if os.path.exists(ANNOTATIONS_FILE):
        with open(ANNOTATIONS_FILE, encoding="utf-8") as f:
            annotations = json.load(f)
        print(f"已有标注：{len(annotations)} 张，继续断点续传")

    image_files = sorted(glob.glob(os.path.join(IMAGES_DIR, "*.jpg")))
    todo = [f for f in image_files if os.path.basename(f) not in annotations]
    if not todo:
        print("没有待标注的图片，全部已完成")
        return

    total = len(todo)
    success = 0
    failed = 0
    game_count = 0
    non_game_count = 0
    screen_types = {}

    print(f"待标注：{total} 张，并发 {CONCURRENCY}，模型 {MODEL}")

    def process(img_path):
        result, err = annotate_one(img_path)
        if result is None:
            return os.path.basename(img_path), None, err
        return os.path.basename(img_path), result, None

    done = 0
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(process, p): p for p in todo}
        for fut in as_completed(futures):
            name, result, err = fut.result()
            done += 1
            if result is None:
                failed += 1
                print(f"[{done}/{total}] 失败 {name}: {err}")
            else:
                success += 1
                annotations[name] = result
                is_game = result.get("is_game", True)
                st = result.get("screenType", "其他")
                if is_game:
                    game_count += 1
                    screen_types[st] = screen_types.get(st, 0) + 1
                else:
                    non_game_count += 1
                # 每 10 张存一次，防止中途崩溃丢进度
                if done % 10 == 0:
                    with open(ANNOTATIONS_FILE, "w", encoding="utf-8") as f:
                        json.dump(annotations, f, ensure_ascii=False, indent=2)
                    print(f"进度 {done}/{total}（游戏 {game_count} 非游戏 {non_game_count} 失败 {failed}）")

    # 最终落盘
    with open(ANNOTATIONS_FILE, "w", encoding="utf-8") as f:
        json.dump(annotations, f, ensure_ascii=False, indent=2)

    print("\n=== 汇总 ===")
    print(f"总处理：{total}")
    print(f"成功：{success}")
    print(f"失败：{failed}")
    print(f"游戏画面：{game_count}")
    print(f"非游戏画面：{non_game_count}")
    print("游戏界面分布：")
    for k, v in sorted(screen_types.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")
    print(f"\n标注结果：{ANNOTATIONS_FILE}")


if __name__ == "__main__":
    main()
