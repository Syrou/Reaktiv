import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

data class MarkdownElement(
    val type: ElementType,
    val content: String
)

enum class ElementType {
    HEADER1, HEADER2, HEADER3, TEXT, UNORDERED_LIST, TABLE
}

@Composable
fun MarkdownParser(content: String) {
    val elements = parseMarkdown(content)

    Column(modifier = Modifier.padding(16.dp)) {
        elements.forEach { element ->
            when (element.type) {
                ElementType.HEADER1 -> Header1(element.content)
                ElementType.HEADER2 -> Header2(element.content)
                ElementType.HEADER3 -> Header3(element.content)
                ElementType.TEXT -> BodyText(element.content)
                ElementType.UNORDERED_LIST -> UnorderedListItem(element.content)
                ElementType.TABLE -> MarkdownTable(element.content)
            }
        }
    }
}

@Composable
fun Header1(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun Header2(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
fun Header3(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun BodyText(text: String) {
    StyledText(text)
}

@Composable
fun UnorderedListItem(text: String) {
    Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        StyledText(text)
    }
}

@Composable
fun MarkdownTable(tableContent: String) {
    val rows = tableContent.trim().split("\n")
    val headers = rows[0].split("|").filter { it.isNotBlank() }.map { it.trim() }
    val dataRows = rows.subList(2, rows.size)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            headers.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(4.dp)
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        dataRows.forEach { row ->
            val cells = row.split("|").filter { it.isNotBlank() }.map { it.trim() }
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEach { cell ->
                    StyledText(
                        text = cell,
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        }
    }
}

@Composable
fun StyledText(text: String, modifier: Modifier = Modifier) {
    val annotatedString = buildAnnotatedString {
        val parts = text.split(Regex("(</?[bi]>)"))
        var isBold = false
        var isItalic = false

        for (part in parts) {
            when (part) {
                "<b>" -> isBold = true
                "</b>" -> isBold = false
                "<i>" -> isItalic = true
                "</i>" -> isItalic = false
                else -> {
                    withStyle(
                        style = SpanStyle(
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal
                        )
                    ) {
                        append(part)
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

fun parseMarkdown(content: String): List<MarkdownElement> {
    val elements = mutableListOf<MarkdownElement>()
    val lines = content.split("\n")
    var currentText = StringBuilder()
    var inTable = false
    var tableContent = StringBuilder()

    for (line in lines) {
        when {
            line.trim().startsWith("# ") -> {
                flushCurrentText(elements, currentText)
                elements.add(MarkdownElement(ElementType.HEADER1, line.trim().removePrefix("# ")))
            }
            line.trim().startsWith("## ") -> {
                flushCurrentText(elements, currentText)
                elements.add(MarkdownElement(ElementType.HEADER2, line.trim().removePrefix("## ")))
            }
            line.trim().startsWith("### ") -> {
                flushCurrentText(elements, currentText)
                elements.add(MarkdownElement(ElementType.HEADER3, line.trim().removePrefix("### ")))
            }
            line.trim().startsWith("- ") -> {
                flushCurrentText(elements, currentText)
                elements.add(MarkdownElement(ElementType.UNORDERED_LIST, parseLine(line.trim().removePrefix("- "))))
            }
            line.trim().startsWith("|") && line.trim().endsWith("|") -> {
                if (!inTable) {
                    flushCurrentText(elements, currentText)
                    inTable = true
                }
                tableContent.append(line).append("\n")
            }
            else -> {
                if (inTable) {
                    elements.add(MarkdownElement(ElementType.TABLE, tableContent.toString().trim()))
                    tableContent.clear()
                    inTable = false
                }
                currentText.append(parseLine(line)).append("\n")
            }
        }
    }

    if (inTable) {
        elements.add(MarkdownElement(ElementType.TABLE, tableContent.toString().trim()))
    } else {
        flushCurrentText(elements, currentText)
    }

    return elements
}

fun flushCurrentText(elements: MutableList<MarkdownElement>, currentText: StringBuilder) {
    if (currentText.isNotBlank()) {
        elements.add(MarkdownElement(ElementType.TEXT, currentText.toString().trim()))
        currentText.clear()
    }
}

fun parseLine(line: String): String {
    val result = StringBuilder()
    var i = 0
    var inBold = false
    var inItalic = false

    while (i < line.length) {
        when {
            line.startsWith("**", i) -> {
                result.append(if (inBold) "</b>" else "<b>")
                inBold = !inBold
                i += 2
            }
            line.startsWith("*", i) -> {
                result.append(if (inItalic) "</i>" else "<i>")
                inItalic = !inItalic
                i++
            }
            else -> {
                result.append(line[i])
                i++
            }
        }
    }

    return result.toString()
}