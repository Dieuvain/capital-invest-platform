package cd.capitalinvest.platform.common

/** « +243 970 000 001 », « 243970000001 », « 0970000001 » ou « 970000001 » vers +243970000001. Null si ce n'est pas un numéro RDC. */
fun normalizeDrcPhone(input: String): String? {
    val digits = input.filter { it.isDigit() }
    return when {
        digits.length == 12 && digits.startsWith("243") -> "+$digits"
        digits.length == 10 && digits.startsWith("0") -> "+243${digits.drop(1)}"
        digits.length == 9 -> "+243$digits"
        else -> null
    }
}
