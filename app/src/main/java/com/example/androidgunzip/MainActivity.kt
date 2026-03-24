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
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var selectedInputUri: Uri? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        validateAndSetInputFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectInputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        selectInputButton.setOnClickListener {
            // Use SAF with */* so cloud/document providers like Google Drive are available.
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        val extractButton = findViewById<MaterialButton>(R.id.extractButton)
        extractButton.setOnClickListener {
            if (validateOutputFilenameBeforeExtraction()) {
                Toast.makeText(this, getString(R.string.extract_ready_message), Toast.LENGTH_SHORT).show()
            }
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
        if (selectedInputUri == null) {
            Toast.makeText(this, getString(R.string.error_select_input_first), Toast.LENGTH_SHORT).show()
            return false
        }

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

    private companion object {
        const val GZIP_MAGIC_FIRST_BYTE = 0x1F
        const val GZIP_MAGIC_SECOND_BYTE = 0x8B
        const val GZIP_EXTENSION = ".gz"
        const val DEFAULT_OUTPUT_FILENAME = "output.out"
    }
}
