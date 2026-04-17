package com.airi.assistant.domain.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class PermissionService(private val context: Context) {

    fun hasCalendarAccess(): Boolean =
        hasPermission(Manifest.permission.READ_CALENDAR)

    fun hasContactsAccess(): Boolean =
        hasPermission(Manifest.permission.READ_CONTACTS)

    fun hasStorageAccess(): Boolean =
        hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun getMissingPermissions(vararg permissions: String): List<String> =
        permissions.filter { !hasPermission(it) }
}
