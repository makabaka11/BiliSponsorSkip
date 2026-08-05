package com.retrsoft.bilisponsorskip

import java.security.SecureRandom

internal object Identity {
    private const val CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun generate(length: Int = 36): String = buildString(length) {
        repeat(length) { append(CHARACTERS[random.nextInt(CHARACTERS.length)]) }
    }

    fun isValid(value: String): Boolean = value.trim().length >= 32
}
