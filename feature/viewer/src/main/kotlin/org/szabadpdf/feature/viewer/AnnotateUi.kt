package org.szabadpdf.feature.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.szabadpdf.core.pdf.AnnotationKind
import org.szabadpdf.core.pdf.InkStyle

private val PenColors = listOf(0x1A1A1A, 0x1E5BD8, 0xD32F2F, 0x2E7D32, 0x7B1FA2, 0xEF6C00)
private val PenWidths = listOf(0.75f, 1.5f, 3f, 6f)
private val HighlighterColors = listOf(0xFFEB3B, 0x76FF03, 0x18FFFF, 0xFF80AB, 0xFFAB40)
private val HighlighterWidths = listOf(8f, 12f, 18f)

/** The tool strip shown along the bottom while annotating. */
@Composable
internal fun AnnotateToolbar(
    state: AnnotateState,
    onTool: (Tool) -> Unit,
    onStyle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Tool.SELECT, Icons.Filled.TouchApp, "Select", state.tool, onTool)
            ToolButton(Tool.PEN, Icons.Filled.Draw, "Pen", state.tool, onTool, accent = state.pen.color)
            ToolButton(Tool.HIGHLIGHTER, Icons.Filled.Brush, "Highlighter pen", state.tool, onTool, accent = state.highlighter.color)
            ToolButton(Tool.TEXT_HIGHLIGHT, Icons.Filled.BorderColor, "Highlight text", state.tool, onTool, accent = state.markupColor)
            ToolButton(Tool.NOTE, Icons.AutoMirrored.Filled.StickyNote2, "Note", state.tool, onTool)
            ToolButton(Tool.SIGNATURE, Icons.Filled.Gesture, "Signature", state.tool, onTool)
            ToolButton(Tool.ERASER, EraserIcon, "Eraser", state.tool, onTool)
            IconButton(onClick = onStyle) { Icon(Icons.Filled.Palette, "Pen settings") }
        }
    }
}

@Composable
private fun ToolButton(
    tool: Tool,
    icon: ImageVector,
    label: String,
    selected: Tool,
    onTool: (Tool) -> Unit,
    accent: Int? = null,
) {
    val active = tool == selected
    Box(
        Modifier
            .size(48.dp)
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .clickable { onTool(tool) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accent != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(width = 18.dp, height = 3.dp)
                    .background(rgbColor(accent), CircleShape),
            )
        }
    }
}

/** Colours and widths for the pens and highlighting, and whether fingers draw. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StyleSheet(
    state: AnnotateState,
    onPen: (InkStyle) -> Unit,
    onHighlighter: (InkStyle) -> Unit,
    onMarkupColor: (Int) -> Unit,
    onFingerDraws: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Pen")
            ColorRow(PenColors, state.pen.color) { onPen(state.pen.copy(color = it)) }
            WidthRow(PenWidths, state.pen) { onPen(state.pen.copy(width = it)) }

            SectionTitle("Highlighter pen")
            ColorRow(HighlighterColors, state.highlighter.color) { onHighlighter(state.highlighter.copy(color = it)) }
            WidthRow(HighlighterWidths, state.highlighter) { onHighlighter(state.highlighter.copy(width = it)) }

            SectionTitle("Text highlight")
            ColorRow(HighlighterColors, state.markupColor, onMarkupColor)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Draw with finger", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.fingerDraws) "Fingers draw; use two fingers to scroll and zoom"
                        else "Only a stylus draws; fingers scroll and zoom",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.fingerDraws, onCheckedChange = onFingerDraws)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ColorRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (color in colors) {
            val isSelected = color == selected
            Box(
                Modifier
                    .size(36.dp)
                    .border(
                        if (isSelected) 3.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .padding(4.dp)
                    .background(rgbColor(color), CircleShape)
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun WidthRow(widths: List<Float>, style: InkStyle, onSelect: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (width in widths) {
            val isSelected = width == style.width
            Box(
                Modifier
                    .size(width = 56.dp, height = 36.dp)
                    .border(
                        if (isSelected) 3.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(width) },
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val y = size.height / 2
                    drawLine(
                        rgbColor(style.color).copy(alpha = style.opacity),
                        Offset(12.dp.toPx(), y),
                        Offset(size.width - 12.dp.toPx(), y),
                        strokeWidth = (width * 1.5f).dp.toPx().coerceAtMost(size.height * 0.8f),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** The bar shown instead of the title bar while text is selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    canMark: Boolean,
    onCopy: () -> Unit,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onStrikeout: () -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        title = { Text("Text") },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
            if (canMark) {
                IconButton(onClick = onHighlight) { Icon(Icons.Filled.BorderColor, "Highlight") }
                IconButton(onClick = onUnderline) { Icon(Icons.Filled.FormatUnderlined, "Underline") }
                IconButton(onClick = onStrikeout) { Icon(Icons.Filled.FormatStrikethrough, "Strike out") }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** Writing a new note, or reading/editing/deleting an existing one. */
@Composable
internal fun NoteDialog(
    initialText: String,
    isNew: Boolean,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew) focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New note" else "Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text != initialText) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text(if (isNew) "Cancel" else "Close") }
            }
        },
    )
}

@Composable
internal fun DeleteAnnotationDialog(kind: AnnotationKind, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val name = when (kind) {
        AnnotationKind.HIGHLIGHT -> "highlight"
        AnnotationKind.UNDERLINE -> "underline"
        AnnotationKind.STRIKEOUT -> "strikeout"
        AnnotationKind.INK -> "drawing"
        AnnotationKind.NOTE -> "note"
        AnnotationKind.OTHER -> "annotation"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this $name?") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asked before leaving (or reorganizing) with unsaved annotations. */
@Composable
internal fun UnsavedChangesDialog(onSave: () -> Unit, onDiscard: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save changes?") },
        text = { Text("Your annotations haven't been saved yet.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDiscard != null) TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun SavingDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.size(16.dp))
                Text("Saving…")
            }
        }
    }
}

internal fun rgbColor(color: Int) = Color(0xFF000000.toInt() or color)

/** Material Design Icons "eraser" (Apache-2.0); the Material set has none. */
internal val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M16.24,3.56L21.19,8.5C21.97,9.29 21.97,10.55 21.19,11.34L12,20.53C10.44,22.09 7.91,22.09 6.34,20.53" +
                "L2.81,17C2.03,16.21 2.03,14.95 2.81,14.16L13.41,3.56C14.2,2.78 15.46,2.78 16.24,3.56M4.22,15.58" +
                "L7.76,19.11C8.54,19.9 9.8,19.9 10.59,19.11L14.12,15.58L9.17,10.63L4.22,15.58Z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Set, change or remove the password needed to open the PDF. */
@Composable
internal fun ProtectDialog(
    encrypted: Boolean,
    onSet: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val problem = when {
        password.isEmpty() -> null
        ',' in password -> "Passwords can't contain commas"
        confirm.isNotEmpty() && confirm != password -> "The passwords don't match"
        else -> null
    }
    val ready = password.isNotEmpty() && confirm == password && problem == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (encrypted) "Password" else "Protect with a password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (encrypted) "This PDF needs a password to open. Set a new one, or remove it."
                    else "Anyone opening this PDF will need the password. A forgotten password can't be " +
                        "recovered, so keep it somewhere safe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (encrypted) "New password" else "Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { problem?.let { Text(it) } },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(password) }, enabled = ready) { Text(if (encrypted) "Change" else "Protect") }
        },
        dismissButton = {
            Row {
                if (encrypted) TextButton(onClick = onRemove) { Text("Remove password") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
