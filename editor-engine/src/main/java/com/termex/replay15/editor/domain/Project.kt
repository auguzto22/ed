package com.termex.replay15.editor.domain

import com.termex.replay15.editor.assets.TransitionInstance
import com.termex.replay15.editor.audio.AudioEnhance
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToLong

fun newId(): String = UUID.randomUUID().toString()
const val SECOND = 1_000_000L
const val MIN_CLIP = 100_000L

data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init { require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right - left >= .05f && bottom - top >= .05f) }
}

enum class VideoFilter(
    val label: String,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val hue: Float = 0f,
    val lightness: Float = 0f,
    val red: Float = 1f,
    val green: Float = 1f,
    val blue: Float = 1f,
    /** Grupo da galeria para os filtros da biblioteca estendida; vazio nos filtros originais. */
    val group: String = "",
) {
    ORIGINAL("Original"),
    MONO("Preto e branco"),
    NEGATIVE("Negativo"),
    CINEMA("Cinema", -.03f, .12f, 10f, 0f, -4f, 1.06f, .99f, .92f),
    VIBRANT("Vibrante", 0f, .10f, 35f),
    WARM("Quente", 0f, 0f, 5f, 0f, 3f, 1.12f, 1.03f, .86f),
    COOL("Frio", 0f, 0f, 3f, 0f, 2f, .88f, 1.02f, 1.14f),
    VINTAGE("Vintage", .03f, -.12f, -30f, -5f, 8f, 1.08f, 1f, .9f),
    GOLDEN("Golden hour", .06f, .08f, 18f, -5f, 8f, 1.15f, 1.04f, .8f),
    ROSE("Rose", .04f, -.02f, 8f, -8f, 7f, 1.14f, .94f, 1.03f),
    TEAL_ORANGE("Teal & Orange", -.03f, .16f, 25f, -6f, -2f, 1.1f, .98f, 1.08f),
    NIGHT("Noite", -.12f, .2f, -8f, -5f, -12f, .82f, .93f, 1.15f),
    FADE("Fade", .09f, -.22f, -20f, 0f, 10f),
    MATTE("Matte", .05f, -.18f, -5f, 0f, 4f),
    RETRO("Retro", .06f, .03f, -18f, 10f, 8f, 1.1f, .96f, .82f),
    DREAM("Sonho", .12f, -.12f, -12f, -8f, 12f, 1.06f, .97f, 1.08f),
    TROPICAL("Tropical", .05f, .12f, 40f, -3f, 8f, .98f, 1.08f, 1.03f),
    URBAN("Urbano", -.06f, .2f, -25f, 0f, -6f, .95f, .98f, 1.05f),
    FOOD("Comida", .08f, .16f, 32f, -3f, 7f, 1.12f, 1.05f, .92f),
    PORTRAIT("Retrato", .06f, .06f, -5f, -5f, 8f, 1.08f, .99f, .98f),
    AUTUMN("Outono", .04f, .12f, 22f, 12f, 4f, 1.16f, 1.02f, .78f),
    ICE("Gelo", .06f, .08f, -4f, 5f, 8f, .86f, 1.02f, 1.18f),
    NEON("Neon", -.05f, .25f, 48f, 0f, -4f, .98f, 1.05f, 1.1f),
    DOCUMENTARY("Documentario", -.04f, .22f, -60f, 0f, -6f),
    SUNSET("Por do sol", .05f, .1f, 28f, -12f, 4f, 1.18f, .96f, .78f),
    LAVENDER("Lavanda", .08f, -.05f, 5f, 8f, 10f, 1.04f, .93f, 1.14f),
    EMERALD("Esmeralda", -.02f, .12f, 24f, -10f, 0f, .9f, 1.12f, 1.02f),
    NOIR("Noir", -.08f, .28f, -100f, 0f, -10f),
    SOFT("Pele suave", .1f, -.08f, -10f, -4f, 10f, 1.08f, 1f, .98f),
    DRAMA("Drama", -.08f, .34f, -18f, 0f, -8f),
    LOW_LIGHT("Baixa luz", .18f, .08f, 8f, 0f, 8f, 1.03f, 1.03f, 1.08f),
    CLEAN("Clean", .07f, .05f, 8f, 0f, 6f),
    // BEGIN GENERATED FILTERS (tools/library/gen_filters.py). Novos filtros entram sempre no fim: o indice fica salvo nos projetos.
    LIB_CORES_VERMELHO_SUAVE("Vermelho suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.250f, 0.875f, 0.875f, "Cores"),
    LIB_CORES_VERMELHO_MEDIO("Vermelho médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.400f, 0.775f, 0.775f, "Cores"),
    LIB_CORES_VERMELHO_FORTE("Vermelho forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.650f, 0.650f, "Cores"),
    LIB_CORES_VERMELHO_INTENSO("Vermelho intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.600f, 0.600f, "Cores"),
    LIB_CORES_CORAL_SUAVE("Coral suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.225f, 0.925f, 0.850f, "Cores"),
    LIB_CORES_CORAL_MEDIO("Coral médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.400f, 0.865f, 0.730f, "Cores"),
    LIB_CORES_CORAL_FORTE("Coral forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.790f, 0.600f, "Cores"),
    LIB_CORES_CORAL_INTENSO("Coral intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.715f, 0.600f, "Cores"),
    LIB_CORES_LARANJA_SUAVE("Laranja suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.192f, 0.992f, 0.817f, "Cores"),
    LIB_CORES_LARANJA_MEDIO("Laranja médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.345f, 0.985f, 0.670f, "Cores"),
    LIB_CORES_LARANJA_FORTE("Laranja forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.977f, 0.600f, "Cores"),
    LIB_CORES_LARANJA_INTENSO("Laranja intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.968f, 0.600f, "Cores"),
    LIB_CORES_AMBAR_SUAVE("Âmbar suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.167f, 1.042f, 0.792f, "Cores"),
    LIB_CORES_AMBAR_MEDIO("Âmbar médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.300f, 1.075f, 0.625f, "Cores"),
    LIB_CORES_AMBAR_FORTE("Âmbar forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 1.117f, 0.600f, "Cores"),
    LIB_CORES_AMBAR_INTENSO("Âmbar intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 1.158f, 0.600f, "Cores"),
    LIB_CORES_AMARELO_SUAVE("Amarelo suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.135f, 1.104f, 0.760f, "Cores"),
    LIB_CORES_AMARELO_MEDIO("Amarelo médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.244f, 1.188f, 0.600f, "Cores"),
    LIB_CORES_AMARELO_FORTE("Amarelo forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.379f, 1.292f, 0.600f, "Cores"),
    LIB_CORES_AMARELO_INTENSO("Amarelo intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 1.396f, 0.600f, "Cores"),
    LIB_CORES_LIMA_SUAVE("Lima suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.042f, 1.167f, 0.792f, "Cores"),
    LIB_CORES_LIMA_MEDIO("Lima médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.075f, 1.300f, 0.625f, "Cores"),
    LIB_CORES_LIMA_FORTE("Lima forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.117f, 1.400f, 0.600f, "Cores"),
    LIB_CORES_LIMA_INTENSO("Lima intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.158f, 1.400f, 0.600f, "Cores"),
    LIB_CORES_VERDE_SUAVE("Verde suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.875f, 1.250f, 0.875f, "Cores"),
    LIB_CORES_VERDE_MEDIO("Verde médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.775f, 1.400f, 0.775f, "Cores"),
    LIB_CORES_VERDE_FORTE("Verde forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.650f, 1.400f, 0.650f, "Cores"),
    LIB_CORES_VERDE_INTENSO("Verde intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 1.400f, 0.600f, "Cores"),
    LIB_CORES_MENTA_SUAVE("Menta suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.812f, 1.188f, 1.000f, "Cores"),
    LIB_CORES_MENTA_MEDIO("Menta médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.662f, 1.337f, 1.000f, "Cores"),
    LIB_CORES_MENTA_FORTE("Menta forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.600f, 1.400f, 1.000f, "Cores"),
    LIB_CORES_MENTA_INTENSO("Menta intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 1.400f, 1.000f, "Cores"),
    LIB_CORES_TURQUESA_SUAVE("Turquesa suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.760f, 1.135f, 1.104f, "Cores"),
    LIB_CORES_TURQUESA_MEDIO("Turquesa médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.600f, 1.244f, 1.188f, "Cores"),
    LIB_CORES_TURQUESA_FORTE("Turquesa forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.600f, 1.379f, 1.292f, "Cores"),
    LIB_CORES_TURQUESA_INTENSO("Turquesa intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 1.400f, 1.396f, "Cores"),
    LIB_CORES_CIANO_SUAVE("Ciano suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.771f, 1.083f, 1.146f, "Cores"),
    LIB_CORES_CIANO_MEDIO("Ciano médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.600f, 1.150f, 1.263f, "Cores"),
    LIB_CORES_CIANO_FORTE("Ciano forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.600f, 1.233f, 1.400f, "Cores"),
    LIB_CORES_CIANO_INTENSO("Ciano intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 1.317f, 1.400f, "Cores"),
    LIB_CORES_AZUL_CLARO_SUAVE("Azul claro suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.802f, 1.021f, 1.177f, "Cores"),
    LIB_CORES_AZUL_CLARO_MEDIO("Azul claro médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.644f, 1.038f, 1.319f, "Cores"),
    LIB_CORES_AZUL_CLARO_FORTE("Azul claro forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.600f, 1.058f, 1.400f, "Cores"),
    LIB_CORES_AZUL_CLARO_INTENSO("Azul claro intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 1.079f, 1.400f, "Cores"),
    LIB_CORES_AZUL_SUAVE("Azul suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.844f, 0.938f, 1.219f, "Cores"),
    LIB_CORES_AZUL_MEDIO("Azul médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.719f, 0.887f, 1.394f, "Cores"),
    LIB_CORES_AZUL_FORTE("Azul forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.600f, 0.825f, 1.400f, "Cores"),
    LIB_CORES_AZUL_INTENSO("Azul intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.600f, 0.762f, 1.400f, "Cores"),
    LIB_CORES_INDIGO_SUAVE("Índigo suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 0.917f, 0.854f, 1.229f, "Cores"),
    LIB_CORES_INDIGO_MEDIO("Índigo médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 0.850f, 0.738f, 1.400f, "Cores"),
    LIB_CORES_INDIGO_FORTE("Índigo forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 0.767f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_INDIGO_INTENSO("Índigo intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 0.683f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_VIOLETA_SUAVE("Violeta suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.000f, 0.812f, 1.188f, "Cores"),
    LIB_CORES_VIOLETA_MEDIO("Violeta médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.000f, 0.662f, 1.337f, "Cores"),
    LIB_CORES_VIOLETA_FORTE("Violeta forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.000f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_VIOLETA_INTENSO("Violeta intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.000f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_ROXO_SUAVE("Roxo suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.062f, 0.781f, 1.156f, "Cores"),
    LIB_CORES_ROXO_MEDIO("Roxo médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.113f, 0.606f, 1.281f, "Cores"),
    LIB_CORES_ROXO_FORTE("Roxo forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.175f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_ROXO_INTENSO("Roxo intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.237f, 0.600f, 1.400f, "Cores"),
    LIB_CORES_MAGENTA_SUAVE("Magenta suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.146f, 0.771f, 1.083f, "Cores"),
    LIB_CORES_MAGENTA_MEDIO("Magenta médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.263f, 0.600f, 1.150f, "Cores"),
    LIB_CORES_MAGENTA_FORTE("Magenta forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.600f, 1.233f, "Cores"),
    LIB_CORES_MAGENTA_INTENSO("Magenta intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.600f, 1.317f, "Cores"),
    LIB_CORES_ROSA_SUAVE("Rosa suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.198f, 0.823f, 0.979f, "Cores"),
    LIB_CORES_ROSA_MEDIO("Rosa médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.356f, 0.681f, 0.962f, "Cores"),
    LIB_CORES_ROSA_FORTE("Rosa forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.600f, 0.942f, "Cores"),
    LIB_CORES_ROSA_INTENSO("Rosa intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.600f, 0.921f, "Cores"),
    LIB_CORES_CARMIM_SUAVE("Carmim suave", 0.000f, 0.010f, 4.0f, 0.0f, 0.0f, 1.229f, 0.854f, 0.917f, "Cores"),
    LIB_CORES_CARMIM_MEDIO("Carmim médio", 0.000f, 0.018f, 10.0f, 0.0f, 0.0f, 1.400f, 0.737f, 0.850f, "Cores"),
    LIB_CORES_CARMIM_FORTE("Carmim forte", 0.000f, 0.028f, 18.0f, 0.0f, 0.0f, 1.400f, 0.600f, 0.767f, "Cores"),
    LIB_CORES_CARMIM_INTENSO("Carmim intenso", 0.000f, 0.038f, 26.0f, 0.0f, 0.0f, 1.400f, 0.600f, 0.683f, "Cores"),
    LIB_PEB_P_B_PRATA("P&B Prata", 0.000f, 0.000f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_GRAFITE("P&B Grafite", -0.040f, 0.200f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_ALTO_CONTRASTE("P&B Alto contraste", 0.000f, 0.450f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_SUAVE("P&B Suave", 0.050f, -0.120f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_NOIR_FOSCO("P&B Noir fosco", -0.080f, 0.350f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_PAPEL("P&B Papel", 0.100f, -0.200f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_NEWSPRINT("P&B Newsprint", 0.060f, 0.150f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_DRAMATICO("P&B Dramático", -0.100f, 0.500f, -100.0f, 0.0f, 0.0f, 1.000f, 1.000f, 1.000f, "P&B"),
    LIB_PEB_P_B_SEPIA("P&B sépia", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 1.342f, 1.067f, 0.600f, "P&B"),
    LIB_PEB_P_B_AZULADO("P&B azulado", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 0.646f, 0.958f, 1.396f, "P&B"),
    LIB_PEB_P_B_ESVERDEADO("P&B esverdeado", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 0.708f, 1.400f, 0.833f, "P&B"),
    LIB_PEB_P_B_ROSADO("P&B rosado", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 1.396f, 0.646f, 0.958f, "P&B"),
    LIB_PEB_P_B_DOURADO("P&B dourado", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 1.312f, 1.125f, 0.600f, "P&B"),
    LIB_PEB_P_B_VIOLETA("P&B violeta", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 1.000f, 0.625f, 1.375f, "P&B"),
    LIB_PEB_P_B_CIANO("P&B ciano", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 0.600f, 1.167f, 1.292f, "P&B"),
    LIB_PEB_P_B_VERMELHO("P&B vermelho", 0.000f, 0.120f, -100.0f, 0.0f, 0.0f, 1.400f, 0.792f, 0.729f, "P&B"),
    LIB_PEB_P_B_CINZA_FRIO("P&B Cinza frio", 0.000f, 0.100f, -100.0f, 0.0f, 0.0f, 0.858f, 0.983f, 1.158f, "P&B"),
    LIB_PEB_P_B_CINZA_QUENTE("P&B Cinza quente", 0.000f, 0.100f, -100.0f, 0.0f, 0.0f, 1.150f, 1.000f, 0.850f, "P&B"),
    LIB_PEB_P_B_PELICULA("P&B Película", 0.040f, -0.100f, -100.0f, 0.0f, 0.0f, 1.133f, 1.033f, 0.833f, "P&B"),
    LIB_PEB_P_B_TINTA("P&B Tinta", -0.060f, 0.400f, -100.0f, 0.0f, 0.0f, 0.883f, 0.933f, 1.183f, "P&B"),
    LIB_PEB_P_B_NEBLINA("P&B Neblina", 0.120f, -0.250f, -100.0f, 0.0f, 0.0f, 0.833f, 1.033f, 1.133f, "P&B"),
    LIB_PEB_P_B_CARVAO("P&B Carvão", -0.120f, 0.300f, -100.0f, 0.0f, 0.0f, 1.167f, 0.967f, 0.867f, "P&B"),
    LIB_PEB_P_B_GIZ("P&B Giz", 0.100f, 0.250f, -100.0f, 0.0f, 0.0f, 1.117f, 1.067f, 0.817f, "P&B"),
    LIB_PEB_P_B_CINEMA_MUDO("P&B Cinema mudo", 0.000f, 0.200f, -100.0f, 0.0f, 0.0f, 1.142f, 1.017f, 0.842f, "P&B"),
    LIB_CINEMA_TEAL_LARANJA_LEVE("Teal & laranja leve", 0.000f, 0.100f, 8.0f, 0.0f, -3.0f, 1.052f, 1.009f, 0.939f, "Cinema"),
    LIB_CINEMA_AMBAR_AZUL_LEVE("Âmbar & azul leve", 0.000f, 0.100f, 8.0f, 0.0f, -3.0f, 1.050f, 1.012f, 0.938f, "Cinema"),
    LIB_CINEMA_DOURADO_PETROLEO_LEVE("Dourado & petróleo leve", 0.000f, 0.100f, 8.0f, 0.0f, -3.0f, 1.033f, 1.047f, 0.920f, "Cinema"),
    LIB_CINEMA_ROSA_CIANO_LEVE("Rosa & ciano leve", 0.000f, 0.100f, 8.0f, 0.0f, -3.0f, 1.058f, 0.941f, 1.002f, "Cinema"),
    LIB_CINEMA_MAGENTA_VERDE_LEVE("Magenta & verde leve", 0.000f, 0.100f, 8.0f, 0.0f, -3.0f, 1.041f, 0.928f, 1.031f, "Cinema"),
    LIB_CINEMA_TEAL_LARANJA_MEDIO("Teal & laranja médio", 0.000f, 0.160f, 14.0f, 0.0f, -3.0f, 1.086f, 1.015f, 0.899f, "Cinema"),
    LIB_CINEMA_AMBAR_AZUL_MEDIO("Âmbar & azul médio", 0.000f, 0.160f, 14.0f, 0.0f, -3.0f, 1.083f, 1.021f, 0.896f, "Cinema"),
    LIB_CINEMA_DOURADO_PETROLEO_MEDIO("Dourado & petróleo médio", 0.000f, 0.160f, 14.0f, 0.0f, -3.0f, 1.055f, 1.078f, 0.867f, "Cinema"),
    LIB_CINEMA_ROSA_CIANO_MEDIO("Rosa & ciano médio", 0.000f, 0.160f, 14.0f, 0.0f, -3.0f, 1.096f, 0.901f, 1.003f, "Cinema"),
    LIB_CINEMA_MAGENTA_VERDE_MEDIO("Magenta & verde médio", 0.000f, 0.160f, 14.0f, 0.0f, -3.0f, 1.068f, 0.880f, 1.052f, "Cinema"),
    LIB_CINEMA_TEAL_LARANJA_FORTE("Teal & laranja forte", 0.000f, 0.220f, 20.0f, 0.0f, -3.0f, 1.121f, 1.020f, 0.859f, "Cinema"),
    LIB_CINEMA_AMBAR_AZUL_FORTE("Âmbar & azul forte", 0.000f, 0.220f, 20.0f, 0.0f, -3.0f, 1.117f, 1.029f, 0.854f, "Cinema"),
    LIB_CINEMA_DOURADO_PETROLEO_FORTE("Dourado & petróleo forte", 0.000f, 0.220f, 20.0f, 0.0f, -3.0f, 1.077f, 1.109f, 0.814f, "Cinema"),
    LIB_CINEMA_ROSA_CIANO_FORTE("Rosa & ciano forte", 0.000f, 0.220f, 20.0f, 0.0f, -3.0f, 1.135f, 0.861f, 1.004f, "Cinema"),
    LIB_CINEMA_MAGENTA_VERDE_FORTE("Magenta & verde forte", 0.000f, 0.220f, 20.0f, 0.0f, -3.0f, 1.095f, 0.832f, 1.073f, "Cinema"),
    LIB_CINEMA_TEAL_LARANJA_EXTREMO("Teal & laranja extremo", 0.000f, 0.280f, 26.0f, 0.0f, -3.0f, 1.156f, 1.026f, 0.818f, "Cinema"),
    LIB_CINEMA_AMBAR_AZUL_EXTREMO("Âmbar & azul extremo", 0.000f, 0.280f, 26.0f, 0.0f, -3.0f, 1.150f, 1.037f, 0.812f, "Cinema"),
    LIB_CINEMA_DOURADO_PETROLEO_EXTREMO("Dourado & petróleo extremo", 0.000f, 0.280f, 26.0f, 0.0f, -3.0f, 1.098f, 1.141f, 0.761f, "Cinema"),
    LIB_CINEMA_ROSA_CIANO_EXTREMO("Rosa & ciano extremo", 0.000f, 0.280f, 26.0f, 0.0f, -3.0f, 1.173f, 0.822f, 1.005f, "Cinema"),
    LIB_CINEMA_MAGENTA_VERDE_EXTREMO("Magenta & verde extremo", 0.000f, 0.280f, 26.0f, 0.0f, -3.0f, 1.122f, 0.784f, 1.094f, "Cinema"),
    LIB_CINEMA_BLOCKBUSTER("Blockbuster", -0.020f, 0.300f, 15.0f, 0.0f, 0.4f, 1.080f, 1.000f, 0.920f, "Cinema"),
    LIB_CINEMA_THRILLER("Thriller", -0.070f, 0.350f, -25.0f, 0.0f, 1.4f, 0.940f, 1.000f, 1.060f, "Cinema"),
    LIB_CINEMA_EPICO("Épico", -0.030f, 0.280f, 12.0f, 0.0f, 0.6f, 1.100f, 1.000f, 0.900f, "Cinema"),
    LIB_CINEMA_NOITE_AZUL("Noite azul", -0.120f, 0.250f, -10.0f, 0.0f, 2.4f, 0.850f, 0.950f, 1.180f, "Cinema"),
    LIB_CINEMA_SCIMENOSFI("Sci-fi", -0.040f, 0.300f, -5.0f, 0.0f, 0.8f, 0.920f, 1.030f, 1.120f, "Cinema"),
    LIB_CINEMA_GUERRA("Guerra", -0.060f, 0.380f, -45.0f, 0.0f, 1.2f, 1.000f, 0.980f, 0.920f, "Cinema"),
    LIB_CINEMA_WESTERN("Western", 0.030f, 0.220f, -8.0f, 0.0f, -0.6f, 1.180f, 1.000f, 0.780f, "Cinema"),
    LIB_CINEMA_NEO_NOIR("Neo noir", -0.100f, 0.450f, -60.0f, 0.0f, 2.0f, 0.950f, 0.980f, 1.080f, "Cinema"),
    LIB_CINEMA_DRAMA("Drama", -0.050f, 0.300f, -15.0f, 0.0f, 1.0f, 1.040f, 1.000f, 0.980f, "Cinema"),
    LIB_CINEMA_ROMANCE("Romance", 0.070f, -0.050f, 6.0f, 0.0f, -1.4f, 1.100f, 0.960f, 1.020f, "Cinema"),
    LIB_CINEMA_TERROR("Terror", -0.120f, 0.400f, -35.0f, 0.0f, 2.4f, 0.900f, 1.050f, 0.980f, "Cinema"),
    LIB_CINEMA_AVENTURA("Aventura", 0.020f, 0.260f, 22.0f, 0.0f, -0.4f, 1.080f, 1.040f, 0.880f, "Cinema"),
    LIB_CINEMA_FANTASIA("Fantasia", 0.050f, 0.120f, 20.0f, 0.0f, -1.0f, 1.020f, 0.980f, 1.120f, "Cinema"),
    LIB_CINEMA_DOCUMENTARIO("Documentário", -0.020f, 0.200f, -45.0f, 0.0f, 0.4f, 1.000f, 1.000f, 1.000f, "Cinema"),
    LIB_CINEMA_CINEMA_INDIE("Cinema indie", 0.040f, 0.100f, -10.0f, 0.0f, -0.8f, 1.060f, 1.020f, 0.940f, "Cinema"),
    LIB_CINEMA_ANAMORFICO("Anamórfico", -0.020f, 0.240f, 10.0f, 0.0f, 0.4f, 0.960f, 1.000f, 1.100f, "Cinema"),
    LIB_CINEMA_SUNSET_FILM("Sunset film", 0.040f, 0.200f, 18.0f, 0.0f, -0.8f, 1.150f, 0.980f, 0.840f, "Cinema"),
    LIB_CINEMA_INVERNO_NORDICO("Inverno nórdico", 0.040f, 0.160f, -30.0f, 0.0f, -0.8f, 0.900f, 1.000f, 1.140f, "Cinema"),
    LIB_CINEMA_VERAO_ITALIANO("Verão italiano", 0.060f, 0.140f, 24.0f, 0.0f, -1.2f, 1.120f, 1.040f, 0.900f, "Cinema"),
    LIB_CINEMA_FILME_EUROPEU("Filme europeu", 0.020f, 0.180f, -20.0f, 0.0f, -0.4f, 1.000f, 1.020f, 0.980f, "Cinema"),
    LIB_VINTAGE_ANOS_50_LEVE("Anos 50 leve", 0.030f, -0.096f, -21.0f, 0.0f, 3.0f, 1.140f, 1.035f, 0.825f, "Vintage"),
    LIB_VINTAGE_ANOS_50_MEDIO("Anos 50 médio", 0.050f, -0.160f, -35.0f, 0.0f, 5.0f, 1.233f, 1.058f, 0.708f, "Vintage"),
    LIB_VINTAGE_ANOS_50_FORTE("Anos 50 forte", 0.075f, -0.240f, -52.5f, 0.0f, 7.5f, 1.350f, 1.087f, 0.600f, "Vintage"),
    LIB_VINTAGE_ANOS_60_LEVE("Anos 60 leve", 0.036f, -0.060f, -9.0f, 0.0f, 3.0f, 1.170f, 1.020f, 0.810f, "Vintage"),
    LIB_VINTAGE_ANOS_60_MEDIO("Anos 60 médio", 0.060f, -0.100f, -15.0f, 0.0f, 5.0f, 1.283f, 1.033f, 0.683f, "Vintage"),
    LIB_VINTAGE_ANOS_60_FORTE("Anos 60 forte", 0.090f, -0.150f, -22.5f, 0.0f, 7.5f, 1.400f, 1.050f, 0.600f, "Vintage"),
    LIB_VINTAGE_ANOS_70_LEVE("Anos 70 leve", 0.030f, -0.036f, 6.0f, 0.0f, 3.0f, 1.248f, 1.000f, 0.752f, "Vintage"),
    LIB_VINTAGE_ANOS_70_MEDIO("Anos 70 médio", 0.050f, -0.060f, 10.0f, 0.0f, 5.0f, 1.400f, 1.000f, 0.600f, "Vintage"),
    LIB_VINTAGE_ANOS_70_FORTE("Anos 70 forte", 0.075f, -0.090f, 15.0f, 0.0f, 7.5f, 1.400f, 1.000f, 0.600f, "Vintage"),
    LIB_VINTAGE_ANOS_80_LEVE("Anos 80 leve", 0.024f, 0.030f, 15.0f, 0.0f, 3.0f, 1.140f, 0.825f, 1.035f, "Vintage"),
    LIB_VINTAGE_ANOS_80_MEDIO("Anos 80 médio", 0.040f, 0.050f, 25.0f, 0.0f, 5.0f, 1.233f, 0.708f, 1.058f, "Vintage"),
    LIB_VINTAGE_ANOS_80_FORTE("Anos 80 forte", 0.060f, 0.075f, 37.5f, 0.0f, 7.5f, 1.350f, 0.600f, 1.088f, "Vintage"),
    LIB_VINTAGE_ANOS_90_LEVE("Anos 90 leve", 0.018f, 0.060f, 4.8f, 0.0f, 3.0f, 0.875f, 1.025f, 1.100f, "Vintage"),
    LIB_VINTAGE_ANOS_90_MEDIO("Anos 90 médio", 0.030f, 0.100f, 8.0f, 0.0f, 5.0f, 0.792f, 1.042f, 1.167f, "Vintage"),
    LIB_VINTAGE_ANOS_90_FORTE("Anos 90 forte", 0.045f, 0.150f, 12.0f, 0.0f, 7.5f, 0.688f, 1.062f, 1.250f, "Vintage"),
    LIB_VINTAGE_Y2K_LEVE("Y2K leve", 0.036f, 0.108f, 12.0f, 0.0f, 3.0f, 0.843f, 1.045f, 1.113f, "Vintage"),
    LIB_VINTAGE_Y2K_MEDIO("Y2K médio", 0.060f, 0.180f, 20.0f, 0.0f, 5.0f, 0.738f, 1.075f, 1.188f, "Vintage"),
    LIB_VINTAGE_Y2K_FORTE("Y2K forte", 0.090f, 0.270f, 30.0f, 0.0f, 7.5f, 0.606f, 1.113f, 1.281f, "Vintage"),
    LIB_VINTAGE_POLAROID("Polaroid", 0.070f, -0.120f, -12.0f, 0.0f, 4.0f, 1.219f, 1.087f, 0.694f, "Vintage"),
    LIB_VINTAGE_KODACHROME("Kodachrome", 0.020f, 0.200f, 22.0f, 0.0f, 4.0f, 1.317f, 0.967f, 0.717f, "Vintage"),
    LIB_VINTAGE_FUJIFILM("Fujifilm", 0.020f, 0.140f, 12.0f, 0.0f, 4.0f, 0.812f, 1.188f, 1.000f, "Vintage"),
    LIB_VINTAGE_AGFA("Agfa", 0.040f, 0.100f, 5.0f, 0.0f, 4.0f, 1.267f, 1.067f, 0.667f, "Vintage"),
    LIB_VINTAGE_CINESTILL("Cinestill", -0.020f, 0.160f, 6.0f, 0.0f, 4.0f, 0.694f, 1.087f, 1.219f, "Vintage"),
    LIB_VINTAGE_EKTAR("Ektar", 0.020f, 0.220f, 30.0f, 0.0f, 4.0f, 1.167f, 0.967f, 0.867f, "Vintage"),
    LIB_VINTAGE_PORTRA("Portra", 0.060f, 0.020f, -4.0f, 0.0f, 4.0f, 1.225f, 1.000f, 0.775f, "Vintage"),
    LIB_VINTAGE_LOMO("Lomo", -0.040f, 0.350f, 28.0f, 0.0f, 4.0f, 1.050f, 0.750f, 1.200f, "Vintage"),
    LIB_VINTAGE_FOTO_LAVADA("Foto lavada", 0.100f, -0.300f, -30.0f, 0.0f, 4.0f, 1.133f, 1.033f, 0.833f, "Vintage"),
    LIB_VINTAGE_CARTAO_POSTAL("Cartão postal", 0.080f, -0.080f, 15.0f, 0.0f, 4.0f, 1.337f, 1.000f, 0.662f, "Vintage"),
    LIB_VINTAGE_SUPER_8("Super 8", 0.040f, 0.100f, -8.0f, 0.0f, 4.0f, 1.354f, 1.042f, 0.604f, "Vintage"),
    LIB_VINTAGE_DAGUERREOTIPO("Daguerreótipo", 0.030f, 0.250f, -90.0f, 0.0f, 4.0f, 1.333f, 1.083f, 0.600f, "Vintage"),
    LIB_VINTAGE_SLIDE("Slide", -0.020f, 0.300f, 30.0f, 0.0f, 4.0f, 1.167f, 0.967f, 0.867f, "Vintage"),
    LIB_VINTAGE_FITA_CASSETE("Fita cassete", 0.050f, -0.100f, -10.0f, 0.0f, 4.0f, 1.200f, 0.750f, 1.050f, "Vintage"),
    LIB_VINTAGE_REVISTA_ANTIGA("Revista antiga", 0.100f, -0.180f, -22.0f, 0.0f, 4.0f, 1.260f, 1.080f, 0.660f, "Vintage"),
    LIB_VINTAGE_ALBUM_DE_FAMILIA("Álbum de família", 0.080f, -0.140f, -18.0f, 0.0f, 4.0f, 1.308f, 1.060f, 0.633f, "Vintage"),
    LIB_VINTAGE_SOL_DE_VERAO_78("Sol de verão 78", 0.070f, 0.020f, 14.0f, 0.0f, 4.0f, 1.400f, 1.050f, 0.600f, "Vintage"),
    LIB_VINTAGE_DINER("Diner", 0.050f, 0.120f, 10.0f, 0.0f, 4.0f, 0.633f, 1.133f, 1.233f, "Vintage"),
    LIB_RETRATO_PELE_SUAVE("Pele suave", 0.100f, -0.080f, -8.0f, 0.0f, 3.0f, 1.119f, 0.988f, 0.894f, "Retrato"),
    LIB_RETRATO_PELE_DOURADA("Pele dourada", 0.060f, 0.060f, 6.0f, 0.0f, 1.8f, 1.212f, 1.025f, 0.762f, "Retrato"),
    LIB_RETRATO_PELE_ROSADA("Pele rosada", 0.070f, -0.020f, 4.0f, 0.0f, 2.1f, 1.219f, 0.844f, 0.938f, "Retrato"),
    LIB_RETRATO_PELE_RADIANTE("Pele radiante", 0.120f, 0.020f, 8.0f, 0.0f, 3.6f, 1.150f, 1.000f, 0.850f, "Retrato"),
    LIB_RETRATO_PELE_BRONZEADA("Pele bronzeada", 0.040f, 0.120f, 12.0f, 0.0f, 1.2f, 1.345f, 0.985f, 0.670f, "Retrato"),
    LIB_RETRATO_PELE_FRESCA("Pele fresca", 0.080f, -0.040f, -2.0f, 0.0f, 2.4f, 0.900f, 1.020f, 1.080f, "Retrato"),
    LIB_RETRATO_PORCELANA("Porcelana", 0.140f, -0.120f, -14.0f, 0.0f, 4.2f, 1.083f, 0.933f, 0.983f, "Retrato"),
    LIB_RETRATO_CONTRAMENOSLUZ("Contra-luz", 0.100f, 0.040f, 5.0f, 0.0f, 3.0f, 1.200f, 1.050f, 0.750f, "Retrato"),
    LIB_RETRATO_NATURAL("Natural", 0.040f, 0.020f, 0.0f, 0.0f, 1.2f, 1.060f, 1.000f, 0.940f, "Retrato"),
    LIB_RETRATO_EDITORIAL("Editorial", -0.020f, 0.240f, -25.0f, 0.0f, -0.6f, 1.119f, 0.988f, 0.894f, "Retrato"),
    LIB_RETRATO_ESTUDIO("Estúdio", 0.050f, 0.100f, -6.0f, 0.0f, 1.5f, 1.067f, 1.017f, 0.917f, "Retrato"),
    LIB_RETRATO_SORRISO("Sorriso", 0.100f, 0.000f, 14.0f, 0.0f, 3.0f, 1.150f, 1.000f, 0.850f, "Retrato"),
    LIB_RETRATO_OLHOS_VIVOS("Olhos vivos", 0.030f, 0.140f, 18.0f, 0.0f, 0.9f, 0.875f, 1.025f, 1.100f, "Retrato"),
    LIB_RETRATO_SUAVE_PASTEL("Suave pastel", 0.120f, -0.200f, -10.0f, 0.0f, 3.6f, 1.150f, 0.850f, 1.000f, "Retrato"),
    LIB_RETRATO_BOUDOIR("Boudoir", 0.020f, 0.080f, -4.0f, 0.0f, 0.6f, 1.321f, 0.796f, 0.883f, "Retrato"),
    LIB_RETRATO_GOLDEN_RETRATO("Golden retrato", 0.070f, 0.080f, 10.0f, 0.0f, 2.1f, 1.333f, 1.083f, 0.600f, "Retrato"),
    LIB_RETRATO_LUZ_DE_JANELA("Luz de janela", 0.100f, -0.060f, -8.0f, 0.0f, 3.0f, 0.925f, 1.000f, 1.075f, "Retrato"),
    LIB_RETRATO_CLOSEMENOSUP("Close-up", 0.040f, 0.160f, 6.0f, 0.0f, 1.2f, 1.095f, 0.990f, 0.915f, "Retrato"),
    LIB_RETRATO_CASAMENTO("Casamento", 0.120f, -0.100f, -12.0f, 0.0f, 3.6f, 1.100f, 1.025f, 0.875f, "Retrato"),
    LIB_RETRATO_BEBE("Bebê", 0.140f, -0.160f, -10.0f, 0.0f, 4.2f, 1.075f, 1.000f, 0.925f, "Retrato"),
    LIB_RETRATO_MODA_CLEAN("Moda clean", 0.060f, 0.180f, -20.0f, 0.0f, 1.8f, 0.929f, 0.992f, 1.079f, "Retrato"),
    LIB_RETRATO_BEAUTY("Beauty", 0.090f, 0.080f, 4.0f, 0.0f, 2.7f, 1.150f, 0.880f, 0.970f, "Retrato"),
    LIB_RETRATO_SELFIE("Selfie", 0.100f, 0.000f, 6.0f, 0.0f, 3.0f, 1.120f, 1.000f, 0.880f, "Retrato"),
    LIB_RETRATO_BARBA_E_CABELO("Barba e cabelo", -0.020f, 0.220f, -10.0f, 0.0f, -0.6f, 1.119f, 0.988f, 0.894f, "Retrato"),
    LIB_RETRATO_MAQUIAGEM("Maquiagem", 0.050f, 0.120f, 20.0f, 0.0f, 1.5f, 1.219f, 0.844f, 0.938f, "Retrato"),
    LIB_RETRATO_LABIOS_VIVOS("Lábios vivos", 0.030f, 0.140f, 22.0f, 0.0f, 0.9f, 1.275f, 0.825f, 0.900f, "Retrato"),
    LIB_RETRATO_PELE_TOM_ESCURO("Pele tom escuro", 0.060f, 0.100f, 6.0f, 0.0f, 1.8f, 1.153f, 0.993f, 0.853f, "Retrato"),
    LIB_RETRATO_PELE_CLARA("Pele clara", 0.100f, -0.020f, -4.0f, 0.0f, 3.0f, 1.090f, 1.000f, 0.910f, "Retrato"),
    LIB_RETRATO_HORA_AZUL("Hora azul", 0.020f, 0.100f, -6.0f, 0.0f, 0.6f, 0.733f, 0.933f, 1.333f, "Retrato"),
    LIB_RETRATO_RETRATO_NOITE("Retrato noite", -0.040f, 0.200f, -10.0f, 0.0f, -1.2f, 0.833f, 0.958f, 1.208f, "Retrato"),
    LIB_NATUREZA_FLORESTA_SUAVE("Floresta suave", -0.012f, 0.096f, 10.8f, 0.0f, 0.0f, 0.886f, 1.201f, 0.912f, "Natureza"),
    LIB_NATUREZA_FLORESTA_MEDIO("Floresta médio", -0.020f, 0.160f, 18.0f, 0.0f, 0.0f, 0.810f, 1.335f, 0.854f, "Natureza"),
    LIB_NATUREZA_FLORESTA_FORTE("Floresta forte", -0.030f, 0.240f, 27.0f, 0.0f, 0.0f, 0.716f, 1.400f, 0.781f, "Natureza"),
    LIB_NATUREZA_PRAIA_SUAVE("Praia suave", 0.042f, 0.060f, 13.2f, 0.0f, 0.0f, 0.835f, 1.060f, 1.105f, "Natureza"),
    LIB_NATUREZA_PRAIA_MEDIO("Praia médio", 0.070f, 0.100f, 22.0f, 0.0f, 0.0f, 0.725f, 1.100f, 1.175f, "Natureza"),
    LIB_NATUREZA_PRAIA_FORTE("Praia forte", 0.105f, 0.150f, 33.0f, 0.0f, 0.0f, 0.600f, 1.150f, 1.262f, "Natureza"),
    LIB_NATUREZA_MONTANHA_SUAVE("Montanha suave", 0.000f, 0.144f, 4.8f, 0.0f, 0.0f, 0.894f, 0.988f, 1.119f, "Natureza"),
    LIB_NATUREZA_MONTANHA_MEDIO("Montanha médio", 0.000f, 0.240f, 8.0f, 0.0f, 0.0f, 0.823f, 0.979f, 1.198f, "Natureza"),
    LIB_NATUREZA_MONTANHA_FORTE("Montanha forte", 0.000f, 0.360f, 12.0f, 0.0f, 0.0f, 0.734f, 0.969f, 1.297f, "Natureza"),
    LIB_NATUREZA_DESERTO_SUAVE("Deserto suave", 0.030f, 0.084f, 3.6f, 0.0f, 0.0f, 1.212f, 1.025f, 0.762f, "Natureza"),
    LIB_NATUREZA_DESERTO_MEDIO("Deserto médio", 0.050f, 0.140f, 6.0f, 0.0f, 0.0f, 1.354f, 1.042f, 0.604f, "Natureza"),
    LIB_NATUREZA_DESERTO_FORTE("Deserto forte", 0.075f, 0.210f, 9.0f, 0.0f, 0.0f, 1.400f, 1.062f, 0.600f, "Natureza"),
    LIB_NATUREZA_NEVE_SUAVE("Neve suave", 0.060f, 0.036f, -8.4f, 0.0f, 0.0f, 0.910f, 1.000f, 1.090f, "Natureza"),
    LIB_NATUREZA_NEVE_MEDIO("Neve médio", 0.100f, 0.060f, -14.0f, 0.0f, 0.0f, 0.850f, 1.000f, 1.150f, "Natureza"),
    LIB_NATUREZA_NEVE_FORTE("Neve forte", 0.150f, 0.090f, -21.0f, 0.0f, 0.0f, 0.775f, 1.000f, 1.225f, "Natureza"),
    LIB_NATUREZA_CAMPO_SUAVE("Campo suave", 0.024f, 0.084f, 14.4f, 0.0f, 0.0f, 1.000f, 1.135f, 0.865f, "Natureza"),
    LIB_NATUREZA_CAMPO_MEDIO("Campo médio", 0.040f, 0.140f, 24.0f, 0.0f, 0.0f, 1.000f, 1.225f, 0.775f, "Natureza"),
    LIB_NATUREZA_CAMPO_FORTE("Campo forte", 0.060f, 0.210f, 36.0f, 0.0f, 0.0f, 1.000f, 1.337f, 0.663f, "Natureza"),
    LIB_NATUREZA_OUTONO_SUAVE("Outono suave", 0.018f, 0.084f, 12.0f, 0.0f, 0.0f, 1.230f, 0.990f, 0.780f, "Natureza"),
    LIB_NATUREZA_OUTONO_MEDIO("Outono médio", 0.030f, 0.140f, 20.0f, 0.0f, 0.0f, 1.383f, 0.983f, 0.633f, "Natureza"),
    LIB_NATUREZA_OUTONO_FORTE("Outono forte", 0.045f, 0.210f, 30.0f, 0.0f, 0.0f, 1.400f, 0.975f, 0.600f, "Natureza"),
    LIB_NATUREZA_PRIMAVERA_SUAVE("Primavera suave", 0.042f, 0.036f, 16.8f, 0.0f, 0.0f, 0.950f, 1.137f, 0.912f, "Natureza"),
    LIB_NATUREZA_PRIMAVERA_MEDIO("Primavera médio", 0.070f, 0.060f, 28.0f, 0.0f, 0.0f, 0.917f, 1.229f, 0.854f, "Natureza"),
    LIB_NATUREZA_PRIMAVERA_FORTE("Primavera forte", 0.105f, 0.090f, 42.0f, 0.0f, 0.0f, 0.875f, 1.344f, 0.781f, "Natureza"),
    LIB_NATUREZA_OCEANO_PROFUNDO_SUAVE("Oceano profundo suave", -0.036f, 0.120f, 7.2f, 0.0f, 0.0f, 0.786f, 1.022f, 1.191f, "Natureza"),
    LIB_NATUREZA_OCEANO_PROFUNDO_MEDIO("Oceano profundo médio", -0.060f, 0.200f, 12.0f, 0.0f, 0.0f, 0.644f, 1.038f, 1.319f, "Natureza"),
    LIB_NATUREZA_OCEANO_PROFUNDO_FORTE("Oceano profundo forte", -0.090f, 0.300f, 18.0f, 0.0f, 0.0f, 0.600f, 1.056f, 1.400f, "Natureza"),
    LIB_NATUREZA_POR_DO_SOL_SUAVE("Pôr do sol suave", 0.024f, 0.108f, 15.6f, 0.0f, 0.0f, 1.275f, 0.945f, 0.780f, "Natureza"),
    LIB_NATUREZA_POR_DO_SOL_MEDIO("Pôr do sol médio", 0.040f, 0.180f, 26.0f, 0.0f, 0.0f, 1.400f, 0.908f, 0.633f, "Natureza"),
    LIB_NATUREZA_POR_DO_SOL_FORTE("Pôr do sol forte", 0.060f, 0.270f, 39.0f, 0.0f, 0.0f, 1.400f, 0.862f, 0.600f, "Natureza"),
    LIB_NATUREZA_NASCER_DO_SOL_SUAVE("Nascer do sol suave", 0.036f, 0.060f, 12.0f, 0.0f, 0.0f, 1.212f, 1.025f, 0.762f, "Natureza"),
    LIB_NATUREZA_NASCER_DO_SOL_MEDIO("Nascer do sol médio", 0.060f, 0.100f, 20.0f, 0.0f, 0.0f, 1.354f, 1.042f, 0.604f, "Natureza"),
    LIB_NATUREZA_NASCER_DO_SOL_FORTE("Nascer do sol forte", 0.090f, 0.150f, 30.0f, 0.0f, 0.0f, 1.400f, 1.062f, 0.600f, "Natureza"),
    LIB_NATUREZA_TEMPESTADE_SUAVE("Tempestade suave", -0.060f, 0.180f, -13.2f, 0.0f, 0.0f, 0.880f, 0.970f, 1.150f, "Natureza"),
    LIB_NATUREZA_TEMPESTADE_MEDIO("Tempestade médio", -0.100f, 0.300f, -22.0f, 0.0f, 0.0f, 0.800f, 0.950f, 1.250f, "Natureza"),
    LIB_NATUREZA_TEMPESTADE_FORTE("Tempestade forte", -0.150f, 0.450f, -33.0f, 0.0f, 0.0f, 0.700f, 0.925f, 1.375f, "Natureza"),
    LIB_NEON_NEON_CIANO_SUTIL("Neon Ciano sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 0.725f, 1.100f, 1.175f, "Neon"),
    LIB_NEON_NEON_CIANO_VIVO("Neon Ciano vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 0.600f, 1.183f, 1.321f, "Neon"),
    LIB_NEON_NEON_CIANO_SATURADO("Neon Ciano saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 0.600f, 1.283f, 1.400f, "Neon"),
    LIB_NEON_NEON_MAGENTA_SUTIL("Neon Magenta sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.175f, 0.725f, 1.100f, "Neon"),
    LIB_NEON_NEON_MAGENTA_VIVO("Neon Magenta vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.321f, 0.600f, 1.183f, "Neon"),
    LIB_NEON_NEON_MAGENTA_SATURADO("Neon Magenta saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.400f, 0.600f, 1.283f, "Neon"),
    LIB_NEON_NEON_VERDE_ACIDO_SUTIL("Neon Verde ácido sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 0.950f, 1.250f, 0.800f, "Neon"),
    LIB_NEON_NEON_VERDE_ACIDO_VIVO("Neon Verde ácido vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 0.908f, 1.400f, 0.633f, "Neon"),
    LIB_NEON_NEON_VERDE_ACIDO_SATURADO("Neon Verde ácido saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 0.858f, 1.400f, 0.600f, "Neon"),
    LIB_NEON_NEON_LARANJA_SUTIL("Neon Laranja sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.237f, 0.975f, 0.787f, "Neon"),
    LIB_NEON_NEON_LARANJA_VIVO("Neon Laranja vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.400f, 0.954f, 0.610f, "Neon"),
    LIB_NEON_NEON_LARANJA_SATURADO("Neon Laranja saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.400f, 0.929f, 0.600f, "Neon"),
    LIB_NEON_NEON_AZUL_ELETRICO_SUTIL("Neon Azul elétrico sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 0.825f, 0.900f, 1.275f, "Neon"),
    LIB_NEON_NEON_AZUL_ELETRICO_VIVO("Neon Azul elétrico vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 0.679f, 0.817f, 1.400f, "Neon"),
    LIB_NEON_NEON_AZUL_ELETRICO_SATURADO("Neon Azul elétrico saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 0.600f, 0.717f, 1.400f, "Neon"),
    LIB_NEON_NEON_VIOLETA_SUTIL("Neon Violeta sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.025f, 0.763f, 1.212f, "Neon"),
    LIB_NEON_NEON_VIOLETA_VIVO("Neon Violeta vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.046f, 0.600f, 1.390f, "Neon"),
    LIB_NEON_NEON_VIOLETA_SATURADO("Neon Violeta saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.071f, 0.600f, 1.400f, "Neon"),
    LIB_NEON_NEON_ROSA_CHOQUE_SUTIL("Neon Rosa choque sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.225f, 0.775f, 1.000f, "Neon"),
    LIB_NEON_NEON_ROSA_CHOQUE_VIVO("Neon Rosa choque vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.400f, 0.600f, 1.000f, "Neon"),
    LIB_NEON_NEON_ROSA_CHOQUE_SATURADO("Neon Rosa choque saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.400f, 0.600f, 1.000f, "Neon"),
    LIB_NEON_NEON_AMARELO_SUTIL("Neon Amarelo sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.155f, 1.140f, 0.705f, "Neon"),
    LIB_NEON_NEON_AMARELO_VIVO("Neon Amarelo vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.284f, 1.257f, 0.600f, "Neon"),
    LIB_NEON_NEON_AMARELO_SATURADO("Neon Amarelo saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.400f, 1.397f, 0.600f, "Neon"),
    LIB_NEON_NEON_TURQUESA_SUTIL("Neon Turquesa sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 0.725f, 1.175f, 1.100f, "Neon"),
    LIB_NEON_NEON_TURQUESA_VIVO("Neon Turquesa vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 0.600f, 1.321f, 1.183f, "Neon"),
    LIB_NEON_NEON_TURQUESA_SATURADO("Neon Turquesa saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 0.600f, 1.400f, 1.283f, "Neon"),
    LIB_NEON_NEON_VERMELHO_SUTIL("Neon Vermelho sutil", -0.040f, 0.180f, 45.0f, 0.0f, -4.0f, 1.295f, 0.845f, 0.860f, "Neon"),
    LIB_NEON_NEON_VERMELHO_VIVO("Neon Vermelho vivo", -0.040f, 0.280f, 45.0f, 0.0f, -4.0f, 1.400f, 0.716f, 0.743f, "Neon"),
    LIB_NEON_NEON_VERMELHO_SATURADO("Neon Vermelho saturado", -0.040f, 0.400f, 45.0f, 0.0f, -4.0f, 1.400f, 0.600f, 0.603f, "Neon"),
    LIB_CRIATIVOS_MATIZ_MENOS40_NATURAL("Matiz -40° natural", 0.000f, 0.060f, 8.0f, -40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS40_VIVIDO("Matiz -40° vívido", 0.000f, 0.160f, 30.0f, -40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS40_PSICODELICO("Matiz -40° psicodélico", 0.000f, 0.300f, 60.0f, -40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS32_NATURAL("Matiz -32° natural", 0.000f, 0.060f, 8.0f, -32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS32_VIVIDO("Matiz -32° vívido", 0.000f, 0.160f, 30.0f, -32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS32_PSICODELICO("Matiz -32° psicodélico", 0.000f, 0.300f, 60.0f, -32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS24_NATURAL("Matiz -24° natural", 0.000f, 0.060f, 8.0f, -24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS24_VIVIDO("Matiz -24° vívido", 0.000f, 0.160f, 30.0f, -24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS24_PSICODELICO("Matiz -24° psicodélico", 0.000f, 0.300f, 60.0f, -24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS16_NATURAL("Matiz -16° natural", 0.000f, 0.060f, 8.0f, -16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS16_VIVIDO("Matiz -16° vívido", 0.000f, 0.160f, 30.0f, -16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS16_PSICODELICO("Matiz -16° psicodélico", 0.000f, 0.300f, 60.0f, -16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS8_NATURAL("Matiz -8° natural", 0.000f, 0.060f, 8.0f, -8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS8_VIVIDO("Matiz -8° vívido", 0.000f, 0.160f, 30.0f, -8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MENOS8_PSICODELICO("Matiz -8° psicodélico", 0.000f, 0.300f, 60.0f, -8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS8_NATURAL("Matiz +8° natural", 0.000f, 0.060f, 8.0f, 8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS8_VIVIDO("Matiz +8° vívido", 0.000f, 0.160f, 30.0f, 8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS8_PSICODELICO("Matiz +8° psicodélico", 0.000f, 0.300f, 60.0f, 8.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS16_NATURAL("Matiz +16° natural", 0.000f, 0.060f, 8.0f, 16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS16_VIVIDO("Matiz +16° vívido", 0.000f, 0.160f, 30.0f, 16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS16_PSICODELICO("Matiz +16° psicodélico", 0.000f, 0.300f, 60.0f, 16.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS24_NATURAL("Matiz +24° natural", 0.000f, 0.060f, 8.0f, 24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS24_VIVIDO("Matiz +24° vívido", 0.000f, 0.160f, 30.0f, 24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS24_PSICODELICO("Matiz +24° psicodélico", 0.000f, 0.300f, 60.0f, 24.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS32_NATURAL("Matiz +32° natural", 0.000f, 0.060f, 8.0f, 32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS32_VIVIDO("Matiz +32° vívido", 0.000f, 0.160f, 30.0f, 32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS32_PSICODELICO("Matiz +32° psicodélico", 0.000f, 0.300f, 60.0f, 32.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS40_NATURAL("Matiz +40° natural", 0.000f, 0.060f, 8.0f, 40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS40_VIVIDO("Matiz +40° vívido", 0.000f, 0.160f, 30.0f, 40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_MATIZ_MAIS40_PSICODELICO("Matiz +40° psicodélico", 0.000f, 0.300f, 60.0f, 40.0f, 0.0f, 1.000f, 1.000f, 1.000f, "Criativos"),
    LIB_CRIATIVOS_INVERSO_SUAVE("Inverso suave", 0.100f, -0.300f, -40.0f, 40.0f, 0.0f, 1.100f, 0.900f, 0.900f, "Criativos"),
    LIB_CRIATIVOS_CROSS_PROCESS("Cross process", -0.020f, 0.300f, 30.0f, -20.0f, 0.0f, 1.140f, 0.960f, 0.800f, "Criativos"),
    LIB_CRIATIVOS_INFRAVERMELHO("Infravermelho", 0.100f, 0.100f, -50.0f, -35.0f, 0.0f, 1.300f, 0.700f, 1.100f, "Criativos"),
    LIB_CRIATIVOS_ALUCINOGENO("Alucinógeno", 0.000f, 0.300f, 70.0f, 30.0f, 0.0f, 1.000f, 1.100f, 1.100f, "Criativos"),
    LIB_CRIATIVOS_TERMICO("Térmico", 0.000f, 0.400f, 60.0f, -30.0f, 0.0f, 1.300f, 0.800f, 0.700f, "Criativos"),
    LIB_CRIATIVOS_RAIOMENOSX("Raio-X", 0.050f, 0.400f, -100.0f, 0.0f, 0.0f, 0.800f, 1.100f, 1.300f, "Criativos"),
    LIB_CRIATIVOS_HOLOGRAFICO("Holográfico", 0.050f, 0.200f, 40.0f, 25.0f, 0.0f, 0.900f, 1.100f, 1.300f, "Criativos"),
    LIB_CRIATIVOS_VAPORWAVE("Vaporwave", 0.040f, 0.180f, 30.0f, -20.0f, 0.0f, 1.100f, 0.900f, 1.250f, "Criativos"),
    LIB_CRIATIVOS_SYNTHWAVE("Synthwave", -0.040f, 0.300f, 50.0f, -25.0f, 0.0f, 1.150f, 0.800f, 1.300f, "Criativos"),
    LIB_CRIATIVOS_GLITCH_FRIO("Glitch frio", 0.000f, 0.300f, 20.0f, 20.0f, 0.0f, 0.800f, 1.100f, 1.300f, "Criativos"),
    // END GENERATED FILTERS
    ;
}

enum class ClipTransition(val label: String) {
    NONE("Sem transicao"),
    FADE_BLACK("Fade escuro"),
    FADE_WHITE("Fade claro"),
}

enum class ClipMotion(val label: String) {
    NONE("Sem movimento"),
    ZOOM_IN("Zoom aproximando"),
    ZOOM_OUT("Zoom afastando"),
    PAN_LEFT("Panoramica esquerda"),
    PAN_RIGHT("Panoramica direita"),
}

/**
 * What happens to a reversed clip's audio.
 *
 * Reversed speech is unintelligible, so the choice is always explicit. Muting silently is
 * never the default: a user who reverses a clip with sound must not lose it by accident.
 */
enum class ReverseAudio(val label: String) {
    KEEP_FORWARD("Manter (som original)"),
    MUTE("Silenciar"),
    REVERSE("Inverter (reversed)"),
}

data class VideoClip(
    val id: String = newId(), val uri: String, val name: String,
    val sourceUs: Long, val width: Int, val height: Int, val fps: Float = 30f,
    val image: Boolean = false, val inUs: Long = 0, val outUs: Long = sourceUs,
    val speed: Float = 1f, val volume: Float = 1f, val rotation: Int = 0,
    val flip: Boolean = false, val crop: CropRect = CropRect(), val filter: Int = 0,
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val hue: Float = 0f, val lightness: Float = 0f, val temperature: Float = 0f,
    val filterStrength: Float = 1f,
    val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f,
    val fineRotation: Float = 0f, val opacity: Float = 1f, val blur: Float = 0f,
    val motion: ClipMotion = ClipMotion.NONE,
    val transitionIn: ClipTransition = ClipTransition.NONE,
    val transitionOut: ClipTransition = ClipTransition.NONE,
    val transitionDurationUs: Long = 400_000L,
    val audioFadeInUs: Long = 0L, val audioFadeOutUs: Long = 0L,
    val keyframes: List<TransformKeyframe> = emptyList(),
    val grade: StudioGrade = StudioGrade(),
    val effects: List<com.termex.replay15.editor.assets.EffectInstance> = emptyList(),
    val speedCurve: List<SpeedPoint> = emptyList(),
    val preservePitch: Boolean = false,
    /**
     * Plays the trimmed window backwards. Duration is unchanged, so every other time mapping,
     * transition and audio placement stays valid; only the direction of [timeMap.sourceAt]
     * is mirrored. See [ReverseAudio] for what happens to the sound.
     */
    val reverse: Boolean = false,
    val reverseAudio: ReverseAudio = ReverseAudio.KEEP_FORWARD,
    val mimeType: String = "",
    val proxyUri: String? = null,
    /**
     * Frame-interpolated render of a slowed-down clip, produced in the background by
     * [com.termex.replay15.editor.motion.SmoothSlowMoCache]. Like [proxyUri] this is a
     * preview-only artifact: it is never persisted and export always reads [uri], so the
     * original media is never replaced.
     */
    val derivedUri: String? = null,
    val is3D: Boolean = false,
    val transform3D: Transform3D = Transform3D(),
    val parentId: String? = null,
    val isNullObject: Boolean = false,
    val backgroundRemoval: BackgroundRemovalEffect = BackgroundRemovalEffect(),
    val mask: MaskState? = null,
    val trackingTracks: List<TrackingTrack> = emptyList(),
    val enhance: AudioEnhance = AudioEnhance.NEUTRAL,
) {
    val effectivePreviewUri: String get() = derivedUri ?: proxyUri ?: uri
    init {
        require(parentId == null || (parentId != id && parentId.matches(Regex("[a-zA-Z0-9-]{1,80}"))))
        require(sourceUs > 0 && inUs >= 0 && outUs <= sourceUs && outUs > inUs)
        require(width > 0 && height > 0 && speed in MIN_CLIP_SPEED..MAX_CLIP_SPEED && volume in 0f..2f)
        require(fps.isFinite() && fps > 0 && rotation in listOf(0, 90, 180, 270) && filter in VideoFilter.entries.indices)
        require(brightness in -1f..1f && contrast in -.9f.. .9f)
        require(saturation in -100f..100f && hue in -180f..180f && lightness in -100f..100f && temperature in -1f..1f)
        require(filterStrength in 0f..1f)
        require(zoom in .25f..4f && offsetX in -.5f.. .5f && offsetY in -.5f.. .5f)
        require(fineRotation in -180f..180f && opacity in 0f..1f && blur in 0f..25f)
        require(transitionDurationUs in 100_000L..2_000_000L)
        require(audioFadeInUs in 0L..10_000_000L && audioFadeOutUs in 0L..10_000_000L)
        require(keyframes.size <= 200 && keyframes.all { it.sourceUs <= sourceUs })
        require(keyframes.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        require(effects.size <= 12 && effects.map { it.id }.distinct().size == effects.size)
        require(effects.flatMap { it.keyframes.values.flatten() }.all { it.sourceUs <= sourceUs })
        require(speedCurve.size <= 32 && speedCurve.all { it.sourceUs <= sourceUs })
        require(speedCurve.zipWithNext().all { (a, b) -> a.sourceUs < b.sourceUs })
        require(mask?.keyframes?.all { it.timeUs <= sourceUs } != false)
        require(trackingTracks.size <= 8 && trackingTracks.map { it.id }.distinct().size == trackingTracks.size)
    }
    val timeMap: ClipTimeMap by lazy(LazyThreadSafetyMode.PUBLICATION) { ClipTimeMap(this) }
    val durationUs: Long get() = if (speedCurve.isEmpty()) ((outUs - inUs) / speed.toDouble()).roundToLong() else timeMap.durationUs
}

data class VolumeKeyframe(
    val timeUs: Long,
    val volume: Float,
) {
    init {
        require(timeUs >= 0L) { "VolumeKeyframe timeUs must be non-negative" }
        require(volume in 0f..2f) { "Volume must be in 0f..2f" }
    }
}

data class AudioClip(
    val id: String = newId(), val uri: String, val name: String, val sourceUs: Long,
    val startUs: Long = 0, val inUs: Long = 0, val outUs: Long = sourceUs, val volume: Float = 1f,
    val fadeInUs: Long = 0L, val fadeOutUs: Long = 0L,
    val volumeKeyframes: List<VolumeKeyframe> = emptyList(),
    val enhance: AudioEnhance = AudioEnhance.NEUTRAL,
) {
    init {
        require(sourceUs > 0 && startUs >= 0 && inUs >= 0 && outUs > inUs && outUs <= sourceUs && volume in 0f..2f)
        require(fadeInUs in 0L..10_000_000L && fadeOutUs in 0L..10_000_000L)
        require(volumeKeyframes.size <= 200)
    }
    val durationUs: Long get() = outUs - inUs
    val endUs: Long get() = startUs + durationUs

    fun volumeAt(localTimeUs: Long): Float {
        if (volumeKeyframes.isEmpty()) return volume
        val sorted = if (volumeKeyframes.zipWithNext().all { (a, b) -> a.timeUs <= b.timeUs }) volumeKeyframes else volumeKeyframes.sortedBy { it.timeUs }
        if (localTimeUs <= sorted.first().timeUs) return sorted.first().volume
        if (localTimeUs >= sorted.last().timeUs) return sorted.last().volume
        val nextIndex = sorted.indexOfFirst { it.timeUs >= localTimeUs }
        if (nextIndex <= 0) return sorted.first().volume
        val prev = sorted[nextIndex - 1]
        val next = sorted[nextIndex]
        val span = next.timeUs - prev.timeUs
        if (span <= 0) return prev.volume
        val t = ((localTimeUs - prev.timeUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return prev.volume + (next.volume - prev.volume) * t
    }
}

/** Legacy enum kept only for schema <= 12 and reference-analysis compatibility. */
enum class TextFont(val id: String, val label: String, val family: String, val asset: String = "") {
    MODERNA("system_sans", "Moderna", "sans-serif"),
    ELEGANTE("system_serif", "Elegante", "serif"),
    IMPACTO("system_sans_black", "Impacto", "sans-serif-black"),
    CONDENSADA("system_sans_condensed", "Condensada", "sans-serif-condensed"),
    LEVE("system_sans_light", "Leve", "sans-serif-light"),
    MAQUINA("system_monospace", "Maquina", "monospace"),
    EDITORIAL("system_serif_monospace", "Editorial", "serif-monospace"),
    MANUSCRITA("system_cursive", "Manuscrita", "cursive"),
    CASUAL("system_casual", "Casual", "casual"),
    OUTFIT("outfit_regular", "Outfit", "sans-serif", "outfit.ttf"),
    MONTSERRAT("montserrat_regular", "Montserrat", "sans-serif", "montserrat.ttf"),
    BEBAS("bebas_neue_regular", "Bebas Neue", "sans-serif-condensed", "bebas.ttf"),
    PLAYFAIR("playfair_display_regular", "Playfair Display", "serif", "playfair.ttf"),
    CAVEAT("caveat_regular", "Caveat", "cursive", "caveat.ttf"),
    SPACE_MONO("space_mono_regular", "Space Mono", "monospace", "spacemono.ttf"),
}

enum class TextAlignment { LEFT, CENTER, RIGHT }
enum class TextAnimation(val label: String) {
    NONE("Sem animacao"),
    FADE("Suave"),
    POP("Pop"),
    SLIDE_UP("Subir"),
    SLIDE_LEFT("Lateral"),
    SLIDE_DOWN("Descer"),
    SLIDE_RIGHT("Direita"),
    BOUNCE("Bounce"),
    ZOOM("Zoom"),
    BLUR_IN("Blur in"),
    PULSE("Pulse"),
    SOFT_BOUNCE("Soft bounce"),
    FLOAT("Float"),
    SHAKE("Shake"),
    WORD_POP("Word pop"),
    KARAOKE("Karaoke"),
    CURRENT_WORD_HIGHLIGHT("Palavra atual"),
    FADE_OUT("Fade out"),
    ZOOM_OUT("Zoom out"),
    BLUR_OUT("Blur out"),
    TYPEWRITER("Typewriter"),
    SCALE("Scale"),
    GLITCH("Glitch"),
    ELASTIC("Elastic"),
    WAVE("Wave"),
    TRACKING("Tracking"),
}

/** A measured word boundary used by karaoke and word-level animation. */
data class SubtitleWordCue(val text: String, val startUs: Long, val endUs: Long) {
    init {
        require(text.isNotBlank() && text.length <= 120)
        require(startUs >= 0L && endUs > startUs)
    }
}

/** Per-word appearance. A zero color means "keep the layer color". */
data class SubtitleWordStyle(
    val normalColor: Int = 0,
    val spokenColor: Int = 0,
    val activeColor: Int = 0,
    val futureColor: Int = 0,
    val activeScale: Float = 1f,
    val boldCurrentWord: Boolean = false,
    /** Caixa arredondada atrás da palavra que está sendo falada (0 = sem caixa). */
    val highlightColor: Int = 0,
    /** Palavras ainda não faladas ficam invisíveis e aparecem uma a uma (o espaço já é reservado). */
    val reveal: Boolean = false,
    /** Mostra a legenda em CAIXA ALTA só na tela; o texto salvo não muda. */
    val uppercase: Boolean = false,
    /** A palavra falada "pula" ao começar (cresce com overshoot até [activeScale]). */
    val pop: Boolean = false,
    /** Cores que se revezam palavra a palavra na palavra falada (vazio = usa [activeColor]). */
    val activePalette: List<Int> = emptyList(),
) {
    init {
        require(activeScale in .8f..1.5f)
        require(activePalette.size <= 8)
    }

    /** True when any per-word effect needs the word timing to render. */
    val animated: Boolean
        get() = activeColor != 0 || spokenColor != 0 || futureColor != 0 || activeScale != 1f || boldCurrentWord ||
            highlightColor != 0 || reveal || pop || activePalette.isNotEmpty()
}

data class StickerClip(
    val id: String = newId(), val uri: String, val name: String,
    val startUs: Long, val endUs: Long,
    val x: Float = .5f, val y: Float = .5f, val size: Float = .25f,
    val rotation: Float = 0f, val opacity: Float = 1f, val flip: Boolean = false,
    val animation: TextAnimation = TextAnimation.NONE,
    val transformKeyframes: List<com.termex.replay15.editor.transform.TransformKeyframe> = emptyList(),
    val scale: Float = 1f,
    val backgroundRemoval: BackgroundRemovalEffect = BackgroundRemovalEffect(),
    val mask: MaskState? = null,
) {
    val durationUs: Long
        get() = (endUs - startUs).coerceAtLeast(0L)

    fun withDuration(newDurationUs: Long): StickerClip = copy(endUs = startUs + newDurationUs)

    val baseTransform: com.termex.replay15.editor.transform.TransformState
        get() = com.termex.replay15.editor.transform.TransformState(
            x = x,
            y = y,
            scaleX = scale,
            scaleY = scale,
            rotation = rotation,
            opacity = opacity,
        )

    init {
        require(uri.isNotBlank() && name.length <= 200 && startUs >= 0 && endUs > startUs)
        require(x in 0f..1f && y in 0f..1f && size in .03f..1.5f)
        require(scale.isFinite() && scale in 0.05f..20f)
        require(rotation.isFinite() && rotation in -180f..180f && opacity in .05f..1f)
        require(transformKeyframes.size <= 200)
        require(mask?.keyframes?.all { it.timeUs <= durationUs } != false)
    }

    fun transformAt(timeUs: Long): com.termex.replay15.editor.transform.TransformState {
        return com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(this, timeUs, realtime = false).transform
    }
}

const val MIN_STICKER_DURATION_US = 200_000L
const val MIN_AUDIO_DURATION_US = 200_000L
const val MIN_TEXT_DURATION_US = 200_000L

data class TextClip(
    val id: String = newId(), val text: String, val startUs: Long, val endUs: Long,
    val x: Float = .5f, val y: Float = .75f, val size: Float = .06f,
    val color: Int = -1, val bold: Boolean = true,
    val fontId: String = TextFont.MODERNA.id, val italic: Boolean = false,
    val underline: Boolean = false, val alignment: TextAlignment = TextAlignment.CENTER,
    val opacity: Float = 1f, val letterSpacing: Float = 0f, val lineSpacing: Float = 1f,
    val boxWidth: Float = 0f, val rotation: Float = 0f,
    val backgroundColor: Int = 0, val outlineColor: Int = 0xFF000000.toInt(),
    val outlineWidth: Float = 0f, val shadow: Boolean = false,
    val animation: TextAnimation = TextAnimation.NONE,
    val fontFamily: String = "",
    val fontWeight: Int = 400,
    val fontStyle: String = "normal",
    val fontVersion: String = "",
    val transformKeyframes: List<com.termex.replay15.editor.transform.TransformKeyframe> = emptyList(),
    val scale: Float = 1f,
    val isCaption: Boolean = false,
    val captionFontOverride: String? = null,
    val enterAnimation: TextAnimation = TextAnimation.NONE,
    val duringAnimation: TextAnimation = TextAnimation.NONE,
    val exitAnimation: TextAnimation = TextAnimation.NONE,
    val wordCues: List<SubtitleWordCue> = emptyList(),
    val wordStyle: SubtitleWordStyle = SubtitleWordStyle(),
    val mask: MaskState? = null,
) {
    val durationUs: Long
        get() = (endUs - startUs).coerceAtLeast(0L)

    fun withDuration(newDurationUs: Long): TextClip = copy(endUs = startUs + newDurationUs)

    val baseTransform: com.termex.replay15.editor.transform.TransformState
        get() = com.termex.replay15.editor.transform.TransformState(
            x = x,
            y = y,
            scaleX = scale,
            scaleY = scale,
            rotation = rotation,
            opacity = opacity,
        )

    init {
        require(text.isNotBlank() && text.length <= 500 && startUs >= 0 && endUs > startUs)
        require(fontId.matches(Regex("[a-z0-9_]{1,80}")))
        require(fontWeight in 1..1000)
        require(fontFamily.length <= 100 && fontStyle.length <= 40 && fontVersion.length <= 40)
        require(x in 0f..1f && y in 0f..1f && size in .02f.. .25f)
        require(scale.isFinite() && scale in 0.05f..20f)
        require(opacity in .1f..1f && letterSpacing in -.05f.. .3f && lineSpacing in .7f..2f)
        require(boxWidth == 0f || boxWidth in .2f.. .95f)
        require(rotation.isFinite() && rotation in -180f..180f && outlineWidth in 0f.. .04f)
        require(transformKeyframes.size <= 200)
        require(wordCues.size <= 300 && wordCues.zipWithNext().all { (a, b) -> a.startUs <= b.startUs })
        require(wordCues.all { it.startUs >= startUs && it.endUs <= endUs })
        require(mask?.keyframes?.all { it.timeUs <= durationUs } != false)
    }

    fun transformAt(timeUs: Long): com.termex.replay15.editor.transform.TransformState {
        return com.termex.replay15.editor.transform.LayerStateEvaluator.evaluate(this, timeUs, realtime = false).transform
    }
}

data class ExportSettings(
    val shortSide: Int = 720, val fps: Int = 30, val bitrate: Int = 8_000_000,
    val audioBitrate: Int = 192_000,
) {
    init {
        require(shortSide in listOf(480, 720, 1080, 1440, 2160))
        require(fps in listOf(24, 25, 30, 50, 60, 120, 144) && bitrate in 1_000_000..160_000_000)
        require(audioBitrate in listOf(96_000, 128_000, 192_000, 256_000, 320_000))
    }
    fun estimatedBytes(durationUs: Long): Long = ((bitrate.toLong() + audioBitrate) * (durationUs / 1_000_000.0) / 8).roundToLong()

    companion object {
        /** Matches a newly-created project to its source without inventing resolution or frames. */
        fun forSource(source: VideoClip, audioBitrate: Int = 192_000): ExportSettings {
            val sourceShortSide = min(source.width, source.height)
            val shortSide = listOf(480, 720, 1080, 1440, 2160).lastOrNull { it <= sourceShortSide + 8 } ?: 480
            val fps = listOf(24, 25, 30, 50, 60, 120, 144).lastOrNull { it <= source.fps + .5f } ?: 24
            val baseBitrate = when (shortSide) {
                480 -> 4_000_000
                720 -> 8_000_000
                1080 -> 16_000_000
                1440 -> 28_000_000
                else -> 48_000_000
            }
            val fpsScale = when {
                fps >= 144 -> 4.1
                fps >= 120 -> 3.4
                fps >= 60 -> 1.8
                fps > 30 -> 1.5
                else -> 1.0
            }
            val bitrate = (baseBitrate * fpsScale).roundToLong().toInt().coerceAtMost(160_000_000)
            return ExportSettings(shortSide, fps, bitrate, audioBitrate)
        }
    }
}

data class Project(
    val id: String = newId(), val name: String = "Novo projeto",
    val videos: List<VideoClip> = emptyList(), val audio: List<AudioClip> = emptyList(),
    val texts: List<TextClip> = emptyList(), val aspect: Float = 0f,
    val export: ExportSettings = ExportSettings(),
    val stickers: List<StickerClip> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val canvasFill: CanvasFill = CanvasFill.FIT,
    val videoTracks: List<VideoTrack> = emptyList(),
    val trackStates: Map<String, TrackState> = emptyMap(),
    val adjustmentClips: List<AdjustmentClip> = emptyList(),
    val transitions: List<com.termex.replay15.editor.assets.TransitionInstance> = emptyList(),
    val schemaVersion: Int = PROJECT_SCHEMA,
    val createdAt: Long = 0L,
    val modifiedAt: Long = 0L,
    val camera: Camera3D = Camera3D(),
    val captionGlobalFontId: String = TextFont.MODERNA.id,
    val captionLanguage: String = "pt-BR",
    val captionVocabulary: Set<String> = emptySet(),
    /** Nested sequences over [videos]. Grouping is metadata only: the clips never leave this list. */
    val compounds: List<CompoundClip> = emptyList(),
    /** Movimentos em balão sobre clipes, textos e stickers; tempo relativo ao início do alvo. */
    val animationClips: List<AnimationClip> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")) && name.length <= 200)
        require(videos.size <= 200 && audio.size <= 8 && texts.size <= MAX_TEXTS && stickers.size <= 24)
        require(markers.size <= 500 && backgroundColor ushr 24 == 255)
        require(aspect == 0f || aspect in .2f..5f)
        require(schemaVersion == PROJECT_SCHEMA && createdAt >= 0 && modifiedAt >= 0)
        require(captionLanguage in setOf("pt-BR", "en-US", "auto"))
        require(captionVocabulary.size <= 500 && captionVocabulary.all { it.length in 1..80 })
        require(videoTracks.size <= 7 && videoTracks.map { it.id }.distinct().size == videoTracks.size)
        require(adjustmentClips.size <= 8 && adjustmentClips.map { it.id }.distinct().size == adjustmentClips.size)
        require(transitions.size <= 200 && transitions.map { it.id }.distinct().size == transitions.size)
        require(videoTracks.none { it.id == MAIN_TRACK || it.id == TEXT_TRACK || it.id == STICKER_TRACK })
        require(trackStates.size <= 64 && trackStates.keys.all { it.length in 1..100 })
        require(videos.sumOf { it.durationUs } <= MAX_PROJECT_US)
        require(compounds.size <= MAX_COMPOUNDS && compounds.map { it.id }.distinct().size == compounds.size)
        require(animationClips.size <= MAX_ANIMATION_CLIPS && animationClips.map { it.id }.distinct().size == animationClips.size)
        val owned = compounds.flatMap { it.childIds }
        require(owned.distinct().size == owned.size) { "Um clipe nao pode pertencer a dois compounds" }
        require(owned.all { child -> videos.any { it.id == child } }) { "Compound referencia um clipe inexistente" }
    }

    /** Transitions are render windows at cuts; they never shorten clip placement. */
    val overlapUs: Long get() = 0L
    val durationUs: Long get() = maxOf(videos.sumOf { it.durationUs }, videoTracks.maxOfOrNull { it.clips.lastOrNull()?.endUs ?: 0L } ?: 0L)

    fun transitionBetween(leftClipId: String, rightClipId: String): com.termex.replay15.editor.assets.TransitionInstance? =
        transitions.firstOrNull { it.leftClipId == leftClipId && it.rightClipId == rightClipId }

    fun withTransition(transition: com.termex.replay15.editor.assets.TransitionInstance): Project {
        val left = videos.firstOrNull { it.id == transition.leftClipId }
        val right = videos.firstOrNull { it.id == transition.rightClipId }
        val transEnteringLeft = transitions.firstOrNull { it.rightClipId == transition.leftClipId }?.durationUs ?: 0L
        val transLeavingRight = transitions.firstOrNull { it.leftClipId == transition.rightClipId }?.durationUs ?: 0L
        val availableLeft = if (left != null) (left.durationUs - transEnteringLeft).coerceAtLeast(100_000L) else transition.durationUs
        val availableRight = if (right != null) (right.durationUs - transLeavingRight).coerceAtLeast(100_000L) else transition.durationUs
        val maxDuration = minOf(availableLeft, availableRight, 5_000_000L)
        val clampedDuration = transition.durationUs.coerceIn(100_000L, maxDuration)
        val safeTransition = transition.copy(durationUs = clampedDuration)
        val filtered = transitions.filterNot { it.leftClipId == safeTransition.leftClipId && it.rightClipId == safeTransition.rightClipId }
        return copy(transitions = filtered + safeTransition)
    }

    fun withoutTransition(leftClipId: String, rightClipId: String): Project =
        copy(transitions = transitions.filterNot { it.leftClipId == leftClipId && it.rightClipId == rightClipId })

    fun cleanTransitions(targetVideos: List<VideoClip> = videos): List<com.termex.replay15.editor.assets.TransitionInstance> {
        val pairs = targetVideos.zipWithNext().map { (a, b) -> a.id to b.id }.toSet()
        return transitions.filter { (it.leftClipId to it.rightClipId) in pairs }
    }

    /**
     * The one rule every edit must respect: a transition exists only on a real cut between two
     * adjacent main clips, at most once per cut, and it plays entirely inside the right clip.
     * Trims, speed changes, duplicates, inserts and old project files can all break that, so
     * [EditorSessionController] and [ProjectCodec] run every snapshot through here.
     */
    fun withSanitizedTransitions(): Project {
        if (transitions.isEmpty()) return this
        val rightOf = videos.zipWithNext().associate { (left, right) -> (left.id to right.id) to right }
        val seen = HashSet<Pair<String, String>>()
        val sanitized = transitions.mapNotNull { transition ->
            val key = transition.leftClipId to transition.rightClipId
            val right = rightOf[key] ?: return@mapNotNull null
            if (!seen.add(key)) return@mapNotNull null
            val maximum = minOf(right.durationUs, 5_000_000L)
            if (maximum < 100_000L) return@mapNotNull null
            if (transition.durationUs <= maximum) transition else transition.copy(durationUs = maximum)
        }
        return if (sanitized == transitions) this else copy(transitions = sanitized)
    }

    fun startOf(index: Int): Long {
        var start = 0L
        for (i in 0 until index.coerceAtMost(videos.size)) {
            start += videos[i].durationUs
        }
        return start
    }

    fun indexAt(timeUs: Long): Int {
        if (videos.isEmpty()) return -1
        for (i in 0 until videos.lastIndex) {
            val nextStart = startOf(i + 1)
            if (timeUs < nextStart) return i
        }
        return videos.lastIndex
    }
    fun dimensions(shortSide: Int = export.shortSide): Pair<Int, Int> {
        val first = videos.firstOrNull() ?: videoTracks.firstOrNull()?.clips?.firstOrNull()?.clip
        val sourceRatio = first?.let {
            val ratio = it.width * (it.crop.right - it.crop.left) / (it.height * (it.crop.bottom - it.crop.top))
            if (it.rotation % 180 == 0) ratio else 1f / ratio
        } ?: (16f / 9f)
        val ratio = if (aspect == 0f) sourceRatio else aspect
        fun even(value: Float) = ((value / 2).toInt() * 2).coerceAtLeast(2)
        return if (ratio >= 1f) even(shortSide * ratio) to shortSide else shortSide to even(shortSide / ratio)
    }
    fun split(index: Int, playheadUs: Long): Project {
        if (videos.size >= 200) return this
        val clip = videos.getOrNull(index) ?: return this
        val offset = playheadUs - startOf(index)
        if (offset < MIN_CLIP || clip.durationUs - offset < MIN_CLIP) return this
        val cut = clip.timeMap.sourceAt(offset)
        if (cut <= clip.inUs || cut >= clip.outUs) return this
        val result = videos.toMutableList()
        val cutTransform = clip.transformAt(cut).copy(sourceUs = cut)
        val leftKeys = if (clip.keyframes.isEmpty()) {
            emptyList()
        } else {
            (clip.keyframes.filter { it.sourceUs < cut } + cutTransform)
                .distinctBy { it.sourceUs }
                .sortedBy { it.sourceUs }
        }
        val rightKeys = if (clip.keyframes.isEmpty()) {
            emptyList()
        } else {
            (listOf(cutTransform) + clip.keyframes.filter { it.sourceUs > cut })
                .distinctBy { it.sourceUs }
                .sortedBy { it.sourceUs }
        }
        result[index] = clip.copy(
            outUs = cut,
            transitionOut = ClipTransition.NONE,
            keyframes = leftKeys,
        )
        val rightId = newId()
        result.add(
            index + 1,
            clip.copy(
                id = rightId,
                inUs = cut,
                transitionIn = ClipTransition.NONE,
                keyframes = rightKeys,
            ),
        )
        // The outgoing transition belongs to the cut after the clip, which is now the right half's.
        val moved = transitions.map { if (it.leftClipId == clip.id) it.copy(leftClipId = rightId) else it }
        // Animation balloons follow their halves; one that crosses the cut is split in two.
        val split = AnimationEditing.splitTarget(copy(transitions = moved), clip.id, rightId, offset)
        // Groups survive a split; a group that would lose a child is dropped by the sanitizer.
        return CompoundEditing.withVideos(split, result).withSanitizedTransitions()
    }
    fun splitAt(playheadUs: Long): Project = split(indexAt(playheadUs), playheadUs)
    /**
     * Removes one clip from the main sequence. Groups that still describe the remaining clips
     * survive; the transitions that referenced the removed clip are dropped with it.
     */
    fun delete(clipId: String): Project {
        if (videos.none { it.id == clipId }) return this
        return CompoundEditing.withVideos(this, videos.filterNot { it.id == clipId })
    }
    fun changeVideo(index: Int, transform: (VideoClip) -> VideoClip): Project =
        copy(videos = videos.mapIndexed { i, v -> if (i == index) transform(v) else v })
    fun moveVideo(from: Int, to: Int): Project {
        if (from !in videos.indices || to !in videos.indices || from == to) return this
        val list = videos.toMutableList(); list.add(to, list.removeAt(from))
        return CompoundEditing.withVideos(this, list)
    }
}

/** Anti-click fade on the outgoing clip's audio at a transition cut; shared by preview and export. */
const val TRANSITION_AUDIO_TAIL_US = 60_000L

fun TransitionInstance.startUs(project: Project): Long {
    val rightIdx = project.videos.indexOfFirst { it.id == rightClipId }
    return if (rightIdx >= 0) project.startOf(rightIdx) else 0L
}

fun TransitionInstance.endUs(project: Project): Long = startUs(project) + durationUs
