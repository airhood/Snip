package com.snip.app.ui.chat

import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

// Claude/GPT write LaTeX as \[ ... \] (block) / \( ... \) or bare $ ... $ (inline). Markwon's
// JLatexMathInlineProcessor — despite the name — only ever matches double `$$...$$`; a single
// `$...$` pair is never recognized as math at all, it's just left as literal text. So every
// style gets normalized to $$...$$ before handing off; whether it renders as block or inline
// then depends only on whether it shares a paragraph with other text.
private val blockLatexPattern = Regex("""\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)
private val parenLatexPattern = Regex("""\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val singleDollarLatexPattern = Regex("""(?<!\$)\$(?!\$)([^$\n]+?)(?<!\$)\$(?!\$)""")

private fun normalizeLatexDelimiters(text: String): String =
    text
        .replace(blockLatexPattern) { m -> "\$\$" + m.groupValues[1] + "\$\$" }
        .replace(parenLatexPattern) { m -> "\$\$" + m.groupValues[1] + "\$\$" }
        .replace(singleDollarLatexPattern) { m -> "\$\$" + m.groupValues[1] + "\$\$" }

/** AI replies are frequently Markdown (and sometimes LaTeX for math), so a plain Compose Text
 * showed raw "**bold**"/"$x^2$" syntax instead of rendering it. Markwon is TextView-based —
 * there's no first-party Compose markdown+LaTeX renderer — so this wraps one via AndroidView
 * rather than reimplementing a Markdown+LaTeX parser from scratch. */
@Composable
fun MarkdownText(markdown: String, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bodyTextSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 15.sp.toPx() }

    val markwon = remember(context, bodyTextSizePx) {
        Markwon.builder(context)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(JLatexMathPlugin.create(bodyTextSizePx) { builder ->
                builder.inlinesEnabled(true)
            })
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 15f
                setLinkTextColor(color.toArgb())
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            markwon.setMarkdown(textView, normalizeLatexDelimiters(markdown))
        },
    )
}
