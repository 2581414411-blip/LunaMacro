#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把标注后的触摸采集数据转换为 Qwen2-VL SFT 格式。

读取 dataset/labels/*.json（只处理 annotated=true 的），
生成 train/output/dataset.jsonl + train/output/images/。

每条格式：
    {
      "image": "images/manual_xxx.jpg",
      "conversations": [
        {"from": "user", "value": "<image>\\n请描述这张游戏截图中的界面类型和所有按钮。"},
        {"from": "assistant", "value": "界面类型：委托界面。按钮：全部领取(0.5, 0.8)、返回(0.1, 0.1)。"}
      ]
    }
"""
import os
import json
import glob
import shutil

DATASET_DIR = "dataset"
OUTPUT_DIR = "train/output"


def fmt_coord(v):
    """坐标格式化为 2 位小数，去掉多余 0。"""
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def main():
    labels_dir = os.path.join(DATASET_DIR, "labels")
    images_dir = os.path.join(DATASET_DIR, "images")
    out_images = os.path.join(OUTPUT_DIR, "images")

    if not os.path.isdir(labels_dir):
        print(f"错误：{labels_dir} 不存在")
        return

    os.makedirs(out_images, exist_ok=True)

    lines = []
    count = 0
    for lf in sorted(glob.glob(os.path.join(labels_dir, "*.json"))):
        with open(lf, encoding="utf-8") as f:
            data = json.load(f)
        if not data.get("annotated", False):
            continue
        img_name = data.get("screenshot", "")
        if not img_name:
            continue
        src = os.path.join(images_dir, img_name)
        if not os.path.exists(src):
            print(f"跳过：截图不存在 {img_name}")
            continue

        shutil.copy(src, os.path.join(out_images, img_name))

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
            "image": f"images/{img_name}",
            "conversations": [
                {"from": "user", "value": "<image>\n请描述这张游戏截图中的界面类型和所有按钮。"},
                {"from": "assistant", "value": assistant},
            ],
        }
        lines.append(json.dumps(record, ensure_ascii=False))
        count += 1

    out_jsonl = os.path.join(OUTPUT_DIR, "dataset.jsonl")
    with open(out_jsonl, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"生成 {count} 条 SFT 数据")
    print(f"输出：{out_jsonl}")
    print(f"图片：{out_images}/（{count} 张）")


if __name__ == "__main__":
    main()
