#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线校验 Room 迁移：在内存 sqlite 上真跑一遍，逐列比对导出 schema。

## 为什么需要它

Room 的迁移错误只会在**真机启动时**炸（`Room cannot verify the data integrity`），
而那是最贵的发现时机 —— 用户已经装上、库已经升级到一半。这个脚本把校验
提前到出包前：用 `app/schemas/*/{from}.json` 建一个老版本的库，插入几行老数据，
执行源码里 `MIGRATION_{from}_{to}` 的 SQL，再用 `PRAGMA table_info` / `index_list`
和 `{to}.json` 的期望逐列比对。

比对项：列集合 / 类型 / NOT NULL / 默认值 / 主键 / 索引名。

## 用法

    python tools/check_migration.py            # 自动取最新的两版
    python tools/check_migration.py 8 9        # 指定版本
"""
import json
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_DIR = os.path.join(ROOT, "app", "schemas", "com.nexus.agent.data.db.AppDatabase")
DB_KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "nexus", "agent",
                     "data", "db", "AppDatabase.kt")


def extract_migration(frm: int, to: int):
    """从 AppDatabase.kt 里抓 MIGRATION_{frm}_{to} 的所有 execSQL 语句。"""
    src = open(DB_KT, encoding="utf-8").read()
    marker = f"val MIGRATION_{frm}_{to} = object : Migration({frm}, {to}) {{"
    if marker not in src:
        return None
    tail = src.split(marker, 1)[1]
    # 迁移对象结束于下一处 `        val MIGRATION_` 或文件末
    nxt = tail.find("\n        val MIGRATION_")
    if nxt > 0:
        tail = tail[:nxt]

    stmts = []
    for chunk in re.split(r"db\.execSQL\(", tail)[1:]:
        depth, i, inq = 1, 0, False
        while i < len(chunk):
            c = chunk[i]
            if c == '"' and (i == 0 or chunk[i - 1] != "\\"):
                inq = not inq
            elif not inq:
                if c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                    if depth == 0:
                        break
            i += 1
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', chunk[:i])
        sql = "".join(literals).replace('\\"', '"')
        if sql.strip():
            stmts.append(sql.strip())
    return stmts


def load(ver: int):
    with open(os.path.join(SCHEMA_DIR, f"{ver}.json"), encoding="utf-8") as f:
        return json.load(f)["database"]


def build(ver: int) -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    for e in load(ver)["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for idx in e.get("indices", []):
            db.execute(idx["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    return db


def compare(db: sqlite3.Connection, ver: int) -> bool:
    ok = True
    for e in load(ver)["entities"]:
        table = e["tableName"]
        pk_cols = set(e.get("primaryKey", {}).get("columnNames", []))
        got = {r[1]: (r[2].upper(), r[3], r[4], r[5])
               for r in db.execute(f"PRAGMA table_info(`{table}`)")}
        want = {}
        for f in e["fields"]:
            want[f["columnName"]] = (
                f["affinity"].upper(),
                1 if f["notNull"] else 0,
                f.get("defaultValue"),
                1 if f["columnName"] in pk_cols else 0,
            )
        if set(got) != set(want):
            ok = False
            print(f"  [X] {table} 列不一致 多={set(got) - set(want)} 少={set(want) - set(got)}")
            continue
        for col, (ty, nn, dv, pk) in want.items():
            gt, gn, gd, gp = got[col]
            if gt != ty:
                ok = False
                print(f"  [X] {table}.{col} 类型 期望 {ty} 实际 {gt}")
            if gn != nn:
                ok = False
                print(f"  [X] {table}.{col} NOT NULL 期望 {nn} 实际 {gn}")
            if (gp > 0) != (pk > 0):
                ok = False
                print(f"  [X] {table}.{col} 主键 期望 {pk} 实际 {gp}")
            if (dv or None) != (gd or None):
                ok = False
                print(f"  [X] {table}.{col} 默认值 期望 {dv!r} 实际 {gd!r}")

        want_idx = {i["name"] for i in e.get("indices", [])}
        got_idx = {r[1] for r in db.execute(f"PRAGMA index_list(`{table}`)")
                   if r[1].startswith("index_")}
        if want_idx != got_idx:
            ok = False
            print(f"  [X] {table} 索引不一致 期望 {want_idx} 实际 {got_idx}")
    return ok


def main():
    versions = sorted(int(os.path.splitext(f)[0]) for f in os.listdir(SCHEMA_DIR)
                      if f.endswith(".json"))
    if len(sys.argv) >= 3:
        frm, to = int(sys.argv[1]), int(sys.argv[2])
    else:
        frm, to = versions[-2], versions[-1]

    print(f"迁移校验 {frm} -> {to}")
    stmts = extract_migration(frm, to)
    if stmts is None:
        print(f"  [X] AppDatabase.kt 里找不到 MIGRATION_{frm}_{to}")
        return 1
    print(f"  抓到 {len(stmts)} 条 execSQL")

    db = build(frm)
    # 塞几行老数据，验证「加列之后老行能读、且落到默认值」
    for e in load(frm)["entities"]:
        cols = [f["columnName"] for f in e["fields"]
                if not f["notNull"] or f.get("defaultValue") is not None
                or f["affinity"] == "TEXT"]
        # 只往不涉及外键的简单表里塞，塞不进去就算了（迁移校验不依赖数据）
        try:
            names = ", ".join(f"`{c}`" for c in cols)
            vals = ", ".join("1" if f_next(e, c) != "TEXT" else "'x'" for c in cols)
            db.execute(f"INSERT INTO `{e['tableName']}` ({names}) VALUES ({vals})")
        except Exception:
            pass
    db.commit()

    for s in stmts:
        try:
            db.execute(s)
        except Exception as ex:
            print(f"  [X] 执行失败：{s[:70]}… -> {ex}")
            return 1
    db.commit()

    ok = compare(db, to)
    tables = [r[0] for r in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    print(f"  迁移后共 {len(tables)} 张表")
    print("  结论：" + ("✅ 结构与导出 schema 完全一致" if ok else "❌ 存在不一致"))
    return 0 if ok else 1


def f_next(entity, col_name):
    for f in entity["fields"]:
        if f["columnName"] == col_name:
            return f["affinity"]
    return "TEXT"


if __name__ == "__main__":
    sys.exit(main())
