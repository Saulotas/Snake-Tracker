package com.snaketracker.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snaketracker.app.R
import com.snaketracker.app.data.backup.BackupTable
import com.snaketracker.app.logging.FileLogger
import com.snaketracker.app.ui.model.BackupExportState
import com.snaketracker.app.ui.model.BackupImportState
import com.snaketracker.app.ui.model.backupExportMessageFor
import com.snaketracker.app.ui.viewmodel.SnakeViewModel
import java.time.LocalDate

/**
 * Top-level Settings screen. It carries the JSON backup pair: the export
 * row (issue #26) launches the SAF create-document picker with a
 * date-stamped suggested name, and the import row (issue #28) launches the
 * SAF open-document picker for JSON files, gates the replace-everything
 * run behind a destructive-worded confirmation dialog, and reports each
 * outcome in a result dialog.
 *
 * Both pickers live behind their small interfaces
 * ([BackupDestinationPicker], [BackupSourcePicker]) so instrumented tests
 * can drive the dialogs without the system file UI; the real defaults are
 * the SAF contracts wired straight into [SnakeViewModel.exportBackup] and
 * [SnakeViewModel.requestBackupImport].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SnakeViewModel,
    modifier: Modifier = Modifier,
    backupDestinationPicker: BackupDestinationPicker =
        rememberCreateDocumentPicker { uri -> if (uri != null) viewModel.exportBackup(uri) },
    backupSourcePicker: BackupSourcePicker =
        rememberOpenDocumentPicker { uri -> if (uri != null) viewModel.requestBackupImport(uri) },
    debugLogDestinationPicker: BackupDestinationPicker = rememberDebugLogExportPicker()
) {
    val exportState by viewModel.backupExportState.collectAsStateWithLifecycle()
    val pendingImportUri by viewModel.pendingImportUri.collectAsStateWithLifecycle()
    val importState by viewModel.backupImportState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier.testTag("settings_title")
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export_backup)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_export_backup_summary))
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Upload,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .testTag("export_backup_row")
                    .clickable {
                        backupDestinationPicker.launch(backupFileName(LocalDate.now()))
                    }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_import_backup)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_import_backup_summary))
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .testTag("import_backup_row")
                    .clickable { backupSourcePicker.pick() }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export_debug_log)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_export_debug_log_summary))
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null
                    )
                },
                modifier = Modifier
                    .testTag("export_debug_log_row")
                    .clickable { debugLogDestinationPicker.launch("snake-tracker-debug-log-${LocalDate.now()}.txt") }
            )
        }
    }

    if (pendingImportUri != null) {
        BackupImportConfirmDialog(
            onConfirm = viewModel::confirmBackupImport,
            onDismiss = viewModel::cancelBackupImport
        )
    }

    val state = exportState
    if (state != null) {
        BackupExportResultDialog(
            state = state,
            onDismiss = viewModel::dismissBackupExport
        )
    }

    val importResult = importState
    if (importResult != null) {
        BackupImportResultDialog(
            state = importResult,
            onDismiss = viewModel::dismissBackupImport
        )
    }
}

/** Outcome dialog for a backup export: success names the saved file, failure states the reason. */
@Composable
private fun BackupExportResultDialog(
    state: BackupExportState,
    onDismiss: () -> Unit
) {
    val title: String
    val message: String
    when (state) {
        is BackupExportState.Success -> {
            title = stringResource(R.string.backup_export_success_title)
            message = stringResource(R.string.backup_export_success_message, state.fileName)
        }

        is BackupExportState.Failure -> {
            title = stringResource(R.string.backup_export_failure_title)
            // The reason->copy mapping is the model layer's (backupExportMessageFor),
            // so a new reason can only ship with its own message, never a
            // composable edit.
            val reasonText = stringResource(backupExportMessageFor(state.reason))
            message = stringResource(R.string.backup_export_failure_message, reasonText)
        }
    }
    BackupResultDialog(
        title = title,
        message = message,
        dismissLabel = stringResource(R.string.backup_export_dismiss),
        titleTag = "backup_export_dialog_title",
        messageTag = "backup_export_message",
        dismissTag = "backup_export_dismiss",
        onDismiss = onDismiss
    )
}

/**
 * The replace-everything warning shown between picking a file and running
 * the import (issue #28 WB2): destructive-worded, and every path out of it
 * is explicit — [onDismiss] (button or tap-outside) imports nothing.
 */
@Composable
private fun BackupImportConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.backup_import_confirm_title),
                modifier = Modifier.testTag("backup_import_confirm_title")
            )
        },
        text = { Text(stringResource(R.string.backup_import_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("backup_import_confirm_yes")) {
                Text(
                    stringResource(R.string.backup_import_confirm_import),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("backup_import_confirm_cancel")) {
                Text(stringResource(R.string.backup_import_confirm_cancel))
            }
        }
    )
}

