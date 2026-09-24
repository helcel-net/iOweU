package net.helcel.owu.helper

import android.content.Context
import android.content.SharedPreferences

/** The preference file opened directly, under the name and mode androidx's
 *  PreferenceManager uses, so the preference UI framework is not pulled in. */
fun defaultPreferences(ctx: Context): SharedPreferences =
    ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE)



