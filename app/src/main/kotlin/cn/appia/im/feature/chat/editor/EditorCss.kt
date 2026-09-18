package cn.appia.im.feature.chat.editor

/**
 * 编辑器样式两源：
 * - [BRIDGE_EXTEND_CSS]：10tap 各 bridge 的 extendCSS 逐字转录（external/10tap-editor/src/bridges/
 *   {image,tasklist,mention,blockquote,customEmoji,code,placeholder}.ts）——经
 *   [TenTapBridge.injectedStyleSheets] 生成 `<style data-tag="{bridgeName}">`（RN getInjectedJS 同构）。
 *   无 extendCSS 的 bridge（bold/italic/…）RN 注入空样式，此处直接不建 tag（渲染等价）。
 * - [inputBarCss]：RN ChatInputBar.tsx handleLoad 注入的自定义 CSS（:342-452），placeholder 文案
 *   参数化（i18n 注入）；滚动条隐藏/列表计数后缀/行距与 RN 冻结基线一致。
 */
object EditorCss {

    val BRIDGE_EXTEND_CSS: Map<String, String> = mapOf(
        "image" to """
            img {
              height: auto;
              max-width: 100%;
            }

            img &.ProseMirror-selectednode {
              outline: 3px solid #68cef8;
            }
        """.trimIndent(),
        "taskList" to """
            ul[data-type="taskList"] {
              list-style: none;
              padding: 0;
            }

            ul[data-type="taskList"] > li {
              display: flex;
            }

            ul[data-type="taskList"] p {
              margin: 0;
            }

            ul[data-type="taskList"] li {
              display: flex;
            }

            ul[data-type="taskList"] li > label > input {
              font-size: inherit;
              font-family: inherit;
              color: #000;
              margin: 0.1rem;
              border: 1px solid black;
              border-radius: 0.3rem;
              padding: 0.1rem 0.4rem;
              background: white;
              accent-color: black;
            }
            ul[data-type="taskList"] li > label {
              flex: 0 0 auto;
              margin-right: 0.5rem;
              user-select: none;
            }

            ul[data-type="taskList"] li > div {
              flex: 1 1 auto;
            }
        """.trimIndent(),
        "mention" to """
            .mention-node {
              background-color: rgba(59, 130, 246, 0.1);
              border-radius: 4px;
              padding: 1px 4px;
              color: #3b82f6;
              cursor: pointer;
              text-decoration: none;
            }
            .mention-node:hover {
              background-color: rgba(59, 130, 246, 0.18);
            }
        """.trimIndent(),
        "blockquote" to """
              blockquote {
                  border-left: 3px solid #0d0d0d1a;
                  padding-left: 1rem;
              }
        """.trimIndent(),
        "customEmoji" to """
              img[data-emoji="true"] {
                display: inline-block;
                vertical-align: text-bottom;
              }
              span[data-emoji="true"][data-type="emoji"] {
                font-size: inherit;
                line-height: inherit;
              }
        """.trimIndent(),
        "code" to """
              code {
                  background-color: #6161611a;
                  border-radius: 0.25em;
                  box-decoration-break: clone;
                  color: #616161;
                  font-size: 0.9rem;
                  padding: 0.25em;
              }
        """.trimIndent(),
        "placeholder" to """
            .is-editor-empty:first-child::before {
                color: #adb5bd;
                content: attr(data-placeholder);
                float: left;
                height: 0;
                pointer-events: none;
            }
        """.trimIndent(),
    )

    /** RN ChatInputBar handleLoad（:338-463）注入 CSS；[placeholder] 走 i18n（chatinput_placeholder）。 */
    fun inputBarCss(placeholder: String): String = """
.ProseMirror p.is-editor-empty:first-child::before {
  content: ${placeholderJson(placeholder)};
  color: #86909c;
  pointer-events: none;
}
.ProseMirror {
  padding: 8px 0;
  box-sizing: border-box;
}
.ProseMirror p {
  margin: 0;
  padding: 0;
  line-height: 1.4;
}
.ProseMirror a:empty {
  display: none !important;
}
.ProseMirror a[href] {
  text-decoration: none !important;
}
.ProseMirror ul, .ProseMirror ol {
  margin: 0;
  padding: 0 0 0 24px;
}
.ProseMirror li,
.ProseMirror li p {
  margin: 0;
  padding: 0;
  line-height: 1.4;
}

@counter-style decimal-type {
  system: extends decimal;
  suffix: ') ';
}

@counter-style lower-alpha-type {
  system: extends lower-alpha;
  suffix: ') ';
}

@counter-style upper-roman-type {
  system: extends upper-roman;
  suffix: ') ';
}

@counter-style lower-roman-type {
  system: extends lower-roman;
  suffix: ') ';
}

.ProseMirror ol {
  list-style-type: decimal-type;
  list-style-position: outside;
}

.ProseMirror ul {
  list-style-type: disc;
  list-style-position: outside;
}
.ProseMirror ul ul {
  list-style-type: circle;
  list-style-position: outside;
}

.ProseMirror ul ul ul {
  list-style-type: square;
  list-style-position: outside;
}

.ProseMirror ol ol,
.ProseMirror ul ol {
  list-style-type: lower-alpha-type;
  list-style-position: outside;
}

.ProseMirror ol ol ol,
.ProseMirror ol ul ol,
.ProseMirror ul ol ol,
.ProseMirror ul ul ol {
  list-style-type: upper-roman-type;
  list-style-position: outside;
}

.ProseMirror ol ol ol ol,
.ProseMirror ol ol ul ol,
.ProseMirror ol ul ol ol,
.ProseMirror ul ul ol ol,
.ProseMirror ol ol ul ul ol,
.ProseMirror ul ol ul ol,
.ProseMirror ul ul ol ol,
.ProseMirror ul ul ul ol {
  list-style-type: lower-roman-type;
  list-style-position: outside;
}

.ProseMirror,
.ProseMirror:focus {
  overflow: hidden;
}
.ProseMirror::-webkit-scrollbar {
  display: none;
}
.ProseMirror {
  scrollbar-width: none;
}"""

    /** placeholder 色取 RN Colors.textTertiary（#86909C）。 */
    private fun placeholderJson(placeholder: String): String = TenTapBridge.jsStringLiteral(placeholder)
}
