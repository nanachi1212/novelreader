package app.novelreader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.novelreader.core.model.AppTheme
import app.novelreader.ui.AppState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(state: AppState, onDismiss: () -> Unit) {
    val settings = state.settings

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {

            // 字體
            SettingLabel("字體")
            var fontMenuOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { fontMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val current = state.fonts.find { it.id == settings.fontId }
                    Text(current?.label ?: "系統預設")
                }
                DropdownMenu(
                    expanded = fontMenuOpen,
                    onDismissRequest = { fontMenuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("系統預設") },
                        onClick = {
                            state.updateSettings { it.copy(fontId = null) }
                            fontMenuOpen = false
                        },
                    )
                    for (font in state.fonts) {
                        DropdownMenuItem(
                            text = { Text(font.label) },
                            onClick = {
                                state.updateSettings { it.copy(fontId = font.id) }
                                fontMenuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 字級
            SliderRow(
                label = "字級",
                valueText = "${settings.fontSizeSp.roundToInt()}",
                value = settings.fontSizeSp,
                range = 12f..34f,
                onChange = { v -> state.updateSettings { it.copy(fontSizeSp = v.roundToInt().toFloat()) } },
            )

            // 行距
            SliderRow(
                label = "行距",
                valueText = "%.1f".format(settings.lineHeightMult),
                value = settings.lineHeightMult,
                range = 1.2f..2.6f,
                onChange = { v ->
                    state.updateSettings { it.copy(lineHeightMult = (v * 10).roundToInt() / 10f) }
                },
            )

            // 邊距
            SliderRow(
                label = "左右",
                valueText = "${settings.marginHorizontalDp}",
                value = settings.marginHorizontalDp.toFloat(),
                range = 8f..96f,
                onChange = { v -> state.updateSettings { it.copy(marginHorizontalDp = v.roundToInt()) } },
            )
            SliderRow(
                label = "上下",
                valueText = "${settings.marginVerticalDp}",
                value = settings.marginVerticalDp.toFloat(),
                range = 8f..120f,
                onChange = { v -> state.updateSettings { it.copy(marginVerticalDp = v.roundToInt()) } },
            )

            SettingLabel("觸控翻頁（九宮格）")
            Text(
                "點選格子循環：關閉 → 前頁 → 後頁",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                repeat(3) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            val action = settings.touchPageZones.getOrElse(index) { 0 }
                            OutlinedButton(
                                onClick = {
                                    state.updateSettings {
                                        val zones = it.touchPageZones.toMutableList().apply {
                                            while (size < 9) add(0)
                                            this[index] = (action + 1) % 3
                                        }
                                        it.copy(touchPageZones = zones)
                                    }
                                },
                                modifier = Modifier.weight(1f).padding(2.dp),
                            ) {
                                Text(listOf("", "前頁", "後頁")[action])
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 主題
            SettingLabel("主題")
            val themes = listOf(
                AppTheme.SYSTEM to "系統",
                AppTheme.LIGHT to "白晝",
                AppTheme.DARK to "夜間",
                AppTheme.SEPIA to "護眼",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, (theme, label) ->
                    SegmentedButton(
                        selected = settings.theme == theme,
                        onClick = { state.updateSettings { it.copy(theme = theme) } },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = themes.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(40.dp),
        )
    }
}
