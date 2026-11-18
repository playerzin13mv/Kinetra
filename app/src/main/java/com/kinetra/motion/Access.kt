package com.kinetra.motion

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Access {
    private const val VISUAL = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            VISUAL
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun ok(c: Context, p: String): Boolean =
        c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasAny(c: Context): Boolean = perms().any { ok(c, it) }

    fun request(a: Activity, code: Int) {
        Prefs.setB(a, "asked_perm", true)
        a.requestPermissions(perms(), code)
    }

    fun asked(c: Context): Boolean = Prefs.b(c, "asked_perm", false)
}
