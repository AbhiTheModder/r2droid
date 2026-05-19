package top.wsdx233.r2droid.feature.debug.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.wsdx233.r2droid.R
import top.wsdx233.r2droid.ui.theme.LocalAppFont

@Composable
fun MemoryPanel(
    address: Long?,
    bytes: ByteArray,
    isLoading: Boolean,
    error: String?,
    onRefreshPc: () -> Unit,
    onRefreshSp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.debug_panel_memory_title),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = LocalAppFont.current
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefreshPc) { Text("PC") }
            OutlinedButton(onClick = onRefreshSp) { Text("SP") }
        }
        Spacer(Modifier.height(8.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            address == null -> {
                Text(
                    text = stringResource(R.string.debug_panel_memory_no_address),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            bytes.isEmpty() -> {
                Text(
                    text = stringResource(R.string.debug_panel_memory_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.debug_panel_memory_base, "0x${address.toString(16).uppercase()}"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LocalAppFont.current,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(formatHexRows(address, bytes), key = { it.address }) { row ->
                        Text(
                            text = row.text,
                            fontFamily = LocalAppFont.current,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private data class MemoryRow(val address: Long, val text: String)

private fun formatHexRows(base: Long, bytes: ByteArray): List<MemoryRow> {
    return bytes.toList().chunked(16).mapIndexed { rowIndex, chunk ->
        val addr = base + rowIndex * 16L
        val hex = chunk.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xff) }.padEnd(16 * 3)
        val ascii = chunk.map { byte ->
            val value = byte.toInt() and 0xff
            if (value in 0x20..0x7e) value.toChar() else '.'
        }.joinToString("")
        MemoryRow(addr, "0x${addr.toString(16).uppercase()}  $hex  $ascii")
    }
}
