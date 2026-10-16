package com.termex.replay15.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowInsetsController

/**
 * Aparência escolhida pela pessoa em Config.: editor escuro ou claro e botões das ferramentas
 * coloridos (uma cor por grupo) ou pretos (neutros). Vale para o app inteiro e fica salva.
 *
 * As telas leem as cores na hora de montar as views, então uma troca só aparece depois que a
 * tela é recriada: quem muda a opção chama [Activity.recreate], e as outras telas se recriam
 * sozinhas ao voltar para a frente ([needsRecreate]).
 */
object BlitzAppearance {
    enum class Theme { DARK, LIGHT }
    enum class Buttons { COLORED, BLACK }

    private const val PREFS = "blitz_aparencia"
    private const val KEY_THEME = "tema"
    private const val KEY_BUTTONS = "botoes"

    @Volatile var theme: Theme = Theme.DARK
        private set
    @Volatile var buttons: Buttons = Buttons.COLORED
        private set
    @Volatile private var loaded = false

    /** Muda a cada troca; cada tela guarda o valor de quando foi montada. */
    @Volatile var version: Int = 0
        private set

    val isLight: Boolean get() = theme == Theme.LIGHT
    val coloredButtons: Boolean get() = buttons == Buttons.COLORED

    /** Lê a escolha salva (rápido: um arquivo de preferências pequeno). Seguro chamar várias vezes. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            theme = runCatching { Theme.valueOf(prefs.getString(KEY_THEME, null) ?: Theme.DARK.name) }.getOrDefault(Theme.DARK)
            buttons = runCatching { Buttons.valueOf(prefs.getString(KEY_BUTTONS, null) ?: Buttons.COLORED.name) }.getOrDefault(Buttons.COLORED)
            loaded = true
        }
    }

    fun setTheme(context: Context, value: Theme) {
        load(context)
        if (value == theme) return
        theme = value
        save(context)
    }

    fun setButtons(context: Context, value: Buttons) {
        load(context)
        if (value == buttons) return
        buttons = value
        save(context)
    }

    /** Troca só em memória, sem salvar (testes JVM e prévias que não têm Context). */
    fun applyInMemory(theme: Theme, buttons: Buttons) {
        this.theme = theme
        this.buttons = buttons
        loaded = true
        version++
    }

    private fun save(context: Context) {
        version++
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, theme.name)
            .putString(KEY_BUTTONS, buttons.name)
            .apply()
    }

    /**
     * Contexto com o modo noturno da escolha (não o do sistema), para os recursos em
     * values-notnight/ (tema claro) e values/ (escuro) baterem com a opção. Usar em
     * Activity.attachBaseContext. [alwaysDark] mantém a tela escura qualquer que seja a escolha
     * (tela de conta, que ainda não tem versão clara), sem cair no modo do sistema.
     */
    fun wrap(base: Context, alwaysDark: Boolean = false): Context {
        load(base)
        // Configuração "vazia" com só o modo noturno: vira uma sobreposição apenas desse campo.
        // Copiar a configuração inteira congelaria orientação/tamanho (o editor trata rotação
        // sem recriar a tela) no valor de quando a tela abriu.
        val config = Configuration()
        config.uiMode = if (isLight && !alwaysDark) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        return base.createConfigurationContext(config)
    }

    /** Fundo da janela e ícones das barras do sistema (escuros no tema claro, claros no escuro). */
    fun applyWindow(activity: Activity, background: Int) {
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(background))
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = background
        }
        val lightBars = isLight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (lightBars) {
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else 0
        }
    }

    /** Tema dos AlertDialog do sistema, claro ou escuro conforme a escolha. */
    val dialogTheme: Int get() = if (isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** true quando a aparência mudou desde [builtWith] (versão guardada ao montar a tela). */
    fun needsRecreate(builtWith: Int): Boolean = builtWith != version
}

/**
 * Cores das telas fora do editor (Início, Config., seletor de mídia), nos dois temas. As do
 * editor ficam em EditorStyle; as duas seguem a mesma escolha.
 */
object BlitzColors {
    val bg: Int get() = if (BlitzAppearance.isLight) 0xFFF4F4F6.toInt() else 0xFF0F0F11.toInt()
    val surface: Int get() = if (BlitzAppearance.isLight) 0xFFFFFFFF.toInt() else 0xFF17171A.toInt()
    val surfaceRaised: Int get() = if (BlitzAppearance.isLight) 0xFFECECEF.toInt() else 0xFF1D1D21.toInt()
    val line: Int get() = if (BlitzAppearance.isLight) 0xFFDDDDE2.toInt() else 0xFF26262B.toInt()
    val text: Int get() = if (BlitzAppearance.isLight) 0xFF141416.toInt() else 0xFFF2F0EA.toInt()
    val muted: Int get() = if (BlitzAppearance.isLight) 0xFF6B6B71.toInt() else 0xFFA09C93.toInt()
    val faint: Int get() = if (BlitzAppearance.isLight) 0xFF8E8E94.toInt() else 0xFF6E6A62.toInt()
    val danger: Int get() = if (BlitzAppearance.isLight) 0xFFD92D45.toInt() else 0xFFF0566A.toInt()
    val ripple: Int get() = if (BlitzAppearance.isLight) 0x1F000000 else 0x1FFFFFFF
    /** Cartões sem miniatura: tons levemente diferentes para a grade não ficar chapada. */
    val tileTones: IntArray get() = if (BlitzAppearance.isLight) {
        intArrayOf(0xFFE6E6EA.toInt(), 0xFFEAE6E7.toInt(), 0xFFE6EAE8.toInt(), 0xFFE8E6EB.toInt())
    } else {
        intArrayOf(0xFF1D1D21.toInt(), 0xFF211D1E.toInt(), 0xFF1D201F.toInt(), 0xFF1F1D22.toInt())
    }
}
