package com.example.androidgunzip

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var selectedInputUri: Uri? = null
    private var selectedDestinationTreeUri: Uri? = null
    private var isExtracting = false

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        validateAndSetInputFile(uri)
    }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(this, getString(R.string.destination_tree_selection_canceled), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val treeUri = result.data?.data
            if (treeUri == null) {
                Toast.makeText(this, getString(R.string.error_destination_tree_open_failed), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            selectedDestinationTreeUri = treeUri
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (_: SecurityException) {
                Toast.makeText(this, getString(R.string.warning_persist_permission_unavailable), Toast.LENGTH_SHORT).show()
            }

            Toast.makeText(this, getString(R.string.destination_tree_selected), Toast.LENGTH_SHORT).show()
            refreshDestinationLabels()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val outputFilenameEditText = findViewById<TextInputEditText>(R.id.outputFilenameEditText)
        outputFilenameEditText.addTextChangedListener {
            refreshDestinationLabels()
        }

        val selectInputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        selectInputButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.picker_sort_toast), Toast.LENGTH_SHORT).show()
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        val selectOutputDestinationButton = findViewById<MaterialButton>(R.id.selectOutputDestinationButton)
        selectOutputDestinationButton.setOnClickListener {
            openCustomDestinationPicker()
        }

        val extractButton = findViewById<MaterialButton>(R.id.extractButton)
        extractButton.setOnClickListener {
            if (isExtracting) {
                return@setOnClickListener
            }

            if (!validateOutputFilenameBeforeExtraction()) {
                return@setOnClickListener
            }

            val inputUri = selectedInputUri
            if (inputUri == null) {
                Toast.makeText(this, getString(R.string.error_select_input_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val outputName = resolveRequestedOutputName()
            val plannedDestination = describePlannedDestination(outputName)
            showExtractionConfirmation(inputUri, outputName, plannedDestination)
        }

        refreshDestinationLabels()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun openCustomDestinationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            selectedDestinationTreeUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        openDocumentTreeLauncher.launch(intent)
    }

    private fun showExtractionConfirmation(inputUri: Uri, outputName: String, destinationSummary: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_extract_title))
            .setMessage(getString(R.string.confirm_extract_message, destinationSummary))
            .setPositiveButton(getString(R.string.confirm_extract_continue)) { _, _ ->
                createOutputTargetAndExtract(inputUri, outputName)
            }
            .setNegativeButton(getString(R.string.confirm_extract_cancel), null)
            .show()
    }

    private fun createOutputTargetAndExtract(inputUri: Uri, outputName: String) {
        val destinationTreeUri = selectedDestinationTreeUri
        thread {
            val outputTarget = if (destinationTreeUri != null) {
                createOutputInTree(destinationTreeUri, outputName)
            } else {
                createDefaultOutputTarget(outputName)
            }

            runOnUiThread {
                if (outputTarget == null) {
                    Toast.makeText(this, getString(R.string.error_destination_create_failed), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                refreshDestinationLabels(outputTarget.label)
                extractGzipOffMainThread(inputUri, outputTarget)
            }
        }
    }

    private fun createDefaultOutputTarget(outputName: String): OutputTarget? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createOutputInMediaStoreDownloads(outputName) ?: createOutputInAppDownloads(outputName)
        } else {
            createOutputInAppDownloads(outputName)
        }
    }

    private fun createOutputInMediaStoreDownloads(outputName: String): OutputTarget? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, OCTET_STREAM_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return OutputTarget(uri = uri, label = uri.toString(), mode = OutputMode.ContentResolver)
    }

    private fun createOutputInAppDownloads(outputName: String): OutputTarget? {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val outputFile = uniqueFileInDirectory(downloadsDir, outputName)
        return OutputTarget(
            uri = Uri.fromFile(outputFile),
            label = outputFile.absolutePath,
            mode = OutputMode.FilePath,
            file = outputFile
        )
    }

    private fun createOutputInTree(treeUri: Uri, outputName: String): OutputTarget? {
        val documentUri = try {
            val docTreeUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(
                contentResolver,
                docTreeUri,
                OCTET_STREAM_MIME,
                outputName
            )
        } catch (_: Exception) {
            null
        } ?: return null

        return OutputTarget(uri = documentUri, label = documentUri.toString(), mode = OutputMode.ContentResolver)
    }

    private fun uniqueFileInDirectory(directory: File, outputName: String): File {
        val baseName = outputName.substringBeforeLast('.', outputName)
        val extension = outputName.substringAfterLast('.', "")

        var index = 0
        while (true) {
            val candidateName = if (index == 0) {
                outputName
            } else if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            index++
        }
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action != Intent.ACTION_VIEW) {
            return
        }

        val incomingUri = incomingIntent.data
        if (incomingUri == null) {
            Toast.makeText(this, getString(R.string.error_opened_uri_missing), Toast.LENGTH_LONG).show()
            return
        }

        if (!isSupportedViewUri(incomingUri)) {
            Toast.makeText(this, getString(R.string.error_opened_uri_unsupported_scheme), Toast.LENGTH_LONG).show()
            return
        }

        if (!hasReadablePermission(incomingUri)) {
            Toast.makeText(this, getString(R.string.error_opened_uri_permission_guidance), Toast.LENGTH_LONG).show()
            return
        }

        validateAndSetInputFile(incomingUri)
    }

    private fun isSupportedViewUri(uri: Uri): Boolean {
        return uri.scheme == CONTENT_SCHEME || uri.scheme == FILE_SCHEME
    }

    private fun hasReadablePermission(uri: Uri): Boolean {
        if (uri.scheme == FILE_SCHEME) {
            return canOpenInputStream(uri)
        }

        val hasTransientGrant =
            checkUriPermission(
                uri,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasTransientGrant) {
            return true
        }

        return canOpenInputStream(uri)
    }

    private fun canOpenInputStream(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.close()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun validateAndSetInputFile(uri: Uri) {
        val inputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        inputButton.isEnabled = false
        inputButton.text = getString(R.string.validating_selected_file)

        thread {
            val displayName = resolveDisplayName(uri)
            val isGzip = isGzipUri(uri)

            runOnUiThread {
                inputButton.isEnabled = true

                if (!isGzip) {
                    selectedInputUri = null
                    inputButton.text = getString(R.string.select_input_file)
                    Toast.makeText(this, getString(R.string.error_not_gzip_file), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                selectedInputUri = uri
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    Toast.makeText(this, getString(R.string.warning_persist_permission_unavailable), Toast.LENGTH_SHORT).show()
                }

                inputButton.text = getString(
                    R.string.selected_input_file,
                    displayName ?: uri.lastPathSegment ?: uri.toString()
                )

                val outputFilenameEditText = findViewById<TextInputEditText>(R.id.outputFilenameEditText)
                val suggestedOutputName = suggestOutputFilename(displayName)
                outputFilenameEditText.setText(suggestedOutputName)
                outputFilenameEditText.setSelection(suggestedOutputName.length)
                findViewById<TextInputLayout>(R.id.outputFilenameLayout).error = null
                refreshDestinationLabels()
            }
        }
    }

    private fun validateOutputFilenameBeforeExtraction(): Boolean {
        val outputFilenameLayout = findViewById<TextInputLayout>(R.id.outputFilenameLayout)
        val outputName = resolveRequestedOutputName()

        when {
            outputName.isBlank() -> {
                outputFilenameLayout.error = getString(R.string.error_output_filename_required)
                return false
            }

            !isSafeFilename(outputName) -> {
                outputFilenameLayout.error = getString(R.string.error_output_filename_invalid)
                return false
            }

            else -> {
                outputFilenameLayout.error = null
            }
        }

        return true
    }

    private fun resolveRequestedOutputName(): String {
        val outputFilenameEditText = findViewById<TextInputEditText>(R.id.outputFilenameEditText)
        return outputFilenameEditText.text?.toString()?.trim().orEmpty()
    }

    private fun isSafeFilename(filename: String): Boolean {
        if (filename == "." || filename == "..") {
            return false
        }

        val forbiddenChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        return filename.none { it in forbiddenChars || it.code < 0x20 }
    }

    private fun isGzipUri(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val first = stream.read()
                val second = stream.read()
                first == GZIP_MAGIC_FIRST_BYTE && second == GZIP_MAGIC_SECOND_BYTE
            } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val nameFromDocumentContract = if (DocumentsContract.isDocumentUri(this, uri)) {
            queryDisplayName(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        } else {
            null
        }

        return nameFromDocumentContract
            ?: queryDisplayName(uri, OpenableColumns.DISPLAY_NAME)
            ?: sanitizeUriFallbackName(uri.lastPathSegment)
    }

    private fun sanitizeUriFallbackName(lastPathSegment: String?): String? {
        if (lastPathSegment.isNullOrBlank()) {
            return null
        }

        val basename = Uri.decode(lastPathSegment)
            .substringAfterLast('/')
            .substringAfterLast(':')
            .substringAfterLast('\\')
            .trim()

        if (basename.isBlank()) {
            return null
        }

        val forbiddenChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val sanitized = basename.filter { it.code >= 0x20 && it !in forbiddenChars }.trim()
        return sanitized.ifBlank { null }
    }

    private fun queryDisplayName(uri: Uri, columnName: String): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(columnName), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(columnName)
                if (columnIndex >= 0) cursor.getString(columnIndex) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun suggestOutputFilename(displayName: String?): String {
        if (displayName.isNullOrBlank()) return DEFAULT_OUTPUT_FILENAME

        return if (displayName.endsWith(GZIP_EXTENSION, ignoreCase = true)) {
            displayName.dropLast(GZIP_EXTENSION.length).ifBlank { DEFAULT_OUTPUT_FILENAME }
        } else {
            "$displayName.out"
        }
    }

    private fun refreshDestinationLabels(overrideResolvedDestination: String? = null) {
        val outputDestinationLabel = findViewById<TextView>(R.id.outputDestinationLabel)
        val outputResolvedLabel = findViewById<TextView>(R.id.outputResolvedLabel)
        val outputName = resolveRequestedOutputName().ifBlank { DEFAULT_OUTPUT_FILENAME }

        val destinationLabel = selectedDestinationTreeUri?.toString()
            ?: getString(R.string.output_destination_default_label)
        outputDestinationLabel.text = getString(R.string.output_destination_selected, destinationLabel)

        val resolved = overrideResolvedDestination ?: describePlannedDestination(outputName)
        outputResolvedLabel.text = getString(R.string.output_resolved_preview, resolved)
    }

    private fun describePlannedDestination(outputName: String): String {
        selectedDestinationTreeUri?.let { treeUri ->
            return "$treeUri/$outputName"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Downloads.EXTERNAL_CONTENT_URI}/$outputName"
        } else {
            val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            File(baseDir, outputName).absolutePath
        }
    }

    private fun extractGzipOffMainThread(inputUri: Uri, outputTarget: OutputTarget) {
        isExtracting = true
        setUiEnabled(false)
        Toast.makeText(this, getString(R.string.extract_in_progress), Toast.LENGTH_SHORT).show()

        thread {
            val result = extractGzip(inputUri, outputTarget)

            runOnUiThread {
                isExtracting = false
                setUiEnabled(true)

                when (result) {
                    is ExtractionResult.Success -> {
                        val outputName = resolveDisplayName(outputTarget.uri) ?: outputTarget.label
                        val sizeText = formatBytes(result.outputBytes)
                        Toast.makeText(
                            this,
                            getString(R.string.extract_success_message, outputName, sizeText),
                            Toast.LENGTH_LONG
                        ).show()
                        refreshDestinationLabels(outputTarget.label)
                    }

                    is ExtractionResult.InvalidGzip -> {
                        Toast.makeText(this, getString(R.string.error_extract_invalid_gzip), Toast.LENGTH_LONG).show()
                    }

                    is ExtractionResult.WriteFailure -> {
                        Toast.makeText(this, getString(R.string.error_extract_write_failed), Toast.LENGTH_LONG).show()
                    }

                    is ExtractionResult.PermissionOrCanceled -> {
                        Toast.makeText(
                            this,
                            getString(R.string.error_extract_permission_or_canceled),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    is ExtractionResult.GenericFailure -> {
                        Toast.makeText(
                            this,
                            getString(R.string.error_extract_generic, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun extractGzip(inputUri: Uri, outputTarget: OutputTarget): ExtractionResult {
        return try {
            contentResolver.openInputStream(inputUri)?.use { rawInput ->
                GZIPInputStream(rawInput).use { gzipInput ->
                    when (outputTarget.mode) {
                        OutputMode.ContentResolver -> {
                            contentResolver.openOutputStream(outputTarget.uri, "w")?.use { output ->
                                val bytes = copyToOutput(gzipInput, output)
                                ExtractionResult.Success(bytes)
                            } ?: ExtractionResult.PermissionOrCanceled
                        }

                        OutputMode.FilePath -> {
                            val file = outputTarget.file ?: return ExtractionResult.WriteFailure
                            FileOutputStream(file).use { output ->
                                val bytes = copyToOutput(gzipInput, output)
                                ExtractionResult.Success(bytes)
                            }
                        }
                    }
                }
            } ?: ExtractionResult.PermissionOrCanceled
        } catch (_: ZipException) {
            ExtractionResult.InvalidGzip
        } catch (_: SecurityException) {
            ExtractionResult.PermissionOrCanceled
        } catch (_: FileNotFoundException) {
            ExtractionResult.PermissionOrCanceled
        } catch (_: IOException) {
            ExtractionResult.WriteFailure
        } catch (exception: Exception) {
            ExtractionResult.GenericFailure(exception.message ?: "unknown error")
        }
    }

    private fun copyToOutput(input: GZIPInputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
            totalBytes += count
        }
        output.flush()
        return totalBytes
    }

    private fun setUiEnabled(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.selectInputButton).isEnabled = enabled
        findViewById<MaterialButton>(R.id.selectOutputDestinationButton).isEnabled = enabled
        findViewById<MaterialButton>(R.id.extractButton).isEnabled = enabled
        findViewById<TextInputEditText>(R.id.outputFilenameEditText).isEnabled = enabled
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }

        val format = DecimalFormat("#,##0.#")
        return String.format(Locale.US, "%s %s", format.format(value), units[unitIndex])
    }

    private sealed class ExtractionResult {
        data class Success(val outputBytes: Long) : ExtractionResult()
        data object InvalidGzip : ExtractionResult()
        data object WriteFailure : ExtractionResult()
        data object PermissionOrCanceled : ExtractionResult()
        data class GenericFailure(val message: String) : ExtractionResult()
    }

    private data class OutputTarget(
        val uri: Uri,
        val label: String,
        val mode: OutputMode,
        val file: File? = null
    )

    private enum class OutputMode {
        ContentResolver,
        FilePath
    }

    private companion object {
        const val GZIP_MAGIC_FIRST_BYTE = 0x1F
        const val GZIP_MAGIC_SECOND_BYTE = 0x8B
        const val GZIP_EXTENSION = ".gz"
        const val DEFAULT_OUTPUT_FILENAME = "output.out"
        const val BUFFER_SIZE = 8 * 1024
        const val CONTENT_SCHEME = "content"
        const val FILE_SCHEME = "file"
        const val OCTET_STREAM_MIME = "application/octet-stream"
    }
}
