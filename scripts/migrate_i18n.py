#!/usr/bin/env python3
"""搬迁 RN i18n 文案（en.json / zh.json）为 Android strings.xml，并生成 I18nKeys.kt。

用法（仓库根目录执行）:
  python3 scripts/migrate_i18n.py \
    --source /Users/bitmain/Projects/rebuild-mobile/appiaMobile/src/i18n \
    --out app/src/main/res

规则（Task 3 简报）:
  - en 为准：en.json 里的 key 全部输出；zh 缺失的 key 回退用 en 值并告警
  - 嵌套 JSON 用 `_` 连接展平（当前源已是扁平结构）
  - key 统一小写作为资源名（Android 资源名强制小写），运行期 t() 做 lowercase 查找
  - XML 转义：& < > 转实体；' -> \' ；" -> \" ；换行 -> \n；行首 @/? 转义
  - 含 % 的串加 formatted="false"（RN 占位符是 {{x}} 风格，无 Android 格式化参数）
  - RN 的 {{x}} 插值占位符原样保留（t() 消费方按 RN 语义自行替换）
"""

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
KEYS_OUT_DEFAULT = REPO_ROOT / "app/src/main/kotlin/cn/appia/im/core/i18n/I18nKeys.kt"

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/migrate_i18n.py 从 appiaMobile/src/i18n/{src} 生成，勿手改；{n} 条 -->
<resources>
"""


def flatten(d, prefix=""):
    """递归展平嵌套 dict，嵌套层级用 `_` 连接。"""
    out = {}
    for k, v in d.items():
        kk = f"{prefix}_{k}" if prefix else k
        if isinstance(v, dict):
            out.update(flatten(v, kk))
        else:
            out[kk] = str(v)
    return out


def xml_escape(value):
    s = value
    s = s.replace("\\", "\\\\")  # Android 串内反斜杠需先转义
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    s = s.replace("\n", "\\n").replace("\t", "\\t")
    if s.startswith(("@", "?")):
        s = "\\" + s
    return s


def render(keys, lang_map, source_name):
    lines = [HEADER.format(src=source_name, n=len(keys))]
    for key in keys:
        esc = xml_escape(lang_map[key])
        extra = ' formatted="false"' if "%" in lang_map[key] else ""
        lines.append(f'    <string name="{key.lower()}"{extra}>{esc}</string>')
    lines.append("</resources>\n")
    return "\n".join(lines)


def render_keys_kt(keys):
    body = "\n".join(f'    "{k.lower()}",' for k in keys)
    return f"""// 由 scripts/migrate_i18n.py 生成，勿手改；与 res/values*/strings.xml 保持同源。
package cn.appia.im.core.i18n

object I18nKeys {{
    val ALL: List<String> = listOf(
{body}
    )
}}
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--source", required=True, help="zh.json / en.json 所在目录")
    ap.add_argument("--out", required=True, help="res 输出目录（生成 values/ 与 values-zh/）")
    ap.add_argument("--keys-out", default=str(KEYS_OUT_DEFAULT), help="I18nKeys.kt 输出路径")
    args = ap.parse_args()

    source = Path(args.source)
    en = flatten(json.loads((source / "en.json").read_text(encoding="utf-8")))
    zh = flatten(json.loads((source / "zh.json").read_text(encoding="utf-8")))

    only_en = sorted(set(en) - set(zh))
    only_zh = sorted(set(zh) - set(en))
    if only_zh:
        print(f"警告: zh 有而 en 无（以 en 为准，已丢弃 {len(only_zh)} 个）: {only_zh[:10]}")
    if only_en:
        print(f"警告: en 有而 zh 无（zh 侧漏翻，回退用 en 值 {len(only_en)} 个）: {only_en[:10]}")

    # en 为准；资源名小写并校验重名（不同 key 仅大小写差异会导致覆盖）
    keys = sorted(en.keys())
    lowered = [k.lower() for k in keys]
    dupes = {k for k in lowered if lowered.count(k) > 1}
    if dupes:
        sys.exit(f"错误: 小写化后资源名冲突: {sorted(dupes)}")

    zh_map = {k: zh.get(k, en[k]) for k in keys}

    out = Path(args.out)
    (out / "values").mkdir(parents=True, exist_ok=True)
    (out / "values-zh").mkdir(parents=True, exist_ok=True)
    (out / "values/strings.xml").write_text(render(keys, en, "en.json"), encoding="utf-8")
    (out / "values-zh/strings.xml").write_text(render(keys, zh_map, "zh.json"), encoding="utf-8")

    keys_out = Path(args.keys_out)
    keys_out.parent.mkdir(parents=True, exist_ok=True)
    keys_out.write_text(render_keys_kt(keys), encoding="utf-8")

    print(f"完成: en={len(keys)} 条 -> {out/'values/strings.xml'}")
    print(f"      zh={len(zh_map)} 条 -> {out/'values-zh/strings.xml'}")
    print(f"      I18nKeys.ALL={len(keys)} -> {keys_out}")
    print(f"差异: 仅在 en={len(only_en)}, 仅在 zh={len(only_zh)}")


if __name__ == "__main__":
    main()
