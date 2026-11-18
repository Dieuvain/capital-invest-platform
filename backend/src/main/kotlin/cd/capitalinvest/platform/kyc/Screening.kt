package cd.capitalinvest.platform.kyc

import org.springframework.stereotype.Service
import java.text.Normalizer
import java.time.LocalDate

data class ScreeningResult(val hit: Boolean, val detail: String?)

/**
 * Contrôle des listes de sanctions (ONU, OFAC, UE) et des personnes politiquement exposées.
 * Le fournisseur réel reste à choisir ; le simulateur compare le nom à une liste configurée.
 */
interface SanctionsScreening {
    fun screen(fullName: String, birthDate: LocalDate): ScreeningResult
}

@Service
class SimulatedSanctionsScreening(props: KycProperties) : SanctionsScreening {
    private val watchlist = props.simulatedWatchlist.map(::normalize).toSet()

    override fun screen(fullName: String, birthDate: LocalDate): ScreeningResult {
        val name = normalize(fullName)
        return if (name in watchlist) ScreeningResult(true, "Correspondance exacte sur la liste simulée") else ScreeningResult(false, null)
    }

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z ]"), " ").split(" ").filter { it.isNotBlank() }.sorted().joinToString(" ")
}
