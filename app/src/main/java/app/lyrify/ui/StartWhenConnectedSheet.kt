package app.lyrify.ui

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.player.StartWhenConnected
import app.lyrify.settings.ConnectDevice

/**
 * Settings > Playback > Start when connected: the devices picked, each with Remove, and Add headphones, which opens
 * Android's own picker of Bluetooth devices. Changes go straight to the settings ([StartWhenConnected]); opening the
 * sheet drops devices Android no longer pairs with KJB.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun StartWhenConnectedSheet(devices: List<ConnectDevice>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // The picker's result carries the new pairing too: added twice is still one device.
        val info = result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        if (result.resultCode == Activity.RESULT_OK && info != null) StartWhenConnected.added(context, info)
    }
    LaunchedEffect(Unit) { StartWhenConnected.reconcile(context) }
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            LyrifyButton(
                stringResource(R.string.connect_add),
                onClick = { StartWhenConnected.add(context) { sender -> picker.launch(IntentSenderRequest.Builder(sender).build()) } },
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_add,
            )
            Text(
                stringResource(R.string.connect_add_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        },
    ) {
        Text(stringResource(R.string.set_autostart), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.connect_sheet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        devices.forEach { device -> ConnectDeviceRow(device) { StartWhenConnected.remove(context, device.associationId) } }
    }
}

/** A picked device: its name, and Remove (TalkBack: "Remove Example Buds"). */
@Composable
private fun ConnectDeviceRow(device: ConnectDevice, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val remove = stringResource(R.string.connect_remove_device, device.name)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHighest)) {
            Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LyrifyButton(
            stringResource(R.string.connect_remove),
            onClick = onRemove,
            kind = ButtonKind.SECONDARY,
            compact = true,
            modifier = Modifier.semantics { contentDescription = remove },
        )
    }
}
