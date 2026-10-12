package com.termex.replay15.account

import android.content.Context
import android.graphics.Typeface

/** Tipografia da tela de conta: fonte do sistema com o peso pedido (sem depender de fontes empacotadas). */
object ReclyType {
    private val cache = HashMap<Int, Typeface>()
    fun body(context: Context, weight: Int = 400): Typeface = synchronized(cache) {
        cache.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }
    }
    fun display(context: Context): Typeface = body(context, 800)
}
