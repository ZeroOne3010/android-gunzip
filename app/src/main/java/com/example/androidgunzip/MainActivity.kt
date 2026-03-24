package com.example.androidgunzip

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.FileNotFoundException
import java.io.IOException
import java.text.DecimalFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var selectedInputUri: Uri? = null
    private var selectedOutputUri: Uri? = null
    private var isExtracting = false

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        validateAndSetInputFile(uri)
    }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri == null) {
                Toast.makeText(
                    this,
                    getString(R.string.error_extract_permission_or_canceled),
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }

            selectedOutputUri = uri
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                Toast.makeText(
                    this,
                    getString(R.string.warning_persist_permission_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            }

            updateOutputDestinationLabel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectInputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        selectInputButton.setOnClickListener {
            // Use SAF with */* so cloud/document providers like Google Drive are available.
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        val selectOutputDestinationButton = findViewById<MaterialButton>(R.id.selectOutputDestinationButton)
        selectOutputDestinationButton.setOnClickListener {
            if (validateOutputFilenameBeforeExtraction()) {
                val outputName =
                    findViewById<TextInputEditText>(R.id.outputFilenameEditText).text?.toString()?.trim().orEmpty()
                createDocumentLauncher.launch(outputName)
            }
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

            val outputUri = selectedOutputUri
            if (outputUri == null) {
                Toast.makeText(this, getString(R.string.error_select_output_destination), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            extractGzipOffMainThread(inputUri, outputUri)
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
            }
        }
    }

    private fun validateOutputFilenameBeforeExtraction(): Boolean {
        val outputFilenameLayout = findViewById<TextInputLayout>(R.id.outputFilenameLayout)
        val outputFilenameEditText = findViewById<TextInputEditText>(R.id.outputFilenameEditText)
        val outputName = outputFilenameEditText.text?.toString()?.trim().orEmpty()

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

    private fun updateOutputDestinationLabel(uri: Uri) {
        val outputDestinationLabel = findViewById<android.widget.TextView>(R.id.outputDestinationLabel)
        val outputName = resolveDisplayName(uri) ?: uri.lastPathSegment ?: uri.toString()
        outputDestinationLabel.text = getString(R.string.output_destination_selected, outputName)
    }

    private fun extractGzipOffMainThread(inputUri: Uri, outputUri: Uri) {
        isExtracting = true
        setUiEnabled(false)
        Toast.makeText(this, getString(R.string.extract_in_progress), Toast.LENGTH_SHORT).show()

        thread {
            val result = extractGzip(inputUri, outputUri)

            runOnUiThread {
                isExtracting = false
                setUiEnabled(true)

                when (result) {
                    is ExtractionResult.Success -> {
                        val outputName = resolveDisplayName(outputUri) ?: outputUri.lastPathSegment ?: outputUri.toString()
                        val sizeText = formatBytes(result.outputBytes)
                        Toast.makeText(
                            this,
                            getString(R.string.extract_success_message, outputName, sizeText),
                            Toast.LENGTH_LONG
                        ).show()
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

    private fun extractGzip(inputUri: Uri, outputUri: Uri): ExtractionResult {
        return try {
            contentResolver.openInputStream(inputUri)?.use { rawInput ->
                GZIPInputStream(rawInput).use { gzipInput ->
                    contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val count = gzipInput.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            totalBytes += count
                        }
                        output.flush()
                        ExtractionResult.Success(totalBytes)
                    } ?: return ExtractionResult.PermissionOrCanceled
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

    private companion object {
        const val GZIP_MAGIC_FIRST_BYTE = 0x1F
        const val GZIP_MAGIC_SECOND_BYTE = 0x8B
        const val GZIP_EXTENSION = ".gz"
        const val DEFAULT_OUTPUT_FILENAME = "output.out"
        const val BUFFER_SIZE = 8 * 1024
    }
}
