package com.shopledger.pos.core.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object BackupShare {
    /** Shares [file] through the app's FileProvider (`cache/backups/`), copying it there if needed. */
    fun share(context: Context, file: File, title: String) {
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val shareable = if (file.parentFile == dir) file else File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareable)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
