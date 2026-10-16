package com.termex.replay15.account

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.recly.editor.R
import com.termex.replay15.editor.ui.EditorLibraryActivity
import com.termex.replay15.editor.ui.EditorStyle

import com.termex.replay15.editor.ui.dp
import com.termex.replay15.editor.ui.installEditorMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tela de conta do Recly. Na primeira abertura a pessoa cria a conta; depois, entra. Também cobre
 * recuperar senha e a espera pela confirmação de e-mail, sem trocar de tela.
 */
class AuthActivity : Activity() {
    /**
     * A tela de conta continua escura (ainda não tem versão clara). Fixar aqui evita que ela pegue
     * o tema claro do sistema pelos recursos de values-notnight/.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.termex.replay15.ui.BlitzAppearance.wrap(newBase, alwaysDark = true))
    }


    companion object {
        const val EXTRA_NEXT = "recly_auth_next"
        const val EXTRA_MODE = "recly_auth_mode"
        const val EXTRA_EXPIRED = "recly_auth_expired"
        const val MODE_SIGN_UP = "sign_up"
        const val MODE_SIGN_IN = "sign_in"
        private const val MODE_RESET = "reset"
        private const val MODE_CONFIRM = "confirm"
        private const val STATE_MODE = "mode"
        private const val STATE_PENDING_EMAIL = "pending_email"
        private const val PREFS = "recly-account-ui"
        private const val KEY_HAD_ACCOUNT = "had_account"
        private const val RESEND_COOLDOWN_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mode = MODE_SIGN_UP
    private var pendingEmail = ""
    private var busy = false
    private var passwordVisible = false
    private var resendTimer: CountDownTimer? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var banner: TextView
    private lateinit var form: View
    private lateinit var btnGoogle: View
    private lateinit var labelGoogle: TextView
    private lateinit var progressGoogle: ProgressBar
    private lateinit var dividerRow: View
    private lateinit var nameGroup: View
    private lateinit var passwordGroup: View
    private lateinit var inputName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPassword: EditText
    private lateinit var errorName: TextView
    private lateinit var errorEmail: TextView
    private lateinit var errorPassword: TextView
    private lateinit var hintPassword: TextView
    private lateinit var linkForgot: TextView
    private lateinit var btnPrimary: View
    private lateinit var labelPrimary: TextView
    private lateinit var progressPrimary: ProgressBar
    private lateinit var btnSecondary: TextView
    private lateinit var switchRow: View
    private lateinit var switchPrompt: TextView
    private lateinit var switchLink: TextView
    private lateinit var btnTogglePassword: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Quem já tem sessão nunca vê esta tela (ex.: voltou pelo histórico de apps).
        // Exceção: o visitante que abriu a tela de propósito para entrar ou criar conta.
        val guestWantsAccount = AccountManager.isGuest(this) && intent.hasExtra(EXTRA_MODE)
        if (AccountManager.isSignedIn(this) && !intent.getBooleanExtra(EXTRA_EXPIRED, false) && !guestWantsAccount) { goNext(); return }
        setContentView(R.layout.activity_auth)
        bindViews()
        applyInsets()
        applyType()

        val hadAccount = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_HAD_ACCOUNT, false)
        mode = savedInstanceState?.getString(STATE_MODE)
            ?: intent.getStringExtra(EXTRA_MODE)
            ?: if (hadAccount) MODE_SIGN_IN else MODE_SIGN_UP
        pendingEmail = savedInstanceState?.getString(STATE_PENDING_EMAIL).orEmpty()

        btnPrimary.setOnClickListener { submit() }
        btnGoogle.setOnClickListener { signInWithGoogle() }
        btnTogglePassword.setOnClickListener { togglePassword() }
        linkForgot.setOnClickListener { setMode(MODE_RESET) }
        switchLink.setOnClickListener { onSwitch() }
        btnSecondary.setOnClickListener { onSecondary() }
        inputEmail.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE || (mode == MODE_RESET && action == EditorInfo.IME_ACTION_NEXT)) { submit(); true } else false
        }
        inputPassword.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
        // Erro some assim que a pessoa corrige o campo.
        listOf(inputName to errorName, inputEmail to errorEmail, inputPassword to errorPassword).forEach { (input, error) ->
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { clearFieldError(input, error) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        // "Entrar sem conta": modo visitante, em qualquer build. Legendas pelo servidor continuam
        // pedindo conta (ver AccountManager.isGuest).
        findViewById<TextView>(R.id.btnDevSkip).apply {
            visibility = View.VISIBLE
            setOnClickListener { AccountManager.continueAsGuest(this@AuthActivity); goNext() }
        }
        if (!AccountManager.isConfigured) {
            showBanner("As contas ainda não estão configuradas neste build. Defina SUPABASE_URL e SUPABASE_ANON_KEY (veja docs/accounts.md).", error = true)
        } else if (intent.getBooleanExtra(EXTRA_EXPIRED, false)) {
            showBanner("Sua sessão expirou. Entre de novo para continuar.", error = false)
        }
        setMode(mode, keepBanner = true)
        installEditorMotion(findViewById(R.id.authContent))
        // Android 13+: com voltar preditivo o onBackPressed não é chamado; o mesmo comportamento vem daqui.
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { handleBack() }
    }

    private fun handleBack() {
        when (mode) {
            MODE_RESET, MODE_CONFIRM -> setMode(MODE_SIGN_IN)
            else -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode)
        outState.putString(STATE_PENDING_EMAIL, pendingEmail)
    }

    @Deprecated("Legacy Android back")
    override fun onBackPressed() { handleBack() }

    override fun onDestroy() {
        resendTimer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ── Modos ───────────────────────────────────────────────────────────────────────────────

    private fun setMode(next: String, keepBanner: Boolean = false) {
        mode = next
        if (!keepBanner) hideBanner()
        listOf(inputName to errorName, inputEmail to errorEmail, inputPassword to errorPassword)
            .forEach { (input, error) -> clearFieldError(input, error) }
        val google = AccountManager.isGoogleAvailable
        form.visibility = if (mode == MODE_CONFIRM) View.GONE else View.VISIBLE
        btnGoogle.visibility = if (google && (mode == MODE_SIGN_UP || mode == MODE_SIGN_IN)) View.VISIBLE else View.GONE
        dividerRow.visibility = btnGoogle.visibility
        nameGroup.visibility = if (mode == MODE_SIGN_UP) View.VISIBLE else View.GONE
        passwordGroup.visibility = if (mode == MODE_SIGN_UP || mode == MODE_SIGN_IN) View.VISIBLE else View.GONE
        hintPassword.visibility = if (mode == MODE_SIGN_UP) View.VISIBLE else View.GONE
        linkForgot.visibility = if (mode == MODE_SIGN_IN) View.VISIBLE else View.GONE
        btnSecondary.visibility = if (mode == MODE_CONFIRM) View.VISIBLE else View.GONE
        inputPassword.imeOptions = EditorInfo.IME_ACTION_DONE
        inputEmail.imeOptions = if (mode == MODE_RESET) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        // "newPassword" faz o gerenciador de senhas sugerir uma senha forte no cadastro.
        inputPassword.setAutofillHints(if (mode == MODE_SIGN_UP) "newPassword" else View.AUTOFILL_HINT_PASSWORD)
        when (mode) {
            MODE_SIGN_UP -> {
                title.text = "Crie sua conta"
                subtitle.text = "Leva menos de um minuto. Seus vídeos continuam no seu aparelho."
                labelPrimary.text = "Criar conta"
                switchPrompt.text = "Já tem conta?"; switchLink.text = "Entrar"
                inputPassword.hint = "Crie uma senha"
            }
            MODE_SIGN_IN -> {
                title.text = "Que bom te ver"
                subtitle.text = "Entre para continuar editando."
                labelPrimary.text = "Entrar"
                switchPrompt.text = "Novo por aqui?"; switchLink.text = "Criar conta"
                inputPassword.hint = "Sua senha"
            }
            MODE_RESET -> {
                title.text = "Recuperar senha"
                subtitle.text = "Mandamos um link para você criar uma senha nova."
                labelPrimary.text = "Enviar link"
                switchPrompt.text = "Lembrou?"; switchLink.text = "Entrar"
            }
            MODE_CONFIRM -> {
                title.text = "Confirme seu e-mail"
                subtitle.text = "Enviamos um link para $pendingEmail. Abra o e-mail, toque no link e volte aqui."
                labelPrimary.text = "Já confirmei, entrar"
                switchPrompt.text = "E-mail errado?"; switchLink.text = "Voltar"
                updateResendLabel(0)
            }
        }
        applyEnabled()
    }

    private fun onSwitch() {
        if (busy) return
        setMode(when (mode) {
            MODE_SIGN_IN -> MODE_SIGN_UP
            MODE_CONFIRM -> MODE_SIGN_UP
            else -> MODE_SIGN_IN
        })
    }

    private fun onSecondary() {
        if (busy || mode != MODE_CONFIRM || resendTimer != null) return
        setBusy(true)
        AccountManager.resendConfirmation(pendingEmail) { result ->
            setBusy(false)
            result.onSuccess {
                showBanner("E-mail reenviado para $pendingEmail. Confira também o spam.", error = false)
                startResendCooldown()
            }.onFailure { showFailure(it) }
        }
    }

    // ── Envio ───────────────────────────────────────────────────────────────────────────────

    private fun submit() {
        if (busy || !AccountManager.isConfigured) return
        hideKeyboard()
        hideBanner()
        when (mode) {
            MODE_SIGN_UP -> {
                if (!validate(name = true, password = true, newPassword = true)) return
                val email = inputEmail.text.toString()
                setBusy(true)
                AccountManager.signUp(this, inputName.text.toString(), email, inputPassword.text.toString()) { result ->
                    setBusy(false)
                    result.onSuccess { outcome ->
                        when (outcome) {
                            is SignUpResult.SignedIn -> onSignedIn()
                            is SignUpResult.ConfirmEmail -> {
                                pendingEmail = AuthValidation.normalizeEmail(email)
                                setMode(MODE_CONFIRM)
                                startResendCooldown()
                            }
                        }
                    }.onFailure { error ->
                        if ((error as? AuthFailure)?.kind == AuthFailureKind.ALREADY_REGISTERED) {
                            setMode(MODE_SIGN_IN)
                            showBanner(error.message.orEmpty(), error = false)
                            inputPassword.requestFocus()
                        } else showFailure(error)
                    }
                }
            }
            MODE_SIGN_IN, MODE_CONFIRM -> {
                if (mode == MODE_SIGN_IN && !validate(name = false, password = true, newPassword = false)) return
                val email = if (mode == MODE_CONFIRM) pendingEmail else inputEmail.text.toString()
                val password = inputPassword.text.toString()
                if (mode == MODE_CONFIRM && password.isEmpty()) { setMode(MODE_SIGN_IN); inputEmail.setText(email); return }
                setBusy(true)
                AccountManager.signIn(this, email, password) { result ->
                    setBusy(false)
                    result.onSuccess { onSignedIn() }.onFailure { error ->
                        if ((error as? AuthFailure)?.kind == AuthFailureKind.EMAIL_NOT_CONFIRMED) {
                            pendingEmail = AuthValidation.normalizeEmail(email)
                            if (mode != MODE_CONFIRM) setMode(MODE_CONFIRM)
                            showBanner("Ainda não recebemos a confirmação. Toque no link do e-mail e tente de novo.", error = true)
                        } else showFailure(error)
                    }
                }
            }
            MODE_RESET -> {
                if (!validate(name = false, password = false, newPassword = false)) return
                val email = AuthValidation.normalizeEmail(inputEmail.text.toString())
                setBusy(true)
                AccountManager.sendPasswordReset(email) { result ->
                    setBusy(false)
                    // Mesma resposta exista a conta ou não: não revela quais e-mails têm cadastro.
                    result.onSuccess {
                        setMode(MODE_SIGN_IN)
                        showBanner("Se existir uma conta com $email, o link para criar uma senha nova já está a caminho.", error = false)
                    }.onFailure { showFailure(it) }
                }
            }
        }
    }

    private fun signInWithGoogle() {
        if (busy || !AccountManager.isGoogleAvailable) return
        hideBanner()
        setBusy(true, google = true)
        scope.launch {
            val token = runCatching { GoogleSignIn.requestToken(this@AuthActivity, AccountManager.googleClientId) }
            token.onFailure { error ->
                setBusy(false)
                if ((error as? AuthFailure)?.kind != AuthFailureKind.CANCELLED) showFailure(error)
                return@launch
            }
            val (idToken, nonce) = token.getOrThrow()
            AccountManager.signInWithGoogle(this@AuthActivity, idToken, nonce) { result ->
                setBusy(false)
                result.onSuccess { onSignedIn() }.onFailure { showFailure(it) }
            }
        }
    }

    private fun onSignedIn() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HAD_ACCOUNT, true).apply()
        goNext()
    }

    private fun goNext() {
        @Suppress("DEPRECATION")
        val next = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_NEXT, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_NEXT) as? Intent
        val target = next?.takeIf { it.component?.packageName == packageName }
            ?: Intent(this, EditorLibraryActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(target)
        finish()
    }

    // ── Validação e estado ──────────────────────────────────────────────────────────────────

    private fun validate(name: Boolean, password: Boolean, newPassword: Boolean): Boolean {
        val problems = buildList {
            if (name) AuthValidation.name(inputName.text.toString())?.let { add(Triple(inputName, errorName, it)) }
            AuthValidation.email(inputEmail.text.toString())?.let { add(Triple(inputEmail, errorEmail, it)) }
            if (password) AuthValidation.password(inputPassword.text.toString(), newPassword)?.let { add(Triple(inputPassword, errorPassword, it)) }
        }
        problems.forEach { (input, error, message) ->
            input.isActivated = true
            error.text = message
            error.visibility = View.VISIBLE
        }
        problems.firstOrNull()?.first?.requestFocus()
        return problems.isEmpty()
    }

    private fun clearFieldError(input: EditText, error: TextView) {
        input.isActivated = false
        error.visibility = View.GONE
    }

    private fun setBusy(value: Boolean, google: Boolean = false) {
        busy = value
        labelPrimary.visibility = if (value && !google) View.INVISIBLE else View.VISIBLE
        progressPrimary.visibility = if (value && !google) View.VISIBLE else View.GONE
        labelGoogle.visibility = if (value && google) View.INVISIBLE else View.VISIBLE
        progressGoogle.visibility = if (value && google) View.VISIBLE else View.GONE
        listOf(inputName, inputEmail, inputPassword).forEach { it.isEnabled = !value }
        applyEnabled()
    }

    private fun applyEnabled() {
        btnPrimary.isEnabled = !busy && AccountManager.isConfigured
        btnGoogle.isEnabled = !busy
        btnGoogle.alpha = if (busy) 0.6f else 1f
        switchLink.isEnabled = !busy
    }

    private fun showFailure(error: Throwable) {
        val message = (error as? AuthFailure)?.message ?: "Algo deu errado. Tente de novo."
        showBanner(message, error = true)
    }

    private fun showBanner(message: String, error: Boolean) {
        banner.text = message
        // Tela de conta é sempre escura: cores fixas do tema escuro, não as do tema escolhido.
        banner.setTextColor(if (error) 0xFFFF5A6E.toInt() else 0xFFF5F5F7.toInt())
        banner.background = GradientDrawable().apply {
            setColor(0xFF111113.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1.5f), if (error) EditorStyle.ACTION else 0xFF2A2A2E.toInt())
        }
        banner.visibility = View.VISIBLE
        banner.announceForAccessibility(message)
    }

    private fun hideBanner() { banner.visibility = View.GONE }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        val cursor = inputPassword.selectionEnd
        inputPassword.inputType = InputType.TYPE_CLASS_TEXT or
            if (passwordVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        inputPassword.typeface = ReclyType.body(this, 500)
        inputPassword.setSelection(cursor.coerceIn(0, inputPassword.length()))
        btnTogglePassword.setImageResource(if (passwordVisible) R.drawable.ic_rc_eye_off else R.drawable.ic_rc_eye)
        btnTogglePassword.contentDescription = if (passwordVisible) "Esconder senha" else "Mostrar senha"
    }

    private fun startResendCooldown() {
        resendTimer?.cancel()
        resendTimer = object : CountDownTimer(RESEND_COOLDOWN_MS, 1_000L) {
            override fun onTick(remaining: Long) = updateResendLabel(((remaining + 999) / 1000).toInt())
            override fun onFinish() { resendTimer = null; updateResendLabel(0) }
        }.start()
    }

    private fun updateResendLabel(seconds: Int) {
        btnSecondary.text = if (seconds > 0) "Reenviar e-mail em ${seconds}s" else "Reenviar e-mail de confirmação"
        btnSecondary.alpha = if (seconds > 0) 0.5f else 1f
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ── Montagem ────────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        title = findViewById(R.id.authTitle); subtitle = findViewById(R.id.authSubtitle); banner = findViewById(R.id.authBanner)
        form = findViewById(R.id.authForm); btnGoogle = findViewById(R.id.btnGoogle); labelGoogle = findViewById(R.id.labelGoogle)
        progressGoogle = findViewById(R.id.progressGoogle); dividerRow = findViewById(R.id.dividerRow)
        nameGroup = findViewById(R.id.fieldNameGroup); passwordGroup = findViewById(R.id.fieldPasswordGroup)
        inputName = findViewById(R.id.inputName); inputEmail = findViewById(R.id.inputEmail); inputPassword = findViewById(R.id.inputPassword)
        errorName = findViewById(R.id.errorName); errorEmail = findViewById(R.id.errorEmail); errorPassword = findViewById(R.id.errorPassword)
        hintPassword = findViewById(R.id.hintPassword); linkForgot = findViewById(R.id.linkForgot)
        btnPrimary = findViewById(R.id.btnPrimary); labelPrimary = findViewById(R.id.labelPrimary); progressPrimary = findViewById(R.id.progressPrimary)
        btnSecondary = findViewById(R.id.btnSecondary); switchRow = findViewById(R.id.switchRow)
        switchPrompt = findViewById(R.id.switchPrompt); switchLink = findViewById(R.id.switchLink)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
    }

    private fun applyType() {
        val display = ReclyType.display(this)
        title.typeface = display
        findViewById<TextView>(R.id.authBrand).typeface = display
        labelPrimary.typeface = ReclyType.body(this, 700)
        labelGoogle.typeface = ReclyType.body(this, 600)
        switchLink.typeface = ReclyType.body(this, 700)
        listOf(subtitle, banner, switchPrompt, btnSecondary, linkForgot, hintPassword, errorName, errorEmail, errorPassword,
            findViewById<TextView>(R.id.labelName), findViewById<TextView>(R.id.labelEmail), findViewById<TextView>(R.id.labelPassword),
            findViewById<TextView>(R.id.authLegal)).forEach { it.typeface = ReclyType.body(this, 500) }
        listOf(inputName, inputEmail, inputPassword).forEach { it.typeface = ReclyType.body(this, 500) }
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        val root = findViewById<View>(R.id.authRoot)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}
