# -*- coding: utf-8 -*-
"""生成 assets/pinyin.txt：汉字 -> 带声调拼音，并校验能拆成 sherpa KWS 的音素 token"""
import io, os, sys
from pypinyin import pinyin, Style

ROOT = r"C:\Users\25814\WorkBuddy\2026-09-27-13-06-33\NexusAgent"
TOKENS = os.path.join(ROOT, "app", "src", "main", "assets", "kws", "tokens.txt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "pinyin.txt")

# 1) 读 tokens
tokens = set()
with io.open(TOKENS, encoding="utf-8") as f:
    for line in f:
        for t in line.split():
            tokens.add(t)
print("tokens:", len(tokens))

# 2) GB2312 汉字区
chars = []
for b1 in range(0xB0, 0xF8):
    for b2 in range(0xA1, 0xFF):
        try:
            ch = bytes((b1, b2)).decode("gb2312")
        except Exception:
            continue
        if "\u4e00" <= ch <= "\u9fff":
            chars.append(ch)
# 补充几个常用但不在 GB2312 的
chars += list("噢喏嘞喽嗯呣噻囧")
seen, uniq = set(), []
for c in chars:
    if c not in seen:
        seen.add(c)
        uniq.append(c)
print("汉字数:", len(uniq))

# 3) 转拼音 + 拆声母韵母
INITIALS = ["zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"]


def split_py(py):
    for ini in INITIALS:
        if py.startswith(ini) and len(py) > len(ini):
            return ini, py[len(ini):]
    return "", py


rows, bad_tok, no_py = [], set(), []
for ch in uniq:
    py = pinyin(ch, style=Style.TONE, heteronym=False, errors="ignore")
    py = py[0][0] if py and py[0] else ""
    if not py or not py.strip():
        no_py.append(ch)
        continue
    ini, fin = split_py(py)
    # 只保留声母/韵母都能在 KWS token 表里找到的字，其余（部首、特殊叹词）剔除
    if any(t and t not in tokens for t in (ini, fin)):
        bad_tok.add((py, ch))
        continue
    rows.append((ch, py))

print("成功:", len(rows), "无拼音:", len(no_py))
print("无法匹配 token 的音素:", len(bad_tok))
for t in sorted(bad_tok)[:30]:
    print("   ", t)

# 4) 落盘
with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
    for ch, py in rows:
        f.write("%s\t%s\n" % (ch, py))
print("写出:", OUT, os.path.getsize(OUT), "B")
