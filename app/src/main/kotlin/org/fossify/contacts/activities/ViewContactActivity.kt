package org.fossify.contacts.activities

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getContactUriRawId
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getLookupKeyFromUri
import org.fossify.commons.extensions.getLookupUriRawId
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.contacts.R
import org.fossify.contacts.databinding.ActivityViewContactBinding
import org.fossify.contacts.extensions.callContact
import org.fossify.contacts.extensions.config

class ViewContactActivity : ContactActivity() {
    private var isViewIntent = false
    private val binding by viewBinding(ActivityViewContactBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (checkAppSideloading()) {
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.contactsHeader.root),
            padBottomSystem = listOf(binding.contactScrollview)
        )
        setupContactsHeader(binding.contactsHeader)
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        window.insetsController().isAppearanceLightStatusBars = getProperBackgroundColor().getContrastColor() != Color.WHITE

        isViewIntent = intent.action == ContactsContract.QuickContact.ACTION_QUICK_CONTACT || intent.action == Intent.ACTION_VIEW
        if (isViewIntent) {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    ensureBackgroundThread {
                        initContact()
                    }
                } else {
                    toast(org.fossify.commons.R.string.no_contacts_permission)
                    finish()
                }
            }
        } else {
            ensureBackgroundThread {
                initContact()
            }
        }
    }

    private fun setupButtons() {
        binding.contactCallButton.setOnClickListener {
            contact?.let { contact ->
                callContact(contact)
            }
        }

        binding.contactMessageButton.setOnClickListener {
            if (contact != null) {
                trySendSMS()
            }
        }

        binding.contactEditButton.setOnClickListener {
            contact?.let { contact ->
                Intent(applicationContext, EditContactActivity::class.java).apply {
                    putExtra(CONTACT_ID, contact.id)
                    putExtra(IS_PRIVATE, contact.isPrivate())
                    putExtra(LAUNCHED_FROM_VIEW_CONTACT, true)
                    startActivity(this)
                }
            }
        }
    }

    private fun initContact() {
        var wasLookupKeyUsed = false
        var contactId: Int
        try {
            contactId = intent.getIntExtra(CONTACT_ID, 0)
        } catch (e: Exception) {
            return
        }

        if (contactId == 0 && isViewIntent) {
            val data = intent.data
            if (data != null) {
                val rawId = if (data.path!!.contains("lookup")) {
                    val lookupKey = getLookupKeyFromUri(data)
                    if (lookupKey != null) {
                        contact = ContactsHelper(this).getContactWithLookupKey(lookupKey)
                        wasLookupKeyUsed = true
                    }

                    getLookupUriRawId(data)
                } else {
                    getContactUriRawId(data)
                }

                if (rawId != -1) {
                    contactId = rawId
                }
            }
        }

        if (contactId != 0 && !wasLookupKeyUsed) {
            contact = ContactsHelper(this).getContactWithId(contactId, intent.getBooleanExtra(IS_PRIVATE, false))
        }

        if (contact == null) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            finish()
        } else {
            runOnUiThread {
                gotContact()
            }
        }
    }

    private fun gotContact() {
        if (isDestroyed || isFinishing) {
            return
        }

        binding.contactScrollview.beVisible()
        binding.contactName.setTextColor(getProperTextColor())
        binding.contactName.text = contact!!.getNameToDisplay()

        val phoneNumber = contact!!.phoneNumbers.firstOrNull()?.value ?: ""
        binding.contactNumberSection.beVisibleIf(phoneNumber.isNotEmpty())
        binding.contactNumber.text = if (config.formatPhoneNumbers) phoneNumber.formatPhoneNumber() else phoneNumber

        val email = contact!!.emails.firstOrNull()?.value ?: ""
        binding.contactEmailSection.beVisibleIf(email.isNotEmpty())
        binding.contactEmail.text = email

        val hasNumber = phoneNumber.isNotEmpty()
        binding.contactCallButton.beVisibleIf(hasNumber)
        binding.contactMessageButton.beVisibleIf(hasNumber)

        updateAvatar()
        updateTextColors(binding.contactScrollview)
        styleButtons()
    }

    // Call/Message get their colors from their XML state selectors; only the Edit pill is themed here.
    // Runs after updateTextColors, which flattens the labels' color state lists - restore them.
    private fun styleButtons() {
        val buttonColor = ColorUtils.compositeColors(getProperTextColor().adjustAlpha(0.1f), getProperBackgroundColor())
        binding.contactEditButton.background.applyColorFilter(buttonColor)
        binding.contactEditIcon.applyColorFilter(getProperTextColor())

        binding.contactCallLabel.setTextColor(resources.getColorStateList(R.color.button_call_content, theme))
        binding.contactMessageLabel.setTextColor(resources.getColorStateList(R.color.button_message_content, theme))
    }

    private fun updateAvatar() {
        if (contact!!.photoUri.isEmpty() && contact!!.photo == null) {
            binding.contactPhoto.setImageResource(R.drawable.avatar_placeholder)
        } else {
            val options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .circleCrop()

            Glide.with(this)
                .load(contact!!.photo ?: contact!!.photoUri)
                .apply(options)
                .error(R.drawable.avatar_placeholder)
                .into(binding.contactPhoto)
        }
    }

    override fun customRingtoneSelected(ringtonePath: String) {
        contact!!.ringtone = ringtonePath
    }

    override fun systemRingtoneSelected(uri: Uri?) {
        contact!!.ringtone = uri?.toString() ?: ""
    }
}
