package app.cactusai.assistant.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    fun hasMicrophone(context: Context): Boolean = granted(context, Manifest.permission.RECORD_AUDIO)
    fun hasContacts(context: Context): Boolean = granted(context, Manifest.permission.READ_CONTACTS)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
