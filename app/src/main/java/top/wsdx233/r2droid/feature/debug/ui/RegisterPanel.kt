package top.wsdx233.r2droid.feature.debug.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import top.wsdx233.r2droid.R
import top.wsdx233.r2droid.ui.theme.LocalAppFont
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterBottomSheet(
    registers: JSONObject,
    autoShowRegisters: Boolean,
    onAutoShowRegistersChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit
) {
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = bottomSheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.debug_panel_tab_registers),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = LocalAppFont.current
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.debug_register_auto_show),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Switch(
                        checked = autoShowRegisters,
                        onCheckedChange = onAutoShowRegistersChange
                    )
                }
            }

            RegisterGrid(
                registers = registers,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun RegisterGrid(
    registers: JSONObject,
    modifier: Modifier = Modifier
) {
    val priority = listOf("pc", "rip", "eip", "ip", "sp", "rsp", "esp", "bp", "rbp", "ebp", "flags", "eflags", "rflags")
    val keys = mutableListOf<String>()
    registers.keys().forEach { keys.add(it) }
    keys.sortWith(compareBy<String> { key ->
        val normalized = key.lowercase(Locale.ROOT)
        val exact = priority.indexOf(normalized)
        when {
            exact >= 0 -> exact
            normalized.endsWith("ip") -> 20
            normalized.endsWith("sp") -> 21
            normalized.endsWith("bp") -> 22
            else -> 100
        }
    }.thenBy { it.lowercase(Locale.ROOT) })

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(keys) { key ->
            val valueStr = when (val value = registers.opt(key)) {
                is Number -> "0x${value.toLong().toString(16).padStart(8, '0')}"
                else -> "$value"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = key.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontFamily = LocalAppFont.current,
                    modifier = Modifier.weight(0.4f),
                    maxLines = 1
                )
                Text(
                    text = valueStr,
                    fontFamily = LocalAppFont.current,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(0.6f),
                    maxLines = 1
                )
            }
        }
    }
}
