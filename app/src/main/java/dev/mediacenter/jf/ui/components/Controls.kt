package dev.mediacenter.jf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType

/**
 * The row of views across the top of a library ("title  genre  year  date added").
 * Like Media Center, moving onto a pivot switches to it; no need to press OK.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PivotBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    val selectedRequester = remember { FocusRequester() }
    Row(
        // Entering the bar from above or below lands on the selected pivot, not the nearest one.
        // Scrolls sideways when there are more pivots than fit (music has eight).
        modifier
            .horizontalScroll(rememberScrollState())
            .focusProperties { enter = { selectedRequester } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            FocusBox(
                onClick = { onSelect(i) },
                onFocus = { if (i != selected) onSelect(i) },
                fill = true,
                scale = 1f,
                modifier = if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier,
            ) { focused ->
                val color by animateColorAsState(if (focused || i == selected) Wmc.Text else Wmc.TextDim, label = "pivot")
                // The large style (details pages) shows the selected pivot as a big heading, as My Movies did.
                val text = if (i == selected && !big) "\u2039 $label \u203a" else label
                WText(
                    text,
                    when {
                        i == selected && big -> WmcType.Title
                        i == selected -> WmcType.Pivot.copy(fontSize = WmcType.Pivot.fontSize * 1.25f)
                        else -> WmcType.Pivot
                    },
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = color,
                )
            }
        }
    }
}

/**
 * A Windows 7–style on/off switch: a glass track that fills with glowing blue when on,
 * and a glossy knob that slides across. [lit] brightens the rim while its row is focused.
 */
@Composable
fun ToggleSwitch(on: Boolean, lit: Boolean, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState(
        if (on) 1f else 0f,
        dev.mediacenter.jf.ui.theme.Motion.spec(androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 700f)),
        label = "switch",
    )
    androidx.compose.foundation.Canvas(modifier.then(Modifier.size(52.dp, 26.dp))) {
        val w = size.width
        val h = size.height
        val round = androidx.compose.ui.geometry.CornerRadius(h / 2f)
        // The track: dark glass when off, filling with the focus blue as it turns on.
        drawRoundRect(Color(0x33FFFFFF), cornerRadius = round)
        drawRoundRect(
            dev.mediacenter.jf.ui.theme.Wmc.FocusFill,
            cornerRadius = round, alpha = pos,
        )
        // Aero gloss across the top half, and the rim.
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x44FFFFFF), Color(0x0AFFFFFF)), endY = h / 2f),
            size = androidx.compose.ui.geometry.Size(w, h / 2f), cornerRadius = round,
        )
        drawRoundRect(
            Color.White.copy(alpha = if (lit) 0.75f else 0.35f), cornerRadius = round,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
        )
        // The knob, with a soft shadow under it.
        val knob = h / 2f - 3.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(h / 2f + (w - h) * pos, h / 2f)
        drawCircle(Color(0x55000814), knob + 1.5.dp.toPx(), center + androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()))
        drawCircle(
            Brush.verticalGradient(listOf(Color.White, Color(0xFFCFE3F5)), startY = center.y - knob, endY = center.y + knob),
            knob, center,
        )
    }
}

/** A wide menu button used on detail pages and in settings. */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    detail: String? = null,
    height: Dp = 44.dp,
    onFocus: () -> Unit = {},
) {
    FocusBox(onClick = onClick, onFocus = onFocus, fill = true, scale = 1.03f, modifier = modifier.fillMaxWidth().height(height)) { focused ->
        Row(Modifier.padding(horizontal = 14.dp).height(height), verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) GlyphIcon(glyph, Modifier.padding(end = 12.dp), size = 20.dp, color = if (focused) Wmc.Text else Wmc.TextDim)
            WText(label, WmcType.Label, Modifier.weight(1f), color = if (focused) Wmc.Text else Wmc.TextDim)
            if (detail != null) WText(detail, WmcType.Caption, color = if (focused) Wmc.Text else Wmc.TextFaint)
        }
    }
}

/**
 * A text box that behaves on a remote: D-pad focus lands on the box without
 * popping the keyboard; OK opens the keyboard; Done closes it and moves on.
 */
