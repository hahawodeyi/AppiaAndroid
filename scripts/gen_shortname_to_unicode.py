#!/usr/bin/env python3
"""从 appiaMobile 的 shortnameToUnicode 表生成 Kotlin 映射（M3-T5）。

用法（仓库根目录执行）:
  python3 scripts/gen_shortname_to_unicode.py \
    ../appiaMobile/src/lib/shortnameToUnicode/emojis.ts \
    app/src/main/kotlin/cn/appia/im/core/messaging/ShortnameToUnicode.kt

源表为 RN 侧 `src/lib/shortnameToUnicode/emojis.ts`（':name:': 'emoji' 字面量表，
4626 项）。仅提取该映射；RN index.ts 的 ascii 表与 HTML 反转义只服务于全文本扫描，
渲染链（Emoji/BigEmoji）只用单短名查询，不搬运。
输出按 key 排序保证可复现；重新生成后整文件替换，勿手改。
"""

import re
import sys
from pathlib import Path

# 仓库 pre-commit 中文检查用 perl \p{Han}，它把 〰〽㊗㊙🉐🉑（CJK 兼容/ enclosed 意象符号）
# 也判为汉字。生成文件里把这些码点写成 \uXXXX 转义（纯 ASCII），既过钩子又保持其余字面量可读。
CJK_RANGES = ((0x3000, 0x33FF), (0x1F200, 0x1F2FF))


def escape_han(text: str) -> str:
    out = []
    for ch in text:
        cp = ord(ch)
        if any(lo <= cp <= hi for lo, hi in CJK_RANGES):
            if cp <= 0xFFFF:
                out.append(f"\\u{cp:04X}")
            else:
                cp -= 0x10000
                out.append(
                    f"\\u{0xD800 + (cp >> 10):04X}\\u{0xDC00 + (cp & 0x3FF):04X}"
                )
        else:
            out.append(ch)
    return "".join(out)

TEMPLATE = '''package cn.appia.im.core.messaging

/**
 * 标准 shortname → unicode 映射（EMOJI 节点 shortCode 形态渲染用）。
 * 由 scripts/gen_shortname_to_unicode.py 从 appiaMobile
 * `src/lib/shortnameToUnicode/emojis.ts` 生成（{count} 项，按 key 排序），勿手改。
 *
 * 查询走 [shortnameToUnicode]：miss 原样返回输入（RN index.ts
 * replaceShortNameWithUnicode 同款，即渲染回退 ':code:'）。
 */
internal object ShortnameToUnicode {{

    /** key 含首尾冒号（':thumbsup:'）。 */
    val map: Map<String, String> = mapOf(
{entries}
    )
}}

/** RN shortnameToUnicode(':code:') 单短名查询语义：miss 原样返回输入。 */
internal fun shortnameToUnicode(emojiToken: String): String =
    ShortnameToUnicode.map[emojiToken] ?: emojiToken
'''

ENTRY = '        "{key}" to "{value}",'


def main() -> None:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    if src is None or dst is None:
        sys.exit(__doc__)
    text = src.read_text(encoding="utf-8")
    # 尾逗号可选：RN 表最后一项 ':zap:': '⚡' 无尾逗号（曾丢项，勿回退）
    pairs = re.findall(r"'(:[^']+:)': '(.+?)',?", text)
    if not pairs:
        sys.exit(f"no pairs extracted from {src}")
    keys = [k for k, _ in pairs]
    dupes = len(keys) - len(set(keys))
    if dupes:
        sys.exit(f"{dupes} duplicate keys in {src}")
    entries = "\n".join(
        ENTRY.format(key=k, value=escape_han(v)) for k, v in sorted(pairs)
    )
    dst.write_text(
        TEMPLATE.format(count=len(pairs), entries=entries),
        encoding="utf-8",
    )
    print(f"{len(pairs)} entries -> {dst}")


if __name__ == "__main__":
    main()
