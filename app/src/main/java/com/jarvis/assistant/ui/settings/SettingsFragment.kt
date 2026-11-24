package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.FragmentSettingsBinding
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.flow.collect

/**
 * The Settings hub: the operator profile card and one row per category. Every category is its own screen with its
 * own layout and Activity (see SettingsScreens.kt), so nothing here is nested inside a shared settings layout.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var profileImageJob: Job? = null
    private var loadedRemoteAvatarUrl: String? = null
    private val profileImageClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    private val profilePhotoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && isAdded) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant a temporary read permission only.
            }
            preferences.profilePhotoUri = uri.toString()
            showProfilePhoto()
            toast("Profile photo saved")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Secret Maya companion settings: long-press the Settings heading to open.
        binding.tvSettingsTitle.setOnLongClickListener {
            startActivity(Intent(requireContext(), MayaModeActivity::class.java))
            true
        }
        setupProfileCard()
        setupCategories()
        setupSearch()
        binding.btnSceneStyle.setOnClickListener { SettingsSceneSheet.show(requireActivity()) }
        // A persisted session is hydrated immediately, then its server profile may arrive asynchronously.
        viewLifecycleOwner.lifecycleScope.launch {
            com.jarvis.assistant.auth.AuthManager.session.collect {
                if (_binding != null && isAdded) loadUserData()
            }
        }
        applyStyle()
        loadUserData()
    }

    private fun setupCategories() {
        fun go(row: View, target: Class<*>) {
            row.setOnClickListener {
                // In See-through mode the next screen shows this one underneath its glass: take the picture first.
                com.jarvis.assistant.ui.glass.ScreenSnapshot.capture(requireActivity()) {
                    if (isAdded) startActivity(Intent(requireContext(), target))
                }
            }
        }
        go(binding.rowCatAi, AiSettingsActivity::class.java)
        go(binding.rowCatVoice, VoiceSettingsActivity::class.java)
        go(binding.rowCatSecurity, SecuritySettingsActivity::class.java)
        go(binding.rowCatAlerts, AlertsSettingsActivity::class.java)
        go(binding.rowCatMemory, MemorySettingsActivity::class.java)
        go(binding.rowCatPermissions, PermissionsSettingsActivity::class.java)
        go(binding.rowCatAppearance, AppearanceSettingsActivity::class.java)
        go(binding.rowCatAbout, AboutSettingsActivity::class.java)
        go(binding.rowCatOwner, OwnerSettingsActivity::class.java)
    }

    // ------------------------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------------------------

    private fun setupSearch() {
        binding.etSettingsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (_binding != null) showSearchResults(s?.toString().orEmpty())
            }
        })
        binding.btnSearchClear.setOnClickListener { binding.etSettingsSearch.setText("") }
        binding.etSettingsSearch.setOnEditorActionListener { v, _, _ ->
            val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
    }

    /** Swaps the hub for a single glass card of matches while something is typed. */
    private fun showSearchResults(query: String) {
        val searching = query.isNotBlank()
        binding.btnSearchClear.visibility = if (searching) View.VISIBLE else View.GONE
        val content = binding.settingsContent
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            child.visibility = if (child === binding.searchResults) (if (searching) View.VISIBLE else View.GONE)
            else (if (searching) View.GONE else View.VISIBLE)
        }
        val box = binding.searchResults
        box.removeAllViews()
        if (!searching) return
        val ctx = requireContext()
        val dp = { v: Int -> UiKit.dp(ctx, v) }
        val results = SettingsIndex.search(query)
        val glass = layoutInflater.inflate(R.layout.view_glass_card, box, false) as ViewGroup
        val list = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        if (results.isEmpty()) {
            list.addView(UiKit.text(ctx, getString(R.string.set_search_empty), 14f, muted = true).apply { setPadding(0, dp(14), 0, dp(14)) })
        }
        results.forEachIndexed { i, entry ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { openSearchResult(entry) }
            }
            row.addView(UiKit.text(ctx, entry.title, 16f, bold = true))
            row.addView(UiKit.text(ctx, entry.category + " › " + entry.detail, 12f, muted = true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            list.addView(row)
            if (i < results.size - 1) {
                list.addView(View(ctx).apply { setBackgroundColor(android.graphics.Color.argb(40, 128, 128, 128)) },
                    android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }
        glass.addView(list, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(glass)
    }

    private fun openSearchResult(entry: SettingsIndex.Entry) {
        val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etSettingsSearch.windowToken, 0)
        com.jarvis.assistant.ui.glass.ScreenSnapshot.capture(requireActivity()) {
            if (!isAdded) return@capture
            val intent = Intent(requireContext(), entry.target)
            entry.focusRow?.let { intent.putExtra(SettingsScreenActivity.EXTRA_FOCUS_ROW, it) }
            startActivity(intent)
        }
    }

    /** Paints the identity card, avatar ring, chips and icon tiles in the active theme's colours. */
    private fun applyStyle() {
        val accent = ThemeManager.getThemeAccentColor()
        SettingsDeck.styleOrb(binding.orbBadge, accent)
        SettingsDeck.styleAvatarRing(binding.frameProfileRing, accent, ThemeManager.getThemeSecondaryColor())
        SettingsDeck.styleOnlineDot(binding.viewProfileOnlineDot, ThemeManager.getThemeSuccessColor())
        SettingsDeck.styleChip(binding.btnProfileEditTile, accent, 14f)
        binding.btnProfileEditTile.setOnClickListener { showEditNameDialog() }
        bindProfileNetworkChip()
        applyIconShape()
    }

    /** Repaints every Settings icon with the shape chosen in Theme & 3D Orb Colors. */
    fun applyIconShape() {
        if (_binding != null) {
            SettingsDeck.styleIcons(binding.root, ThemeManager.getThemeAccentColor(), preferences.settingsIconShape)
        }
    }

    override fun onResume() {
        super.onResume()
        com.jarvis.assistant.ui.glass.GlassBackdropView.refreshAll()
        if (_binding != null) {
            applyIconShape()
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (!hidden) {
            binding.root.visibility = View.VISIBLE
            loadUserData()
        } else {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileImageJob?.cancel()
        profileImageJob = null
        _binding = null
    }

    // ------------------------------------------------------------------------------------------
    // Profile card
    // ------------------------------------------------------------------------------------------

    private fun setupProfileCard() {
        binding.cardProfile.setOnClickListener { showEditNameDialog() }
        binding.ivProfileAvatar.setOnClickListener {
            profilePhotoPicker.launch(arrayOf("image/*"))
        }
        binding.ivProfileAvatar.setOnLongClickListener {
            preferences.profilePhotoUri = ""
            showProfilePhoto()
            toast("Profile photo removed")
            true
        }
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }
    }

    private fun loadUserData() {
        val ctx = requireContext()
        val green = ContextCompat.getColor(ctx, R.color.status_green)
        val sapphire = ContextCompat.getColor(ctx, R.color.accent_sapphire)

        binding.tvProfileName.text = preferences.profileNameOverride.ifBlank {
            preferences.accountDisplayName.ifBlank { preferences.userName.ifBlank { "Sukant" } }
        }
        showProfilePhoto()
        binding.tvProfileSubtitle.text = "JARVIS OPERATOR • PRIMARY USER"

        if (preferences.hasOwnerVoiceProfile()) {
            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(green)
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = ColorStateList.valueOf(green)
        } else {
            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(sapphire)
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = ColorStateList.valueOf(sapphire)
        }
        binding.tvProfilePersonaChip.text = preferences.personality.uppercase()
    }

    private fun showEditNameDialog() {
        val current = preferences.profileNameOverride.ifBlank {
            preferences.accountDisplayName.ifBlank { preferences.userName.ifBlank { "Sukant" } }
        }
        PremiumDialog.input(
            requireContext(), "OPERATOR PROFILE", "Operator Name",
            "Enter the name Jarvis should use to address you.", current, "Your name"
        ) { newName ->
            preferences.userName = newName
            preferences.profileNameOverride = newName
            loadUserData()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun bindProfileNetworkChip() {
        val mgr = com.jarvis.assistant.udn.UdnManager.get(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                mgr.state.collect { st ->
                    if (_binding == null) return@collect
                    val signedIn = com.jarvis.assistant.auth.AuthManager.isSignedIn
                    val (text, tone) = when {
                        !st.configured || !signedIn -> "NETWORK · SIGN IN" to ThemeManager.getThemeSecondaryColor()
                        !st.enrolled -> "NETWORK · NOT JOINED" to ThemeManager.getThemeWarningColor()
                        !st.authorized -> "NETWORK · JOINING" to ThemeManager.getThemeWarningColor()
                        else -> ((if (st.isParent) "PARENT" + (st.slot?.let { " $it" } ?: "") else "CHILD") +
                            " · ${st.devices.count { it.isAuthorized && it.online }} ONLINE") to ThemeManager.getThemeSuccessColor()
                    }
                    binding.tvProfileNetwork.text = text
                    SettingsDeck.styleChip(binding.chipProfileNetwork, tone)
                    binding.viewProfileNetworkDot.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(tone)
                    }
                    binding.chipProfileNetwork.setOnClickListener {
                        startActivity(android.content.Intent(requireContext(), com.jarvis.assistant.ui.udn.UdnActivity::class.java))
                    }
                }
            }
        }
    }

    private fun showProfilePhoto() {
        val avatar = binding.ivProfileAvatar
        avatar.imageTintList = null
        avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        avatar.setPadding(0, 0, 0, 0)

        // A photo explicitly selected in Settings remains the user's override.
        val savedUri = preferences.profilePhotoUri
        if (savedUri.isNotBlank()) {
            profileImageJob?.cancel()
            loadedRemoteAvatarUrl = null
            try {
                avatar.setImageURI(Uri.parse(savedUri))
            } catch (_: Exception) {
                preferences.profilePhotoUri = ""
                showProfilePhoto()
            }
            return
        }

        val accountUrl = preferences.accountAvatarUrl
        if (accountUrl.startsWith("https://", ignoreCase = true)) {
            if (loadedRemoteAvatarUrl == accountUrl && avatar.drawable != null) return
            profileImageJob?.cancel()
            avatar.setImageResource(R.drawable.jarvis_default_user)
            profileImageJob = lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val request = Request.Builder().url(accountUrl).header("Accept", "image/*").build()
                        profileImageClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            val body = response.body ?: return@use null
                            if (body.contentLength() > 4L * 1024L * 1024L) return@use null
                            val bytes = body.bytes()
                            if (bytes.size > 4 * 1024 * 1024) return@use null
                            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }
                    }.getOrNull()
                }
                if (_binding != null && preferences.profilePhotoUri.isBlank() &&
                    preferences.accountAvatarUrl == accountUrl
                ) {
                    if (bitmap != null) {
                        binding.ivProfileAvatar.setImageBitmap(bitmap)
                        loadedRemoteAvatarUrl = accountUrl
                    } else {
                        binding.ivProfileAvatar.setImageResource(R.drawable.jarvis_default_user)
                        loadedRemoteAvatarUrl = null
                    }
                }
            }
            return
        }

        profileImageJob?.cancel()
        loadedRemoteAvatarUrl = null
        avatar.setImageResource(R.drawable.jarvis_default_user)
    }

}
