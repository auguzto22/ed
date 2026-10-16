package com.termex.replay15.editor

import com.termex.replay15.editor.ui.SpeedRulerScale
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedRulerScaleTest {
    @Test
    fun marcasPrincipaisFicamComEspacoIgual() {
        val stops = SpeedRulerScale.STOPS
        stops.forEachIndexed { index, speed ->
            assertEquals(index.toFloat() / (stops.size - 1), SpeedRulerScale.fractionOf(speed), 1e-4f)
            assertEquals(speed, SpeedRulerScale.speedAt(index.toFloat() / (stops.size - 1)), 1e-4f)
        }
    }

    @Test
    fun idaEVoltaPreservaVelocidadeArredondada() {
        listOf(.1f, .5f, 1f, 1.5f, 3.7f, 8f, 12.3f, 16f).forEach { speed ->
            assertEquals(speed, SpeedRulerScale.speedAt(SpeedRulerScale.fractionOf(speed)), 1e-4f)
        }
    }

    @Test
    fun foraDaFaixaFicaNosLimites() {
        assertEquals(SpeedRulerScale.STOPS.first(), SpeedRulerScale.speedAt(-1f), 1e-4f)
        assertEquals(SpeedRulerScale.STOPS.last(), SpeedRulerScale.speedAt(2f), 1e-4f)
        assertEquals(0f, SpeedRulerScale.fractionOf(.01f), 1e-4f)
        assertEquals(1f, SpeedRulerScale.fractionOf(99f), 1e-4f)
    }
}