/**
 * Outcome dialog for a backup import: success lists the per-table imported
 * counts, failure names the reason (issue #28 WB4).
 */
@Composable
private fun BackupImportResultDialog(
    state: BackupImportState,
    onDismiss: () -> Unit
) {
    val title: String
    val message: String
    when (state) {
        is BackupImportState.Success -> {
            title = stringResource(R.string.backup_import_success_title)
            message = state.counts
                .map { pluralStringResource(importCountPluralFor(it.table), it.count, it.count) }
                .joinToString("\n")
        }

        is BackupImportState.Failure -> {
            title = stringResource(R.string.backup_import_failure_title)
            message = stringResource(state.messageRes)
        }
    }
    BackupResultDialog(
        title = title,
        message = message,
        dismissLabel = stringResource(R.string.backup_import_dismiss),
        titleTag = "backup_import_dialog_title",
        messageTag = "backup_import_message",
        dismissTag = "backup_import_dismiss",
        onDismiss = onDismiss
    )
}

/**
 * The plural resource a table's imported-row count line uses. The `when`
 * is exhaustive over [BackupTable], so a sixth table cannot reach this
 * dialog without naming its own plural here.
 */
private fun importCountPluralFor(table: BackupTable): Int = when (table) {
    BackupTable.SNAKE -> R.plurals.import_count_snakes
    BackupTable.FEEDING -> R.plurals.import_count_feedings
    BackupTable.SHED -> R.plurals.import_count_sheds
    BackupTable.WEIGHT -> R.plurals.import_count_weights
    BackupTable.FOOD_STOCK -> R.plurals.import_count_food_stock
}

/**
 * The one AlertDialog scaffold both backup result dialogs share: a title, a
 * message, and a single dismiss button. Only the copy and the test tags are
 * per-dialog.
 */
@Composable
private fun BackupResultDialog(
    title: String,
    message: String,
    dismissLabel: String,
    titleTag: String,
    messageTag: String,
    dismissTag: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, modifier = Modifier.testTag(titleTag))
        },
        text = {
            Text(message, modifier = Modifier.testTag(messageTag))
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) {
                Text(dismissLabel)
            }
        }
    )
}

/**
 * The suggested name for a backup document: `snake-tracker-backup-<yyyy-MM-dd>.json`,
 * dated with the device's local calendar day ([LocalDate.toString] is ISO
 * `yyyy-MM-dd`). The picker lets the user change it; the gateway then reports
 * the name that was actually saved.
 */
internal fun backupFileName(date: LocalDate): String = "snake-tracker-backup-$date.json"

/** Launches the create-document picker with [suggestedFileName]. */
fun interface BackupDestinationPicker {
    fun launch(suggestedFileName: String)
}

/**
 * Production [BackupDestinationPicker]: `ACTION_CREATE_DOCUMENT` for JSON via
 * [ActivityResultContracts.CreateDocument]; the user-chosen URI (or null on
 * cancel) is handed to [onDocumentPicked].
 */
@Composable
private fun rememberCreateDocumentPicker(
    mimeType: String = JSON_MIME_TYPE,
    onDocumentPicked: (Uri?) -> Unit
): BackupDestinationPicker {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { uri -> onDocumentPicked(uri) }
    return remember(launcher) {
        BackupDestinationPicker { name -> launcher.launch(name) }
    }
}

/**
 * Production picker for the debug log: a plain-text create-document picker
 * whose chosen URI receives the on-device log written by [FileLogger].
 */
@Composable
private fun rememberDebugLogExportPicker(): BackupDestinationPicker {
    val context = LocalContext.current
    return rememberCreateDocumentPicker(mimeType = "text/plain") { uri ->
        if (uri != null) {
            FileLogger.exportTo(context, uri) { ok ->
                Toast.makeText(
                    context,
                    if (ok) R.string.debug_log_export_success else R.string.debug_log_export_failure,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Launches the picker for the JSON backup file the user wants to import.
 * The JSON restriction is the capability itself, so it lives inside the
 * implementations — callers cannot pick a different filter, and the fake
 * has nothing to echo.
 */
fun interface BackupSourcePicker {
    fun pick()
}

/**
 * Production [BackupSourcePicker]: `ACTION_OPEN_DOCUMENT` for JSON via
 * [ActivityResultContracts.OpenDocument]; the user-chosen URI (or null on
 * cancel) is handed to [onDocumentPicked].
 */
@Composable
private fun rememberOpenDocumentPicker(
    onDocumentPicked: (Uri?) -> Unit
): BackupSourcePicker {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onDocumentPicked(uri) }
    return remember(launcher) {
        BackupSourcePicker { launcher.launch(arrayOf(JSON_MIME_TYPE)) }
    }
}

/** The one JSON mime constant both backup pickers share. */
private const val JSON_MIME_TYPE = "application/json"
