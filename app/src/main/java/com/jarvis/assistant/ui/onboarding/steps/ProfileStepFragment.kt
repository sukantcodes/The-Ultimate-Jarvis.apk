package com.jarvis.assistant.ui.onboarding.steps

import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.StepFragment
import com.jarvis.assistant.ui.onboarding.StepScaffold
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.glass
import com.jarvis.assistant.ui.onboarding.lp
import com.jarvis.assistant.ui.onboarding.primaryButton
import com.jarvis.assistant.ui.onboarding.secondaryButton
import com.jarvis.assistant.ui.onboarding.text
import com.jarvis.assistant.ui.onboarding.textButton
import com.jarvis.assistant.ui.onboarding.verticalLayout
import com.jarvis.assistant.ui.onboarding.withAlpha
import com.jarvis.assistant.util.ProfilePhotoStore
import kotlinx.coroutines.launch

/**
 * Stage 3: edits the real user card. Name and photo are written to the same preferences the Settings user card and the
 * home greeting already read. The photo comes from the Android Photo Picker, which needs no storage permission.
 */
class ProfileStepFragment : StepFragment() {

    override val step = OnboardingStep.PROFILE

    private lateinit var avatar: ImageView
    private lateinit var previewName: TextView
    private lateinit var nameInput: EditText
    private lateinit var removePhoto: View
    private lateinit var status: TextView

    private val prefs get() = JarvisApp.instance.preferences

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val copy = ProfilePhotoStore.importPicked(requireContext(), uri)
            if (copy == null) {
                status.text = "That image couldn't be read. Try another photo."
                status.setTextColor(Palette.ROSE)
            } else {
                vm.stagedPhotoUri = copy
                vm.stagedPhotoRemoved = false
                status.text = ""
                refreshPreview()
            }
        }
    }

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        scaffold.heading(
            "Your profile",
            "This is the card JARVIS shows on the home screen and in Settings. Choose a photo and the name JARVIS should call you."
        )

        val card = verticalLayout(ctx).apply {
            background = glass(ctx, 24, Palette.CYAN)
            setPadding(ctx.dp(18), ctx.dp(20), ctx.dp(18), ctx.dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        avatar = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = glass(ctx, 60, Palette.CYAN)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            setOnClickListener { pickPhoto() }
            contentDescription = "Profile photo. Tap to choose one."
        }
        card.addView(avatar, LinearLayout.LayoutParams(ctx.dp(112), ctx.dp(112)))
        previewName = text(ctx, "", 20f, Palette.TEXT, bold = true, gravity = Gravity.CENTER)
        card.addView(previewName, lp(topDp = 14, context = ctx))
        card.addView(text(ctx, "Preview of your user card", 12f, Palette.TEXT_DIM, gravity = Gravity.CENTER), lp(topDp = 4, context = ctx))
        scaffold.addBody(card, 18)

        scaffold.addBody(secondaryButton(ctx, "Choose a photo") { pickPhoto() }, 14)
        removePhoto = textButton(ctx, "Remove photo") {
            vm.stagedPhotoUri = null
            vm.stagedPhotoRemoved = true
            refreshPreview()
        }
        scaffold.addBody(removePhoto, 0)

        scaffold.addBody(text(ctx, "NAME", 12f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.14f }, 18)
        nameInput = EditText(ctx).apply {
            hint = "Sukant"
            setHintTextColor(withAlpha(Palette.TEXT_DIM, 140))
            setTextColor(Palette.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            background = glass(ctx, 16)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            setText(vm.stagedName ?: prefs.userName)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    vm.stagedName = s?.toString()
                    refreshPreview()
                }
            })
        }
        scaffold.addBody(nameInput, 8)

        status = text(ctx, "", 13f, Palette.TEXT_DIM)
        scaffold.addBody(status, 10)

        standardFooter(primaryButton(ctx, "Save profile") { save() }, "Skip for now")
        refreshPreview()
    }

    private fun pickPhoto() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun currentPhoto(): String? = when {
        vm.stagedPhotoRemoved -> null
        vm.stagedPhotoUri != null -> vm.stagedPhotoUri
        else -> prefs.profilePhotoUri.ifBlank { null }
    }

    private fun refreshPreview() {
        val name = (vm.stagedName ?: prefs.userName).trim().ifBlank { "Sukant" }
        previewName.text = name
        val photo = currentPhoto()
        if (photo == null) {
            // Same default portrait the Settings user card shows until a personal photo is chosen.
            avatar.setImageResource(com.jarvis.assistant.R.drawable.jarvis_default_user)
            avatar.imageTintList = null
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar.setPadding(0, 0, 0, 0)
        } else {
            try {
                avatar.setImageURI(Uri.parse(photo))
                avatar.imageTintList = null
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.setPadding(0, 0, 0, 0)
            } catch (_: Exception) {
                avatar.setImageResource(com.jarvis.assistant.R.drawable.jarvis_default_user)
            }
        }
        removePhoto.visibility = if (photo == null) View.GONE else View.VISIBLE
    }

    private fun save() {
        val name = (nameInput.text?.toString() ?: "").trim()
        if (name.isEmpty() && prefs.userName.isBlank() && currentPhoto() == null) {
            status.text = "Add a name or a photo to save, or skip this step."
            status.setTextColor(Palette.AMBER)
            return
        }
        try {
            if (name.isNotEmpty()) {
                prefs.userName = name
                prefs.profileNameOverride = name
            }
            when {
                vm.stagedPhotoRemoved -> prefs.profilePhotoUri = ""
                vm.stagedPhotoUri != null -> prefs.profilePhotoUri = vm.stagedPhotoUri!!
            }
            ProfilePhotoStore.cleanup(requireContext(), listOf(prefs.profilePhotoUri).filter { it.isNotBlank() })
        } catch (e: Exception) {
            status.text = "Couldn't save your profile. Please try again."
            status.setTextColor(Palette.ROSE)
            return
        }
        vm.store.profileSaved = true
        vm.stagedName = null
        vm.stagedPhotoUri = null
        vm.stagedPhotoRemoved = false
        Toast.makeText(requireContext(), "Profile saved", Toast.LENGTH_SHORT).show()
        advance(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
