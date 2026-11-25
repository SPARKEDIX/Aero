package com.aero.voice

import android.content.Context
import android.provider.ContactsContract

/**
 * Resolves a spoken name/number to a phone number.
 */
object ContactHelper {

    /** Returns a phone number for [query]: digits if present, else contact lookup. */
    fun findNumber(ctx: Context, query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null

        // Direct number: keep digits and leading '+' if the query is numeric.
        val digits = q.replace(Regex("[^0-9+]"), "")
        if (digits.replace("+", "").length >= 7) return digits

        // Contact lookup by display name (fuzzy).
        return try {
            val cr = ctx.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            cr.query(uri, projection, null, null, null)?.use { c ->
                val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol)?.trim().orEmpty()
                    val num = c.getString(numCol)?.trim().orEmpty()
                    if (num.isNotEmpty() && name.isNotEmpty() &&
                        (name.lowercase().contains(q.lowercase()) || q.lowercase().contains(name.lowercase()))
                    ) {
                        return@use num
                    }
                }
                null
            }
        } catch (t: Throwable) {
            null
        }
    }
}
