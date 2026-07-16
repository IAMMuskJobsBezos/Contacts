package org.fossify.contacts.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.MediaStore
import android.telephony.PhoneNumberUtils
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getCachePhoto
import org.fossify.commons.extensions.getContactUriRawId
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getEmptyContact
import org.fossify.commons.extensions.getLookupUriRawId
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getVisibleContactSources
import org.fossify.commons.extensions.hasContactPermissions
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.DEFAULT_ADDRESS_TYPE
import org.fossify.commons.helpers.DEFAULT_EMAIL_TYPE
import org.fossify.commons.helpers.DEFAULT_EVENT_TYPE
import org.fossify.commons.helpers.DEFAULT_PHONE_NUMBER_TYPE
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.PHOTO_ADDED
import org.fossify.commons.helpers.PHOTO_CHANGED
import org.fossify.commons.helpers.PHOTO_REMOVED
import org.fossify.commons.helpers.PHOTO_UNCHANGED
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.contacts.Address
import org.fossify.commons.models.contacts.Contact
import org.fossify.commons.models.contacts.Email
import org.fossify.commons.models.contacts.Event
import org.fossify.commons.models.contacts.Organization
import org.fossify.contacts.R
import org.fossify.contacts.databinding.ActivityEditContactBinding
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.getCachePhotoUri
import org.fossify.contacts.extensions.viewContact
import org.fossify.contacts.helpers.ADD_NEW_CONTACT_NUMBER
import org.fossify.contacts.helpers.IS_FROM_SIMPLE_CONTACTS
import org.fossify.contacts.helpers.KEY_EMAIL
import org.fossify.contacts.helpers.KEY_NAME
import java.util.LinkedList
import java.util.Locale

class EditContactActivity : ContactActivity() {
    companion object {
        private const val INTENT_TAKE_PHOTO = 1
        private const val INTENT_CHOOSE_PHOTO = 2
        private const val INTENT_CROP_PHOTO = 3

        private const val TAKE_PHOTO = 1
        private const val CHOOSE_PHOTO = 2
        private const val REMOVE_PHOTO = 3

        private const val OPTIONAL_FIELDS_EXPANDED = "optional_fields_expanded"

        // experiment (2026-07-16): while the optional-fields toggle is pinned, a background-colored
        // backdrop strip pins with it so scrolling content disappears beneath the pill's zone
        // instead of showing around it. Flip to false to let content pass visibly beside the pill.
        private const val PIN_TOGGLE_BLOCKS_CONTENT_BEHIND = true
    }

    private var lastPhotoIntentUri: Uri? = null
    private var isSaving = false
    private var isThirdPartyIntent = false
    private var originalContactSource = ""
    private var optionalFieldsExpanded = false
    private var isTogglePinned = false
    private var toggleRestingColor = 0
    private val binding by viewBinding(ActivityEditContactBinding::inflate)

    enum class PrimaryNumberStatus {
        UNCHANGED, STARRED, UNSTARRED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (checkAppSideloading()) {
            return
        }

        optionalFieldsExpanded = savedInstanceState?.getBoolean(OPTIONAL_FIELDS_EXPANDED) ?: false

        setupEdgeToEdge(
            padTopSystem = listOf(binding.contactsHeader.root),
            padBottomImeAndSystem = listOf(binding.contactScrollview)
        )
        setupContactsHeader(binding.contactsHeader)
        setupButtons()

