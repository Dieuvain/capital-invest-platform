package cd.capitalinvest.platform.auth

import cd.capitalinvest.platform.common.BusinessException

object PinPolicy {
    /** 4 chiffres, ni tous identiques (1111), ni une suite (1234, 4321). */
    fun validate(pin: String) {
        if (!Regex("^[0-9]{4}$").matches(pin)) throw BusinessException("PIN_FORMAT", "Le PIN doit contenir 4 chiffres")
        val d = pin.map { it - '0' }
        val allSame = d.all { it == d[0] }
        val ascending = d.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = d.zipWithNext().all { (a, b) -> b == a - 1 }
        if (allSame || ascending || descending) throw BusinessException("PIN_TOO_SIMPLE", "Ce PIN est trop facile à deviner")
    }
}