@Composable
fun WmcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val fieldRequester = remember { FocusRequester() }
    val outerRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(editing) {
        if (editing) {
            fieldRequester.tryFocus()
            keyboard?.show()
        }
    }

    Column(modifier) {
        WText(label, WmcType.Caption, Modifier.padding(bottom = 6.dp, start = 2.dp))
        FocusBox(
            onClick = { editing = true },
            fill = false,
            scale = 1.02f,
            modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(outerRequester),
        ) { focused ->
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = WmcType.Label.copy(color = Color(0xFF0B1E3F)),
                cursorBrush = SolidColor(Wmc.FocusBottom),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    keyboard?.hide()
                    outerRequester.tryFocus()
                    onDone()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRequester(fieldRequester)
                    .focusProperties { canFocus = editing }
                    // Up/Down leave the box (a single-line field would otherwise swallow them).
                    .onPreviewKeyEvent { e ->
                        val dir = when (e.key) {
                            Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                            Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                            else -> null
                        }
                        if (dir == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        editing = false
                        keyboard?.hide()
                        outerRequester.tryFocus()
                        focusManager.moveFocus(dir)
                        true
                    }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false }
                    .background(Color(if (focused || editing) 0xFFF4F8FF else 0xE6DCE7F7), RoundedCornerShape(5.dp))
                    .border(if (focused || editing) 3.dp else 1.dp, if (focused || editing) Wmc.Glow else Wmc.GlassEdge, RoundedCornerShape(5.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/** A translucent panel, used behind lists and dialogs. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.aeroGlass(corner = 8.dp, strong = true)) { content() }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    WText(text, WmcType.Caption, modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wmc.Accent)

/**
 * A settings row that opens, when focused, into an Aero glass panel holding the
 * description and default. With [options], OK drops down the full list of
 * choices (current one ticked); OK picks one, Back closes the list.
 */
@Composable
fun SettingRow(
    title: String,
    value: String?,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    glyph: Glyph? = null,
    onFocus: () -> Unit = {},
    options: List<String>? = null,
    selected: Int = -1,
    onSelect: (Int) -> Unit = {},
    /** For on/off settings: shows a switch in this state; OK flips it (through [onClick]). */
    toggle: Boolean? = null,
) {
    val sounds = dev.mediacenter.jf.LocalAppState.current.sounds
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val header = remember { FocusRequester() }
    val current = remember { FocusRequester() }

    // Set while the drop-down hands focus back to its row, so the focus trap lets it go.
    val closing = remember { booleanArrayOf(false) }
    androidx.activity.compose.BackHandler(enabled = open) {
        sounds.back()
        closing[0] = true
        header.tryFocus()
        closing[0] = false
        open = false
    }
    LaunchedEffect(open) { if (open) current.focusWhenReady() }

    val expanded = focused || open
    // The whole row (header, description or drop-down) grows slightly and glows, as a single focused tile.
    val glow by animateFloatAsState(if (expanded) 1f else 0f, dev.mediacenter.jf.ui.theme.Motion.spec(androidx.compose.animation.core.tween(160)), label = "rowGlow")
    val grow by animateFloatAsState(
        if (expanded) 1.015f else 1f,
        dev.mediacenter.jf.ui.theme.Motion.spec(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f)),
        label = "rowScale",
    )
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (expanded) 2f else if (grow > 1.001f || glow > 0.01f) 1f else 0f)
            .graphicsLayer { scaleX = grow; scaleY = grow }
            .focusFrame({ glow }, fill = false, corner = 6.dp)
            .onFocusChanged { focused = it.hasFocus }
            .then(if (expanded) Modifier.aeroGlass(corner = 6.dp, tint = Color(0xFF4A9BEA)) else Modifier)
            .animateContentSize(dev.mediacenter.jf.ui.theme.Motion.finite(androidx.compose.animation.core.tween(180))),
    ) {
        FocusBox(
            onClick = { if (options != null) open = !open else onClick() },
            onFocus = onFocus,
            decorate = false,
            modifier = Modifier.fillMaxWidth().focusRequester(header),
        ) { headerFocused ->
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) GlyphIcon(glyph, Modifier.padding(end = 12.dp), size = 20.dp, color = if (expanded) Wmc.Text else Wmc.TextDim)
                WText(title, WmcType.Label, Modifier.weight(1f), color = if (expanded) Wmc.Text else Wmc.TextDim)
                if (toggle != null) {
                    WText(
                        if (toggle) "on" else "off",
                        WmcType.Label.copy(fontSize = WmcType.Label.fontSize * 0.9f),
                        Modifier.padding(end = 12.dp),
                        color = if (expanded) Wmc.Text else Wmc.TextFaint,
                    )
                    ToggleSwitch(toggle, lit = expanded)
                } else if (value != null) {
                    val arrow = if (options == null) "" else if (open) "  \u25b4" else "  \u25be"
                    WText(
                        value + arrow,
                        WmcType.Label.copy(fontSize = WmcType.Label.fontSize * 0.9f),
                        color = if (expanded) Wmc.Text else Wmc.TextFaint,
                    )
                }
            }
        }
        if (open && options != null) {
            // The drop-down: every choice, current one ticked. Up/down stay inside the list.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 8.dp)
                    // Keep the cursor inside the open list: only a choice or Back closes it.
                    // (Picking a choice hands focus back to the row itself, so let that through.)
                    .focusProperties { onExit = { if (!closing[0]) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                options.forEachIndexed { i, label ->
                    FocusBox(
                        onClick = {
                            closing[0] = true
                            header.tryFocus()
                            closing[0] = false
                            open = false
                            onSelect(i)
                        },
                        fill = true,
                        scale = 1f,
                        corner = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .then(if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(current) else Modifier),
                    ) { f ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(26.dp)) {
                                if (i == selected) GlyphIcon(Glyph.Check, size = 16.dp, color = if (f) Wmc.Text else Color(0xFFB8DEFA))
                            }
                            WText(label, WmcType.Label, color = if (f || i == selected) Wmc.Text else Wmc.TextDim)
                        }
                    }
                }
            }
        }
        if (expanded && !open && (description != null || footnote != null)) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp).background(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color(0x80E6F4FF), Color(0x80E6F4FF), Color.Transparent))
                    )
                )
                if (description != null) {
                    WText(description, WmcType.Body.copy(color = Color(0xEEF2F8FF)), Modifier.padding(top = 8.dp), maxLines = 6)
                }
                if (footnote != null) {
                    WText(footnote, WmcType.Caption.copy(color = Color(0xFFB8DEFA)), Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
