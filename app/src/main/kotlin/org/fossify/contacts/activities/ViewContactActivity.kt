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
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.isPackageInstalled
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
    companion object {
        // must match org.fossify.phone.activities.MainActivity's own EXTRA_TAB + tab indices
        private const val PHONE_EXTRA_TAB = "extra_tab"
        private const val PHONE_TAB_KEYPAD = 0
        private const val PHONE_TAB_CONTACTS = 1
        private const val PHONE_TAB_RECENTS = 2
        private const val PHONE_PACKAGE = "org.fossify.phone"
        private const val PHONE_PACKAGE_DEBUG = "org.fossify.phone.debug"
    }

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
            padBottomSystem = listOf(binding.contactTabBar.root)
        )
        setupContactsHeader(binding.contactsHeader)
        setupButtons()
        setupTabBar()
    }

    override fun onResume() {
        super.onResume()
        window.insetsController().isAppearanceLightStatusBars = getProperBackgroundColor().getContrastColor() != Color.WHITE
        setupTabBarColors()

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

    // jumps into the Phone app on the tapped tab; Contacts app has no launcher tile of its
    // own, so "Contacts" here also means "back to Phone's contact list" (2026-07-17 plan)
    private fun setupTabBar() {
        binding.contactTabBar.apply {
            tabBarKeypad.setOnClickListener { returnToPhoneTab(PHONE_TAB_KEYPAD) }
            tabBarContacts.setOnClickListener { returnToPhoneTab(PHONE_TAB_CONTACTS) }
            tabBarRecents.setOnClickListener { returnToPhoneTab(PHONE_TAB_RECENTS) }
        }
    }

    private fun returnToPhoneTab(tab: Int) {
        val targetPackage = when {
            isPackageInstalled(PHONE_PACKAGE) -> PHONE_PACKAGE
            isPackageInstalled(PHONE_PACKAGE_DEBUG) -> PHONE_PACKAGE_DEBUG
            else -> null
        }

        if (targetPackage == null) {
            toast(org.fossify.commons.R.string.no_app_found)
            return
        }

        try {
            // target MainActivity directly (not getLaunchIntentForPackage's launcher intent,
            // which routes through SplashActivity and would drop this extra along the way)
            Intent().apply {
                setClassName(targetPackage, "org.fossify.phone.activities.MainActivity")
                putExtra(PHONE_EXTRA_TAB, tab)
                // NEW_TASK is required here: this call originates from inside Contacts'
                // own task, and without it Android embeds a second MainActivity instance
                // inside THAT task instead of returning to Phone's own task (matched via
                // taskAffinity) - silently leaving two live MainActivity instances alive,
                // with taps landing unpredictably on whichever one has focus
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                startActivity(this)
            }
            finish()
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.no_app_found)
        }
    }

    // active tab = dark text color, inactive = light primary (matches the Phone fork's bar);
    // Contacts is always "active" here since this screen only exists inside that context
    private fun setupTabBarColors() {
        val textColor = getProperTextColor()
        val primaryColor = getProperPrimaryColor()
        binding.contactTabBar.apply {
            tabBarKeypadIcon.applyColorFilter(primaryColor)
            tabBarKeypadLabel.setTextColor(primaryColor)
            tabBarRecentsIcon.applyColorFilter(primaryColor)
            tabBarRecentsLabel.setTextColor(primaryColor)
            tabBarContactsIcon.applyColorFilter(textColor)
            tabBarContactsLabel.setTextColor(textColor)
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
