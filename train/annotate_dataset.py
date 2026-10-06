#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 DashScope Qwen-VL-Max 批量标注触摸采集的游戏截图。

流程：
1. 读取 dataset/labels/*.json，找出 annotated=false 的
2. 对每张对应截图调用 Qwen-VL 识别界面类型 + 按钮
3. 把结果写回 labels/*.json（annotated=true + screenType + buttons）

依赖：
    pip install dashscope
    export DASHSCOPE_API_KEY=sk-xxx

支持断点续传（已 annotated 的跳过）和并发（默认 3 并发）。
"""
import os
import json
import glob
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    import dashscope
    from dashscope import MultiModalConversation
except ImportError:
    print("错误：需要安装 dashscope，请先执行 pip install dashscope")
    sys.exit(1)

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
DATASET_DIR = "dataset"
MODEL = "qwen-vl-max"
CONCURRENCY = 3

PROMPT = (
    "这是一张游戏截图。请识别：\n"
    "1. 当前是什么界面（如：主界面、委托界面、战斗界面、加载中等）\n"
    "2. 画面中有哪些按钮或可交互元素，输出每个元素的文本和中心坐标（用图片比例坐标 0.0-1.0）\n"
    '输出 JSON 格式：{"screenType": "...", "buttons": [{"text": "...", "x": 0.5, "y": 0.8}]}'
)


def extract_json(text):
    """从模型输出中提取 JSON 对象。"""
    text = text.strip()
    # 去掉可能的 markdown 代码块
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    try:
        return json.loads(text)
    except Exception:
        # 尝试提取 { ... }
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except Exception:
                pass
    return None


def annotate_one(image_path):
    """调用 Qwen-VL 标注单张图，返回结果 dict。"""
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
        sys.exit(1)

    labels_dir = os.path.join(DATASET_DIR, "labels")
    images_dir = os.path.join(DATASET_DIR, "images")

    if not os.path.isdir(labels_dir):
        print(f"错误：{labels_dir} 不存在，请先解压采集数据")
        sys.exit(1)

    label_files = sorted(glob.glob(os.path.join(labels_dir, "*.json")))

    # 找未标注的
    todo = []
    for lf in label_files:
        with open(lf, encoding="utf-8") as f:
            data = json.load(f)
        if not data.get("annotated", False):
            todo.append(lf)

    if not todo:
        print("没有未标注的数据，全部已完成")
        return

    print(f"待标注：{len(todo)} 张，并发 {CONCURRENCY}")

    total = len(todo)
    success = 0
    failed = 0
    screen_types = {}

    def process(lf):
        with open(lf, encoding="utf-8") as f:
            data = json.load(f)
        img_name = data.get("screenshot", "")
        img_path = os.path.join(images_dir, img_name)
        if not os.path.exists(img_path):
            return lf, False, f"截图不存在：{img_name}", None
        result, err = annotate_one(img_path)
        if result is None:
            return lf, False, err, None
        # 写回
        data["annotated"] = True
        data["screenType"] = result.get("screenType", "其他")
        data["buttons"] = result.get("buttons", [])
        with open(lf, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return lf, True, None, data.get("screenType")

    done = 0
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(process, lf): lf for lf in todo}
        for fut in as_completed(futures):
            lf, ok, err, st = fut.result()
            done += 1
            if ok:
                success += 1
                screen_types[st] = screen_types.get(st, 0) + 1
            else:
                failed += 1
                print(f"[{done}/{total}] 失败 {os.path.basename(lf)}: {err}")
            if done % 10 == 0:
                print(f"进度 {done}/{total}（成功 {success} 失败 {failed}）")

    print("\n=== 汇总 ===")
    print(f"总处理：{total}")
    print(f"成功：{success}")
    print(f"失败：{failed}")
    print("界面分布：")
    for k, v in sorted(screen_types.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