        val action = intent.action
        isThirdPartyIntent = action == Intent.ACTION_EDIT || action == Intent.ACTION_INSERT || action == ADD_NEW_CONTACT_NUMBER
        val isFromSimpleContacts = intent.getBooleanExtra(IS_FROM_SIMPLE_CONTACTS, false)
        if (isThirdPartyIntent && !isFromSimpleContacts) {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    handlePermission(PERMISSION_WRITE_CONTACTS) {
                        if (it) {
                            initContact()
                        } else {
                            toast(org.fossify.commons.R.string.no_contacts_permission)
                            hideKeyboard()
                            finish()
                        }
                    }
                } else {
                    toast(org.fossify.commons.R.string.no_contacts_permission)
                    hideKeyboard()
                    finish()
                }
            }
        } else {
            initContact()
        }
    }

    override fun onResume() {
        super.onResume()
        // the base class assumes the old full-bleed photo header and forces light status bar icons
        window.insetsController().isAppearanceLightStatusBars = getProperBackgroundColor().getContrastColor() != Color.WHITE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPTIONAL_FIELDS_EXPANDED, optionalFieldsExpanded)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                INTENT_TAKE_PHOTO, INTENT_CHOOSE_PHOTO -> startCropPhotoIntent(lastPhotoIntentUri, resultData?.data)
                INTENT_CROP_PHOTO -> loadAvatar(lastPhotoIntentUri.toString())
            }
        }
    }

    private fun setupButtons() {
        // Cancel/Save colors and the outline-to-fill press states live entirely in
        // button_cancel_background / button_save_background + their content color selectors
        binding.contactSaveButton.setOnClickListener { saveContact() }
        binding.contactCancelButton.setOnClickListener {
            hideKeyboard()
            finish()
        }

        // opaque composite of the old translucent tint, so the pill fully covers
        // fields scrolling beneath it while pinned
        toggleRestingColor = ColorUtils.compositeColors(getProperTextColor().adjustAlpha(0.1f), getProperBackgroundColor())
        binding.contactOptionalToggle.background.applyColorFilter(toggleRestingColor)
        binding.contactToggleBackdrop.setBackgroundColor(getProperBackgroundColor())
        // no outline -> no elevation shadow: the backdrop must hide content, not draw a line
        binding.contactToggleBackdrop.outlineProvider = null
        binding.contactOptionalToggle.setOnClickListener {
            setOptionalFieldsExpanded(!optionalFieldsExpanded)
        }

        binding.contactScrollview.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateOptionalTogglePinning(scrollY)
        }
    }

    // the toggle scrolls with the list, but pins below the header's bottom divider instead of
    // leaving the screen; scrolling back down releases it into the list again. The backdrop
    // strip pins with it so content vanishes under the pill's zone rather than showing beside it.
    private fun updateOptionalTogglePinning(scrollY: Int) {
        val toggle = binding.contactOptionalToggle
        val backdrop = binding.contactToggleBackdrop
        val pinnedGap = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.normal_margin)
        val overshoot = scrollY + pinnedGap - toggle.top
        val shouldPin = overshoot > 0
        isTogglePinned = shouldPin

        if (shouldPin) {
            toggle.translationY = overshoot.toFloat()
            toggle.translationZ = 4 * resources.displayMetrics.density
            if (PIN_TOGGLE_BLOCKS_CONTENT_BEHIND) {
                backdrop.translationY = scrollY.toFloat()
                backdrop.translationZ = 3 * resources.displayMetrics.density
                backdrop.visibility = View.VISIBLE
            }
        } else {
            toggle.translationY = 0f
            toggle.translationZ = 0f
            backdrop.visibility = View.INVISIBLE
        }
    }

    private fun setOptionalFieldsExpanded(expanded: Boolean) {
        optionalFieldsExpanded = expanded
        binding.contactOptionalHolder.beVisibleIf(expanded)
        binding.contactOptionalLabel.setText(if (expanded) R.string.hide_optional_fields else R.string.show_optional_fields)
        binding.contactOptionalArrow.setImageResource(
            if (expanded) {
                org.fossify.commons.R.drawable.ic_chevron_up_vector
            } else {
                org.fossify.commons.R.drawable.ic_chevron_down_vector
            }
        )
        binding.contactOptionalArrow.applyColorFilter(getProperTextColor())
    }

    private fun initContact() {
        var contactId = intent.getIntExtra(CONTACT_ID, 0)
        val action = intent.action
        if (contactId == 0 && (action == Intent.ACTION_EDIT || action == ADD_NEW_CONTACT_NUMBER)) {
            val data = intent.data
            if (data != null && data.path != null) {
                val rawId = if (data.path!!.contains("lookup")) {
                    if (data.pathSegments.last().startsWith("local_")) {
                        data.path!!.substringAfter("local_").toInt()
                    } else {
                        getLookupUriRawId(data)
                    }
                } else {
                    getContactUriRawId(data)
                }

                if (rawId != -1) {
                    contactId = rawId
                }
            }
        }

        if (contactId != 0) {
            ensureBackgroundThread {
                contact = ContactsHelper(this).getContactWithId(contactId, intent.getBooleanExtra(IS_PRIVATE, false))
                if (contact == null) {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                    hideKeyboard()
                    finish()
                } else {
                    runOnUiThread {
                        gotContact()
                    }
                }
            }
        } else {
            gotContact()
        }
    }

    private fun gotContact() {
        binding.contactScrollview.beVisible()
        if (contact == null) {
            setupNewContact()
        } else {
            originalContactSource = contact!!.source
            originalRingtone = contact?.ringtone
        }

        val action = intent.action
        if (((contact!!.id == 0 && action == Intent.ACTION_INSERT) || action == ADD_NEW_CONTACT_NUMBER) && intent.extras != null) {
            val phoneNumber = getPhoneNumberFromIntent(intent)
            if (phoneNumber != null) {
                contact!!.phoneNumbers.add(PhoneNumber(phoneNumber, DEFAULT_PHONE_NUMBER_TYPE, "", phoneNumber.normalizePhoneNumber()))
            }

            val email = intent.getStringExtra(KEY_EMAIL)
            if (email != null) {
                contact!!.emails.add(Email(email, DEFAULT_EMAIL_TYPE, ""))
            }

            val firstName = intent.extras!!.get(KEY_NAME)
            if (firstName != null) {
                contact!!.firstName = firstName.toString()
            }

            val data = intent.extras!!.getParcelableArrayList<ContentValues>("data")
            if (data != null) {
                parseIntentData(data)
            }
        }

        setupViews()
    }

    private fun setupViews() {
        // the title sits in the fixed header outside contact_scrollview, so
        // updateTextColors below does not reach it
        binding.contactTitle.setTextColor(getProperTextColor())

        binding.contactFirstName.setText(contact!!.firstName)
        binding.contactSurname.setText(contact!!.surname)
        updateContactTitle()
        binding.contactFirstName.doAfterTextChanged { updateContactTitle() }
        binding.contactSurname.doAfterTextChanged { updateContactTitle() }

        val firstNumber = contact!!.phoneNumbers.firstOrNull()
        binding.contactNumber.setText(firstNumber?.value ?: "")
        binding.contactNumber.tag = firstNumber?.normalizedNumber ?: ""

        binding.contactEmail.setText(contact!!.emails.firstOrNull()?.value ?: "")

        val address = contact!!.addresses.firstOrNull()
        val streetLines = (address?.street?.ifEmpty { address.value } ?: "").split("\n")
        binding.contactAddressLine1.setText(streetLines.getOrNull(0) ?: "")
        binding.contactAddressLine2.setText(streetLines.drop(1).joinToString(" ").trim())
        binding.contactCity.setText(address?.city ?: "")
        binding.contactState.setText(address?.region ?: "")
        binding.contactZip.setText(address?.postcode ?: "")

        binding.contactNotes.setText(contact!!.notes)

        updateAvatar()
        binding.contactPhoto.setOnClickListener { trySetPhoto() }

        val hasOptionalData = arrayOf(
            binding.contactEmail, binding.contactAddressLine1, binding.contactAddressLine2,
            binding.contactCity, binding.contactState, binding.contactZip, binding.contactNotes
        ).any { it.value.isNotEmpty() }
        setOptionalFieldsExpanded(optionalFieldsExpanded || hasOptionalData)

        updateTextColors(binding.contactScrollview)
    }

    private fun updateContactTitle() {
        val name = arrayOf(binding.contactFirstName.value, binding.contactSurname.value)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        binding.contactTitle.text = name.ifEmpty { getString(R.string.new_contact) }
    }

    private fun updateAvatar() {
        if (contact!!.photoUri.isEmpty() && contact!!.photo == null) {
            showAvatarPlaceholder()
        } else {
            loadAvatar(contact!!.photoUri, contact!!.photo)
        }
    }

    private fun showAvatarPlaceholder() {
        currentContactPhotoPath = ""
        contact?.photo = null
        binding.contactPhoto.setImageResource(R.drawable.avatar_placeholder)
    }

    private fun loadAvatar(path: String, bitmap: Bitmap? = null) {
        currentContactPhotoPath = path
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .circleCrop()

        Glide.with(this)
            .load(bitmap ?: path)
            .apply(options)
            .error(R.drawable.avatar_placeholder)
            .into(binding.contactPhoto)
    }

    private fun setupNewContact() {
        originalContactSource = if (hasContactPermissions()) config.lastUsedContactSource else SMT_PRIVATE
        contact = getEmptyContact()

        // if the last used contact source is not available anymore, use the first available one. Could happen at ejecting SIM card
        ContactsHelper(this).getSaveableContactSources { sources ->
            val sourceNames = sources.map { it.name }
            if (!sourceNames.contains(originalContactSource)) {
                originalContactSource = sourceNames.first()
                contact?.source = originalContactSource
            }
        }
    }

    private fun saveContact() {
        if (isSaving || contact == null) {
            return
        }

        val visibleFields = arrayOf(
            binding.contactFirstName, binding.contactSurname, binding.contactNumber,
            binding.contactEmail, binding.contactAddressLine1, binding.contactAddressLine2,
            binding.contactCity, binding.contactState, binding.contactZip, binding.contactNotes
        )
        if (visibleFields.all { it.value.isEmpty() } && currentContactPhotoPath.isEmpty()) {
            toast(R.string.fields_empty)
            return
        }

        val contactValues = fillContactValues()

        val oldPhotoUri = contact!!.photoUri
        val oldPrimary = contact!!.phoneNumbers.find { it.isPrimary }
        val newPrimary = contactValues.phoneNumbers.find { it.isPrimary }
        val primaryState = Pair(oldPrimary, newPrimary)

        contact = contactValues

        ensureBackgroundThread {
            config.lastUsedContactSource = contact!!.source
            when {
                contact!!.id == 0 -> insertNewContact(false)
                originalContactSource != contact!!.source -> insertNewContact(true)
                else -> {
                    val photoUpdateStatus = getPhotoUpdateStatus(oldPhotoUri, contact!!.photoUri)
                    updateContact(photoUpdateStatus, primaryState)
                }
            }
        }
    }

    // the screen edits only the fields it shows - any other data on the contact is preserved untouched
    private fun fillContactValues(): Contact {
        val phoneNumbers = ArrayList(contact!!.phoneNumbers.map { it.copy() })
        val newNumber = binding.contactNumber.value
        if (newNumber.isEmpty()) {
            if (phoneNumbers.isNotEmpty()) {
                phoneNumbers.removeAt(0)
            }
        } else {
            var normalizedNumber = newNumber.normalizePhoneNumber()

            // fix a glitch when the app thinks that a number changed because we fetched
            // normalized number +421903123456, then at getting it from the input field we get 0903123456, can happen at WhatsApp contacts
            val fetchedNormalizedNumber = binding.contactNumber.tag?.toString() ?: ""
            if (PhoneNumberUtils.compare(normalizedNumber, fetchedNormalizedNumber)) {
                normalizedNumber = fetchedNormalizedNumber
            }

            if (phoneNumbers.isEmpty()) {
                phoneNumbers.add(PhoneNumber(newNumber, DEFAULT_PHONE_NUMBER_TYPE, "", normalizedNumber))
            } else {
                phoneNumbers[0] = phoneNumbers[0].copy(value = newNumber, normalizedNumber = normalizedNumber)
            }
        }

        val emails = ArrayList(contact!!.emails.map { it.copy() })
        val newEmail = binding.contactEmail.value
        if (newEmail.isEmpty()) {
            if (emails.isNotEmpty()) {
                emails.removeAt(0)
            }
        } else if (emails.isEmpty()) {
            emails.add(Email(newEmail, DEFAULT_EMAIL_TYPE, ""))
        } else {
            emails[0] = emails[0].copy(value = newEmail)
        }

        val addresses = ArrayList(contact!!.addresses.map { it.copy() })
        val street = arrayOf(binding.contactAddressLine1.value, binding.contactAddressLine2.value)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val city = binding.contactCity.value
        val region = binding.contactState.value
        val postcode = binding.contactZip.value
        if (street.isEmpty() && city.isEmpty() && region.isEmpty() && postcode.isEmpty()) {
            if (addresses.isNotEmpty()) {
                addresses.removeAt(0)
            }
        } else {
            val existing = addresses.firstOrNull()
            val country = existing?.country ?: ""
            val pobox = existing?.pobox ?: ""
            val neighborhood = existing?.neighborhood ?: ""

            /* from DAVdroid */
            val lineLocality = arrayOf(postcode, city).filter { it.isNotEmpty() }.joinToString(" ")
            val lines = LinkedList<String>()
            street.split("\n").filterTo(lines) { it.isNotEmpty() }
            if (lineLocality.isNotEmpty()) lines += lineLocality
            if (region.isNotEmpty()) lines += region
            if (country.isNotEmpty()) lines += country.uppercase(Locale.getDefault())
            val formattedAddress = lines.joinToString("\n")

            val newAddress = Address(
                formattedAddress, existing?.type ?: DEFAULT_ADDRESS_TYPE, existing?.label ?: "",
                country, region, city, postcode, pobox, street, neighborhood
            )
            if (addresses.isEmpty()) {
                addresses.add(newAddress)
            } else {
                addresses[0] = newAddress
            }
        }

        return contact!!.copy(
            firstName = binding.contactFirstName.value,
            surname = binding.contactSurname.value,
            photoUri = currentContactPhotoPath,
            phoneNumbers = phoneNumbers,
            emails = emails,
            addresses = addresses,
            notes = binding.contactNotes.value
        )
    }

    private fun insertNewContact(deleteCurrentContact: Boolean) {
        isSaving = true
        if (!deleteCurrentContact) {
            toast(R.string.inserting)
        }

        if (ContactsHelper(this@EditContactActivity).insertContact(contact!!)) {
            if (deleteCurrentContact) {
                contact!!.source = originalContactSource
                ContactsHelper(this).deleteContact(contact!!, false) {
                    finishSaved(openSavedContact = true)
                }
            } else {
                finishSaved(openSavedContact = true)
            }
        } else {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }

    // after a save the user lands on the contact's view screen - unless this edit was opened
    // FROM that screen, which refreshes itself in onResume when we return to it
    private fun finishSaved(openSavedContact: Boolean) {
        setResult(Activity.RESULT_OK)
        hideKeyboard()

        if (intent.getBooleanExtra(LAUNCHED_FROM_VIEW_CONTACT, false)) {
            finish()
            return
        }

        if (!openSavedContact && contact!!.id != 0) {
            runOnUiThread {
                viewContact(contact!!)
                finish()
            }
            return
        }

        // a freshly inserted contact has no id on our side - look it up by what was just saved
        val savedFirstName = contact!!.firstName
        val savedSurname = contact!!.surname
        val savedNumber = contact!!.phoneNumbers.firstOrNull()?.normalizedNumber
        ContactsHelper(this).getContacts { contacts ->
            val saved = contacts.filter {
                it.firstName == savedFirstName && it.surname == savedSurname &&
                    (savedNumber == null || it.phoneNumbers.any { number -> number.normalizedNumber == savedNumber })
            }.maxByOrNull { it.id }

            if (saved != null) {
                viewContact(saved)
            }
            finish()
        }
    }

    private fun updateContact(photoUpdateStatus: Int, primaryState: Pair<PhoneNumber?, PhoneNumber?>) {
        isSaving = true
        if (ContactsHelper(this@EditContactActivity).updateContact(contact!!, photoUpdateStatus)) {
            val status = getPrimaryNumberStatus(primaryState.first, primaryState.second)
            if (status != PrimaryNumberStatus.UNCHANGED) {
                updateDefaultNumberForDuplicateContacts(primaryState, status) {
                    finishSaved(openSavedContact = false)
                }
            } else {
                finishSaved(openSavedContact = false)
            }
        } else {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }

    private fun updateDefaultNumberForDuplicateContacts(
        toggleState: Pair<PhoneNumber?, PhoneNumber?>,
        primaryStatus: PrimaryNumberStatus,
        callback: () -> Unit
    ) {
        val contactsHelper = ContactsHelper(this)

        contactsHelper.getDuplicatesOfContact(contact!!, false) { contacts ->
            ensureBackgroundThread {
                val displayContactSources = getVisibleContactSources()
                contacts.filter { displayContactSources.contains(it.source) }.forEach { contact ->
                    val duplicate = contactsHelper.getContactWithId(contact.id, contact.isPrivate())
                    if (duplicate != null) {
                        if (primaryStatus == PrimaryNumberStatus.UNSTARRED) {
                            val number = duplicate.phoneNumbers.find { it.normalizedNumber == toggleState.first!!.normalizedNumber }
                            number?.isPrimary = false
                        } else if (primaryStatus == PrimaryNumberStatus.STARRED) {
                            val number = duplicate.phoneNumbers.find { it.normalizedNumber == toggleState.second!!.normalizedNumber }
                            if (number != null) {
                                duplicate.phoneNumbers.forEach {
                                    it.isPrimary = false
                                }
                                number.isPrimary = true
                            }
                        }

                        contactsHelper.updateContact(duplicate, PHOTO_UNCHANGED)
                    }
                }

                runOnUiThread {
                    callback.invoke()
                }
            }
        }
    }

    private fun getPrimaryNumberStatus(oldPrimary: PhoneNumber?, newPrimary: PhoneNumber?): PrimaryNumberStatus {
        return if (oldPrimary != null && newPrimary != null && oldPrimary != newPrimary) {
            PrimaryNumberStatus.STARRED
        } else if (oldPrimary == null && newPrimary != null) {
            PrimaryNumberStatus.STARRED
        } else if (oldPrimary != null && newPrimary == null) {
            PrimaryNumberStatus.UNSTARRED
        } else {
            PrimaryNumberStatus.UNCHANGED
        }
    }

    private fun getPhotoUpdateStatus(oldUri: String, newUri: String): Int {
        return if (oldUri.isEmpty() && newUri.isNotEmpty()) {
            PHOTO_ADDED
        } else if (oldUri.isNotEmpty() && newUri.isEmpty()) {
            PHOTO_REMOVED
        } else if (oldUri != newUri) {
            PHOTO_CHANGED
        } else {
            PHOTO_UNCHANGED
        }
    }

    private fun trySetPhoto() {
        val items = arrayListOf(
            RadioItem(TAKE_PHOTO, getString(org.fossify.commons.R.string.take_photo)),
            RadioItem(CHOOSE_PHOTO, getString(org.fossify.commons.R.string.choose_photo))
        )

        if (currentContactPhotoPath.isNotEmpty() || contact!!.photo != null) {
            items.add(RadioItem(REMOVE_PHOTO, getString(R.string.remove_photo)))
        }

        RadioGroupDialog(this, items) {
            when (it as Int) {
                TAKE_PHOTO -> startTakePhotoIntent()
                CHOOSE_PHOTO -> startChoosePhotoIntent()
                else -> showAvatarPlaceholder()
            }
        }
    }

    private fun startCropPhotoIntent(primaryUri: Uri?, backupUri: Uri?) {
        if (primaryUri == null) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
            return
        }

        var imageUri = primaryUri
        var bitmap = MediaStore.Images.Media.getBitmap(contentResolver, primaryUri)
        if (bitmap == null) {
            imageUri = backupUri
            try {
                bitmap = MediaStore.Images.Media.getBitmap(contentResolver, backupUri) ?: return
            } catch (e: Exception) {
                showErrorToast(e)
                return
            }

            // we might have received an URI which we have no permission to send further, so just copy the received image in a new uri (for example from Google Photos)
            val newFile = getCachePhoto()
            val fos = newFile.outputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            imageUri = getCachePhotoUri(newFile)
        }

        hideKeyboard()
        lastPhotoIntentUri = getCachePhotoUri()
        Intent("com.android.camera.action.CROP").apply {
            setDataAndType(imageUri, "image/*")
            putExtra(MediaStore.EXTRA_OUTPUT, lastPhotoIntentUri)
            putExtra("outputX", 512)
            putExtra("outputY", 512)
            putExtra("aspectX", 1)
            putExtra("aspectY", 1)
            putExtra("crop", "true")
            putExtra("scale", "true")
            putExtra("scaleUpIfNeeded", "true")
            clipData = ClipData("Attachment", arrayOf("text/primaryUri-list"), ClipData.Item(lastPhotoIntentUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            try {
                startActivityForResult(this, INTENT_CROP_PHOTO)
            } catch (e: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun startTakePhotoIntent() {
        hideKeyboard()
        val uri = getCachePhotoUri()
        lastPhotoIntentUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)

            try {
                startActivityForResult(this, INTENT_TAKE_PHOTO)
            } catch (e: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun startChoosePhotoIntent() {
        hideKeyboard()
        val uri = getCachePhotoUri()
        lastPhotoIntentUri = uri
        Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
            clipData = ClipData("Attachment", arrayOf("text/uri-list"), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(MediaStore.EXTRA_OUTPUT, uri)

            try {
                startActivityForResult(this, INTENT_CHOOSE_PHOTO)
            } catch (e: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun parseIntentData(data: ArrayList<ContentValues>) {
        data.forEach {
            when (it.get(StructuredName.MIMETYPE)) {
                CommonDataKinds.Email.CONTENT_ITEM_TYPE -> parseEmail(it)
                StructuredPostal.CONTENT_ITEM_TYPE -> parseAddress(it)
                CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> parseOrganization(it)
                CommonDataKinds.Event.CONTENT_ITEM_TYPE -> parseEvent(it)
                Website.CONTENT_ITEM_TYPE -> parseWebsite(it)
                Note.CONTENT_ITEM_TYPE -> parseNote(it)
            }
        }
    }

    private fun parseEmail(contentValues: ContentValues) {
        val type = contentValues.getAsInteger(CommonDataKinds.Email.DATA2) ?: DEFAULT_EMAIL_TYPE
        val emailValue = contentValues.getAsString(CommonDataKinds.Email.DATA1) ?: return
        val email = Email(emailValue, type, "")
        contact!!.emails.add(email)
    }

    private fun parseAddress(contentValues: ContentValues) {
        val type = contentValues.getAsInteger(StructuredPostal.DATA2) ?: DEFAULT_ADDRESS_TYPE
        val addressValue = contentValues.getAsString(StructuredPostal.DATA4)
            ?: contentValues.getAsString(StructuredPostal.DATA1) ?: return
        val country = contentValues.getAsString(StructuredPostal.COUNTRY)
        val region = contentValues.getAsString(StructuredPostal.REGION)
        val city = contentValues.getAsString(StructuredPostal.CITY)
        val postcode = contentValues.getAsString(StructuredPostal.POSTCODE)
        val pobox = contentValues.getAsString(StructuredPostal.POBOX)
        val street = contentValues.getAsString(StructuredPostal.STREET)
        val neighborhood = contentValues.getAsString(StructuredPostal.NEIGHBORHOOD)
        val address = Address(addressValue, type, "", country, region, city, postcode, pobox, street, neighborhood)
        contact!!.addresses.add(address)
    }

    private fun parseOrganization(contentValues: ContentValues) {
        val company = contentValues.getAsString(CommonDataKinds.Organization.DATA1) ?: ""
        val jobPosition = contentValues.getAsString(CommonDataKinds.Organization.DATA4) ?: ""
        contact!!.organization = Organization(company, jobPosition)
    }

    private fun parseEvent(contentValues: ContentValues) {
        val type = contentValues.getAsInteger(CommonDataKinds.Event.DATA2) ?: DEFAULT_EVENT_TYPE
        val eventValue = contentValues.getAsString(CommonDataKinds.Event.DATA1) ?: return
        val event = Event(eventValue, type)
        contact!!.events.add(event)
    }

    private fun parseWebsite(contentValues: ContentValues) {
        val website = contentValues.getAsString(Website.DATA1) ?: return
        contact!!.websites.add(website)
    }

    private fun parseNote(contentValues: ContentValues) {
        val note = contentValues.getAsString(Note.DATA1) ?: return
        contact!!.notes = note
    }

    override fun customRingtoneSelected(ringtonePath: String) {
        contact!!.ringtone = ringtonePath
    }

    override fun systemRingtoneSelected(uri: Uri?) {
        contact!!.ringtone = uri?.toString() ?: ""
    }
}
