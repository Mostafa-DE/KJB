package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Backup and restore. Milestone B2 replaces this body; Settings calls it as it is declared here.

/**
 * Settings > Backup's actions (B2): Back up now, Restore, and turning Automatic backup on, which asks for a folder first.
 * Until then the first two do nothing and the switch only stores its value ([setAutomatic]).
 */
@Composable
fun rememberBackupActions(setAutomatic: (Boolean) -> Unit): BackupActions = remember(setAutomatic) { BackupActions(backUpNow = {}, restore = {}, setAutomatic = setAutomatic) }
