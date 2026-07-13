package com.spectralcheck.io

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class FlacEntry(val uri: Uri, val name: String, val sizeBytes: Long)

/** Recursively lists .flac files under a Storage Access Framework tree. */
object FlacFileScanner {

    fun scan(context: Context, treeUri: Uri): List<FlacEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<FlacEntry>()
        walk(root, out)
        out.sortBy { it.name.lowercase() }
        return out
    }

    private fun walk(dir: DocumentFile, out: MutableList<FlacEntry>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                walk(child, out)
            } else {
                val name = child.name ?: continue
                if (name.endsWith(".flac", ignoreCase = true) || child.type == "audio/flac") {
                    out.add(FlacEntry(child.uri, name, child.length()))
                }
            }
        }
    }
}
