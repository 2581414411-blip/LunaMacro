# -*- coding: utf-8 -*-
"""
在 github.com:443 直连被 reset、代理不可用时，用 GitHub REST API 提交本地改动。

用法：
    python tools/push_via_api.py <commit_message> <file1> [file2 ...]

做的事：
    1) 取 master 最新 commit / tree
    2) 逐文件建 blob（base64，保留原始字节，不碰换行）
    3) 建 tree（base_tree）→ commit（parent=master）→ 更新 refs/heads/master
    4) 打 tag（refs/tags/vX.Y.Z，取 versionName）
"""
import base64
import io
import json
import os
import subprocess
import sys
import urllib.request

REPO = "2581414411-blip/LunaMacro"
API = "https://api.github.com"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def get_token():
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("拿不到 GitHub token")


TOKEN = get_token()


def call(method, path, payload=None, base=API):
    url = path if path.startswith("http") else base + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "token " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    # 强制直连，不读环境代理
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=120) as r:
        body = r.read()
        return json.loads(body) if body else {}


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: push_via_api.py <commit_message> <file...>")
    message = sys.argv[1]
    files = sys.argv[2:]

    ref = call("GET", "/repos/%s/git/ref/heads/master" % REPO)
    parent_sha = ref["object"]["sha"]
    parent = call("GET", "/repos/%s/git/commits/%s" % (REPO, parent_sha))
    base_tree = parent["tree"]["sha"]
    print("parent commit:", parent_sha[:7])

    entries = []
    for rel in files:
        full = os.path.join(ROOT, rel.replace("/", os.sep))
        with open(full, "rb") as f:
            raw = f.read()
        blob = call("POST", "/repos/%s/git/blobs" % REPO, {
            "content": base64.b64encode(raw).decode("ascii"),
            "encoding": "base64",
        })
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %-70s %8d B" % (rel, len(raw)))

    tree = call("POST", "/repos/%s/git/trees" % REPO, {"base_tree": base_tree, "tree": entries})
    commit = call("POST", "/repos/%s/git/commits" % REPO, {
        "message": message, "tree": tree["sha"], "parents": [parent_sha],
    })
    new_sha = commit["sha"]
    call("PATCH", "/repos/%s/git/refs/heads/master" % REPO, {"sha": new_sha, "force": False})
    print("master ->", new_sha[:7])

    # 版本号 → tag
    import re
    gradle = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not m:
        raise SystemExit("读不到 versionName")
    tag = "v" + m.group(1)
    tag_ref = "refs/tags/" + tag
    try:
        call("POST", "/repos/%s/git/refs" % REPO, {"ref": tag_ref, "sha": new_sha})
        print("tag created:", tag)
    except Exception as e:
        print("tag create failed (%s), force update..." % e)
        call("PATCH", "/repos/%s/git/refs/tags/%s" % (REPO, tag), {"sha": new_sha, "force": True})
        print("tag moved:", tag)
    print("SHA=" + new_sha)


if __name__ == "__main__":
    main()
