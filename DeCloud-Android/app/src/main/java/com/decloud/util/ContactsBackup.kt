package com.decloud.util

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for backing up contacts using native Android ContactsContract API
 * Exports contacts in VCF (vCard) format - industry standard for contact backup
 */
object ContactsBackup {

    private const val TAG = "ContactsBackup"

    /**
     * Contact data holder
     */
    data class ContactInfo(
        val id: String,
        val displayName: String,
        val phoneNumbers: List<String>,
        val emails: List<String>,
        val organization: String?,
        val title: String?,
        val note: String?
    )

    /**
     * Result of contacts backup
     */
    data class ContactsBackupResult(
        val success: Boolean,
        val contactCount: Int,
        val filePath: String?,
        val fileSize: Long,
        val errorMessage: String? = null
    )

    /**
     * Get count of contacts on device
     */
    suspend fun getContactCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.count
            } ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error counting contacts: ${e.message}")
            0
        }
    }

    /**
     * Get all contacts from device
     */
    suspend fun getAllContacts(context: Context): List<ContactInfo> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactInfo>()

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val hasPhoneColumn = it.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (it.moveToNext()) {
                    try {
                        val id = it.getString(idColumn)
                        val name = it.getString(nameColumn) ?: "Unknown"
                        val hasPhone = it.getInt(hasPhoneColumn) > 0

                        // Get phone numbers
                        val phones = if (hasPhone) getPhoneNumbers(context, id) else emptyList()

                        // Get emails
                        val emails = getEmails(context, id)

                        // Get organization info
                        val (org, title) = getOrganization(context, id)

                        // Get notes
                        val note = getNote(context, id)

                        contacts.add(
                            ContactInfo(
                                id = id,
                                displayName = name,
                                phoneNumbers = phones,
                                emails = emails,
                                organization = org,
                                title = title,
                                note = note
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading contact: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}")
        }

        contacts
    }

    /**
     * Get phone numbers for a contact
     */
    private fun getPhoneNumbers(context: Context, contactId: String): List<String> {
        val phones = mutableListOf<String>()

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { cursor ->
                val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    cursor.getString(numberColumn)?.let { phones.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading phone numbers: ${e.message}")
        }

        return phones.distinct()
    }

    /**
     * Get emails for a contact
     */
    private fun getEmails(context: Context, contactId: String): List<String> {
        val emails = mutableListOf<String>()

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { cursor ->
                val emailColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    cursor.getString(emailColumn)?.let { emails.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading emails: ${e.message}")
        }

        return emails.distinct()
    }

    /**
     * Get organization and title for a contact
     */
    private fun getOrganization(context: Context, contactId: String): Pair<String?, String?> {
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                    ContactsContract.CommonDataKinds.Organization.TITLE
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val org = cursor.getString(0)
                    val title = cursor.getString(1)
                    return Pair(org, title)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading organization: ${e.message}")
        }

        return Pair(null, null)
    }

    /**
     * Get notes for a contact
     */
    private fun getNote(context: Context, contactId: String): String? {
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading note: ${e.message}")
        }

        return null
    }

    /**
     * Export contacts to VCF file
     */
    suspend fun exportToVcf(
        context: Context,
        outputDir: File,
        progressCallback: ((current: Int, total: Int) -> Unit)? = null
    ): ContactsBackupResult = withContext(Dispatchers.IO) {
        try {
            val contacts = getAllContacts(context)

            if (contacts.isEmpty()) {
                return@withContext ContactsBackupResult(
                    success = true,
                    contactCount = 0,
                    filePath = null,
                    fileSize = 0
                )
            }

            // Create output file
            outputDir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "contacts_backup_${dateFormat.format(Date())}.vcf"
            val outputFile = File(outputDir, fileName)

            FileOutputStream(outputFile).use { fos ->
                contacts.forEachIndexed { index, contact ->
                    val vcard = contactToVcard(contact)
                    fos.write(vcard.toByteArray(Charsets.UTF_8))
                    progressCallback?.invoke(index + 1, contacts.size)
                }
            }

            ContactsBackupResult(
                success = true,
                contactCount = contacts.size,
                filePath = outputFile.absolutePath,
                fileSize = outputFile.length()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting contacts: ${e.message}")
            ContactsBackupResult(
                success = false,
                contactCount = 0,
                filePath = null,
                fileSize = 0,
                errorMessage = e.message
            )
        }
    }

    /**
     * Convert contact to vCard format (VCF 3.0)
     */
    private fun contactToVcard(contact: ContactInfo): String {
        val sb = StringBuilder()

        sb.appendLine("BEGIN:VCARD")
        sb.appendLine("VERSION:3.0")

        // Full name
        sb.appendLine("FN:${escapeVcardValue(contact.displayName)}")

        // Structured name (simplified - just use display name)
        val nameParts = contact.displayName.split(" ", limit = 2)
        val firstName = nameParts.getOrNull(0) ?: ""
        val lastName = nameParts.getOrNull(1) ?: ""
        sb.appendLine("N:${escapeVcardValue(lastName)};${escapeVcardValue(firstName)};;;")

        // Phone numbers
        contact.phoneNumbers.forEach { phone ->
            sb.appendLine("TEL;TYPE=CELL:${escapeVcardValue(phone)}")
        }

        // Emails
        contact.emails.forEach { email ->
            sb.appendLine("EMAIL:${escapeVcardValue(email)}")
        }

        // Organization
        if (contact.organization != null || contact.title != null) {
            val org = contact.organization ?: ""
            sb.appendLine("ORG:${escapeVcardValue(org)}")
            contact.title?.let {
                sb.appendLine("TITLE:${escapeVcardValue(it)}")
            }
        }

        // Notes
        contact.note?.let {
            sb.appendLine("NOTE:${escapeVcardValue(it)}")
        }

        sb.appendLine("END:VCARD")
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Escape special characters in vCard values
     */
    private fun escapeVcardValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
}
