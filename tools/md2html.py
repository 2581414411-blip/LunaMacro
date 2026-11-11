#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 Markdown → 单文件 HTML 渲染器（项目自用，无第三方依赖）。

只覆盖本仓库设计文档用到的语法：标题 / 段落 / 表格 / 围栏代码块 /
有序无序列表（一层嵌套）/ 引用 / 水平线 / 粗体 / 行内代码 / 链接。
不追求通用，追求「渲染结果与 md 完全一致且样式可读」。

用法：python tools/md2html.py <输入.md> <输出.html> ["标题"]
"""
import html
import re
import sys


def esc(s: str) -> str:
    return html.escape(s, quote=False)


def inline(s: str) -> str:
    """行内语法。先抽出 code span 占位，避免里面的 * 被当粗体。"""
    spans = []

    def keep(m):
        spans.append(m.group(1))
        return f"\x00{len(spans) - 1}\x00"

    s = re.sub(r"`([^`]+)`", keep, s)
    s = esc(s)
    s = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", s)
    s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', s)
    s = re.sub(r"\x00(\d+)\x00", lambda m: f"<code>{esc(spans[int(m.group(1))])}</code>", s)
    return s


def slug(text: str, used: dict) -> str:
    base = re.sub(r"[^\w\u4e00-\u9fff-]+", "-", text.strip().lower()).strip("-") or "sec"
    n = used.get(base, 0) + 1
    used[base] = n
    return base if n == 1 else f"{base}-{n}"


def render(md: str):
    lines = md.split("\n")
    out = []
    toc = []
    used = {}
    i = 0
    n = len(lines)
    para = []

    def flush_para():
        if para:
            out.append("<p>" + inline(" ".join(para).strip()) + "</p>")
            para.clear()

    while i < n:
        line = lines[i]
        stripped = line.strip()

        # 围栏代码块
        if stripped.startswith("```"):
            flush_para()
            lang = stripped[3:].strip() or "text"
            i += 1
            buf = []
            while i < n and not lines[i].strip().startswith("```"):
                buf.append(lines[i])
                i += 1
            i += 1
            out.append(
                f'<div class="code"><div class="code-lang">{esc(lang)}</div>'
                f"<pre><code>{esc(chr(10).join(buf))}</code></pre></div>"
            )
            continue

        # 空行
        if not stripped:
            flush_para()
            i += 1
            continue

        # 水平线
        if re.fullmatch(r"-{3,}|\*{3,}", stripped):
            flush_para()
            out.append("<hr>")
            i += 1
            continue

        # 标题
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            flush_para()
            level = len(m.group(1))
            text = m.group(2).strip()
            sid = slug(text, used)
            if level <= 2:
                toc.append((level, sid, text))
            out.append(f'<h{level} id="{sid}">{inline(text)}</h{level}>')
            i += 1
            continue

        # 表格：当前行以 | 开头，且下一行是分隔行
        if stripped.startswith("|") and i + 1 < n and re.fullmatch(
            r"\|[\s:|-]+\|?", lines[i + 1].strip()
        ):
            flush_para()
            header = [c.strip() for c in split_row(stripped)]
            i += 2
            body = []
            while i < n and lines[i].strip().startswith("|"):
                body.append([c.strip() for c in split_row(lines[i].strip())])
                i += 1
            out.append('<div class="table-wrap"><table><thead><tr>')
            for c in header:
                out.append(f"<th>{inline(c)}</th>")
            out.append("</tr></thead><tbody>")
            for row in body:
                out.append("<tr>")
                for j in range(len(header)):
                    cell = row[j] if j < len(row) else ""
                    out.append(f"<td>{inline(cell)}</td>")
                out.append("</tr>")
            out.append("</tbody></table></div>")
            continue

        # 引用
        if stripped.startswith(">"):
            flush_para()
            buf = []
            while i < n and lines[i].strip().startswith(">"):
                buf.append(lines[i].strip().lstrip(">").strip())
                i += 1
            out.append("<blockquote>" + inline(" ".join(buf)) + "</blockquote>")
            continue

        # 列表
        if re.match(r"^([-*]|\d+\.)\s+", stripped):
            flush_para()
            ordered = bool(re.match(r"^\d+\.", stripped))
            tag = "ol" if ordered else "ul"
            out.append(f"<{tag}>")
            while i < n:
                cur = lines[i]
                s = cur.strip()
                if not s:
                    break
                indent = len(cur) - len(cur.lstrip())
                mm = re.match(r"^([-*]|\d+\.)\s+(.*)$", s)
                if not mm:
                    break
                item = mm.group(2)
                i += 1
                # 缩进更深的下一行算子项（只有一层）
                subs = []
                while i < n:
                    nxt = lines[i]
                    if not nxt.strip():
                        break
                    if len(nxt) - len(nxt.lstrip()) > indent and re.match(
                        r"^\s*([-*]|\d+\.)\s+", nxt
                    ):
                        subs.append(re.sub(r"^\s*([-*]|\d+\.)\s+", "", nxt))
                        i += 1
                    else:
                        break
                if subs:
                    item_html = inline(item) + "<ul>" + "".join(
                        f"<li>{inline(x)}</li>" for x in subs
                    ) + "</ul>"
                else:
                    item_html = inline(item)
                out.append(f"<li>{item_html}</li>")
            out.append(f"</{tag}>")
            continue

        para.append(stripped)
        i += 1

    flush_para()
    return "\n".join(out), toc


def split_row(row: str):
    """按 | 切单元格，尊重 \\| 转义。"""
    row = row.strip()
    if row.startswith("|"):
        row = row[1:]
    if row.endswith("|") and not row.endswith("\\|"):
        row = row[:-1]
    cells, cur, k = [], "", 0
    while k < len(row):
        if row[k] == "\\" and k + 1 < len(row) and row[k + 1] == "|":
            cur += "|"
            k += 2
            continue
        if row[k] == "|":
            cells.append(cur)
            cur = ""
            k += 1
            continue
        cur += row[k]
        k += 1
    cells.append(cur)
    return cells


CSS = """
:root{
  --bg:#fbfbfc; --fg:#1c1f23; --muted:#5b6472; --line:#e3e6ea;
  --card:#ffffff; --accent:#0b6b74; --accent-soft:#e6f2f3;
  --code-bg:#f5f7f9; --code-fg:#26303b; --th:#f0f3f6;
}
@media (prefers-color-scheme: dark){
  :root{
    --bg:#15181c; --fg:#e6e9ee; --muted:#9aa4b2; --line:#2b313a;
    --card:#1b1f25; --accent:#5cc8cf; --accent-soft:#12333a;
    --code-bg:#12161a; --code-fg:#cdd6e0; --th:#20252c;
  }
}
*{box-sizing:border-box}
body{
  margin:0; background:var(--bg); color:var(--fg);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei","PingFang SC","Hiragino Sans GB",sans-serif;
  font-size:15.5px; line-height:1.78; letter-spacing:.01em;
}
.page{max-width:920px; margin:0 auto; padding:56px 28px 120px;}
h1{font-size:29px; line-height:1.35; margin:0 0 10px; letter-spacing:-.01em;}
h2{font-size:21px; margin:52px 0 14px; padding-bottom:9px; border-bottom:1px solid var(--line);}
h3{font-size:17px; margin:34px 0 10px; color:var(--accent);}
h4{font-size:15.5px; margin:24px 0 8px;}
p{margin:12px 0;}
strong{font-weight:650;}
a{color:var(--accent); text-decoration:none; border-bottom:1px solid transparent;}
a:hover{border-bottom-color:var(--accent);}
hr{border:0; border-top:1px solid var(--line); margin:40px 0;}
blockquote{
  margin:16px 0; padding:12px 16px; background:var(--accent-soft);
  border-left:3px solid var(--accent); border-radius:0 8px 8px 0; color:var(--fg);
}
ul,ol{margin:12px 0; padding-left:24px;}
li{margin:6px 0;}
li>ul{margin:4px 0;}
code{
  font-family:"JetBrains Mono","Cascadia Code",Consolas,"Courier New",monospace;
  font-size:13px; background:var(--code-bg); color:var(--code-fg);
  padding:1.5px 5px; border-radius:4px; border:1px solid var(--line);
}
.code{
  margin:18px 0; border:1px solid var(--line); border-radius:10px;
  background:var(--code-bg); overflow:hidden;
}
.code-lang{
  font-family:ui-monospace,Consolas,monospace; font-size:11px; letter-spacing:.08em;
  text-transform:uppercase; color:var(--muted); padding:7px 14px;
  border-bottom:1px solid var(--line);
}
.code pre{margin:0; padding:14px 16px; overflow-x:auto;}
.code pre code{
  background:none; border:0; padding:0; font-size:12.8px; line-height:1.66;
  color:var(--code-fg); white-space:pre;
}
.table-wrap{margin:18px 0; overflow-x:auto; border:1px solid var(--line); border-radius:10px;}
table{border-collapse:collapse; width:100%; font-size:14px;}
th,td{padding:9px 13px; text-align:left; vertical-align:top; border-bottom:1px solid var(--line);}
th{background:var(--th); font-weight:650; white-space:nowrap;}
tbody tr:last-child td{border-bottom:0;}
.toc{
  margin:32px 0 8px; padding:18px 22px; background:var(--card);
  border:1px solid var(--line); border-radius:12px;
}
.toc-title{font-size:12px; letter-spacing:.14em; text-transform:uppercase; color:var(--muted); margin-bottom:10px;}
.toc ol{margin:0; padding-left:20px;}
.toc li{margin:5px 0; font-size:14.5px;}
.toc a{border:0;}
.toc .lv1{font-weight:600;}
.meta{color:var(--muted); font-size:13.5px; margin:0 0 6px;}
@media print{
  body{background:#fff; color:#000;}
  .toc{break-after:page;}
  .code,table,blockquote{break-inside:avoid;}
}
"""


def build(md_path: str, out_path: str, title: str):
    with open(md_path, encoding="utf-8") as f:
        md = f.read()
    body, toc = render(md)

    toc_html = ""
    if toc:
        items = "".join(
            f'<li class="lv{lv}"><a href="#{sid}">{esc(text)}</a></li>'
            for lv, sid, text in toc
        )
        toc_html = (
            '<nav class="toc"><div class="toc-title">目录</div>'
            f"<ol>{items}</ol></nav>"
        )

    doc = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(title)}</title>
<style>{CSS}</style>
</head>
<body>
<main class="page">
{toc_html}
{body}
</main>
</body>
</html>
"""
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(doc)
    print(f"OK -> {out_path}  ({len(doc)} chars, {len(toc)} toc entries)")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    t = sys.argv[3] if len(sys.argv) > 3 else "文档"
    build(sys.argv[1], sys.argv[2], t)
