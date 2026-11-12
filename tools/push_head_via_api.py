#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把本地 HEAD 原样推到 GitHub（github.com:443 不通时用 REST API）。

## 和 push_via_api.py 的区别（别混用）

`push_via_api.py` 读的是**工作区字节**：文件在磁盘上是 CRLF、而 git 库里存的是 LF，
推上去的 blob 就和本地 commit 不一致 —— 下次 `git diff` 整个文件变化，一片红。

这个脚本反过来：内容一律从 **git 对象库**取（`git cat-file blob HEAD:<path>`），
推上去的 tree 与本地 HEAD 的 tree **逐字节相同**。所以：

  - 推完远程 commit 与本地 commit 内容一致，只是 sha 不同（时区/作者信息不同）；
  - 网络恢复后 `git fetch && git rebase` 会把本地收敛到远程（树相同，零冲突）。

## 用法

    python tools/push_head_via_api.py "commit message"
    python tools/push_head_via_api.py "commit message" --tag v0.9.17

只推 `远程 head → 本地 HEAD` 之间的差异文件，不碰别的路径。
不加 `--tag` 就不打 tag —— 打 tag 意味着「可以更新了」，必须等 APK 传上去再打。

## 会做的事

1. 取远程 master 最新 commit / tree；
2. 确认它是本地 HEAD 的祖先（不是就退出，免得覆盖别人）；
3. 逐文件建 blob（内容来自 `git cat-file`）→ 建 tree（base_tree）→ 建 commit → 更新 ref。
"""
import base64
import json
import subprocess
import sys
import urllib.request

REPO = "2581414411-blip/LunaMacro"
API = "https://api.github.com"


def run(*args):
    return subprocess.run(args, capture_output=True, check=True).stdout


def get_token():
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("拿不到 GitHub token（git credential fill 没返回 password）")


TOKEN = get_token()


def call(method, path, payload=None):
    """强制直连：环境里的代理（127.0.0.1:7993）经常是关的，走它只会 timeout。"""
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "token " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=120) as r:
        body = r.read()
        return json.loads(body) if body else {}


def is_ancestor(sha):
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, "HEAD"]
    ).returncode == 0


def main():
    if len(sys.argv) < 2:
        raise SystemExit("用法: push_head_via_api.py <commit_message> [--tag vX.Y.Z]")
    message = sys.argv[1]
    tag = None
    if "--tag" in sys.argv:
        tag = sys.argv[sys.argv.index("--tag") + 1]
        if not tag.startswith("v"):
            tag = "v" + tag

    local_head = run("git", "rev-parse", "HEAD").decode().strip()

    ref = call("GET", "/repos/%s/git/ref/heads/master" % REPO)
    parent_sha = ref["object"]["sha"]
    if parent_sha == local_head:
        print("远程已经是本地 HEAD，无需推送")
        return
    if not is_ancestor(parent_sha):
        raise SystemExit(
            "远程 %s 不是本地 HEAD 的祖先 —— 先 fetch/rebase，别硬推覆盖别人的提交"
            % parent_sha[:7]
        )

    parent = call("GET", "/repos/%s/git/commits/%s" % (REPO, parent_sha))
    base_tree = parent["tree"]["sha"]
    print("远程 head: %s  本地 head: %s" % (parent_sha[:7], local_head[:7]))

    # 逐文件取差异。用 git 自己的 name-status，重命名/删除都能覆盖到。
    raw = run("git", "diff", "--name-status", parent_sha, local_head).decode()
    entries = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        status, path = parts[0], parts[-1]
        if status.startswith("D"):
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            print("  delete %s" % path)
            continue
        blob_bytes = run("git", "cat-file", "blob", "HEAD:" + path)
        blob = call("POST", "/repos/%s/git/blobs" % REPO, {
            "content": base64.b64encode(blob_bytes).decode("ascii"),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %-70s %7d B" % (path, len(blob_bytes)))

    if not entries:
        raise SystemExit("没有差异文件，中止（避免建空 commit）")

    tree = call("POST", "/repos/%s/git/trees" % REPO, {"base_tree": base_tree, "tree": entries})
    commit = call("POST", "/repos/%s/git/commits" % REPO, {
        "message": message, "tree": tree["sha"], "parents": [parent_sha],
    })
    new_sha = commit["sha"]
    call("PATCH", "/repos/%s/git/refs/heads/master" % REPO, {"sha": new_sha, "force": False})
    print("master -> %s" % new_sha[:7])

    if tag:
        try:
            call("POST", "/repos/%s/git/refs" % REPO,
                 {"ref": "refs/tags/" + tag, "sha": new_sha})
            print("tag created: %s" % tag)
        except Exception as e:
            print("tag 已存在，没动它（%s）" % str(e)[:60])

    print("远程 commit: %s" % new_sha)
    print("提示：本地 %s 与远程 %s 内容相同但 sha 不同（提交时区），网络恢复后"
          % (local_head[:7], new_sha[:7]))
    print("      git fetch && git reset --hard origin/master  # 丢掉本地这版 sha，树一致（确认没有更新的本地提交再用）")


if __name__ == "__main__":
    main()
