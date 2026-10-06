#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 annotate_images.py 产出的 annotations.json 转换为 Qwen2-VL SFT 格式。

- 读取 dataset/annotations.json
- 只保留 is_game=true 的（过滤 App 界面/多任务/照片等无效帧）
- 生成 train/output/dataset.jsonl + train/output/images/

用法：
    python convert_annotations_to_sft.py
"""
import os
import json
import shutil

DATASET_DIR = "dataset"
IMAGES_DIR = os.path.join(DATASET_DIR, "images")
ANNOTATIONS_FILE = os.path.join(DATASET_DIR, "annotations.json")
OUTPUT_DIR = "output"


def fmt_coord(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def main():
    if not os.path.exists(ANNOTATIONS_FILE):
        print(f"错误：{ANNOTATIONS_FILE} 不存在，请先运行 annotate_images.py")
        return

    with open(ANNOTATIONS_FILE, encoding="utf-8") as f:
        annotations = json.load(f)

    out_images = os.path.join(OUTPUT_DIR, "images")
    os.makedirs(out_images, exist_ok=True)

    lines = []
    count = 0
    skipped_non_game = 0
    skipped_missing = 0
    screen_types = {}

    for name in sorted(annotations.keys()):
        data = annotations[name]
        if not data.get("is_game", True):
            skipped_non_game += 1
            continue
        src = os.path.join(IMAGES_DIR, name)
        if not os.path.exists(src):
            skipped_missing += 1
            continue

        shutil.copy(src, os.path.join(out_images, name))

        screen_type = data.get("screenType", "其他")
        buttons = data.get("buttons", [])
        if buttons:
            button_str = "、".join(
                f"{b.get('text', '按钮')}({fmt_coord(b.get('x', 0))}, {fmt_coord(b.get('y', 0))})"
                for b in buttons
            )
            assistant = f"界面类型：{screen_type}。按钮：{button_str}。"
        else:
            assistant = f"界面类型：{screen_type}。"

        record = {
            "image": f"images/{name}",
            "conversations": [
                {"from": "user", "value": "<image>\n请描述这张游戏截图中的界面类型和所有按钮。"},
                {"from": "assistant", "value": assistant},
            ],
        }
        lines.append(json.dumps(record, ensure_ascii=False))
        count += 1
        screen_types[screen_type] = screen_types.get(screen_type, 0) + 1

    out_jsonl = os.path.join(OUTPUT_DIR, "dataset.jsonl")
    with open(out_jsonl, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print("=== 汇总 ===")
    print(f"生成 {count} 条 SFT 数据")
    print(f"过滤非游戏画面：{skipped_non_game} 张")
    print(f"截图缺失：{skipped_missing} 张")
    print("界面分布：")
    for k, v in sorted(screen_types.items(), key=lambda x: -x[1]):
        print(f"  {k}: {v}")
    print(f"\n输出：{out_jsonl}")
    print(f"图片：{out_images}/（{count} 张）")


if __name__ == "__main__":
    main()
