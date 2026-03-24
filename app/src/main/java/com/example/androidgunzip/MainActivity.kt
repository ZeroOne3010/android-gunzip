package com.example.androidgunzip

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private var selectedInputUri: Uri? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        if (!isGzipUri(uri)) {
            selectedInputUri = null
            Toast.makeText(this, getString(R.string.error_not_gzip_file), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        selectedInputUri = uri
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.warning_persist_permission_unavailable), Toast.LENGTH_SHORT).show()
        }

        val inputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        inputButton.text = getString(R.string.selected_input_file, getDisplayName(uri) ?: uri.lastPathSegment ?: uri.toString())

        val outputFilenameEditText = findViewById<TextInputEditText>(R.id.outputFilenameEditText)
        val suggestedOutputName = suggestOutputFilename(getDisplayName(uri))
        if (!suggestedOutputName.isNullOrBlank()) {
            outputFilenameEditText.setText(suggestedOutputName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectInputButton = findViewById<MaterialButton>(R.id.selectInputButton)
        selectInputButton.setOnClickListener {
            // Use SAF with */* so cloud/document providers like Google Drive are available.
            openDocumentLauncher.launch(arrayOf("*/*"))
        }
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

    private fun getDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) cursor.getString(columnIndex) else null
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }

    private fun suggestOutputFilename(displayName: String?): String? {
        if (displayName.isNullOrBlank()) return null
        return if (displayName.endsWith(".gz", ignoreCase = true)) {
            displayName.dropLast(3)
        } else {
            "$displayName.out"
        }
    }

    private companion object {
        const val GZIP_MAGIC_FIRST_BYTE = 0x1F
        const val GZIP_MAGIC_SECOND_BYTE = 0x8B
    }
}
