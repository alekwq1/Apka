package pl.fujara.app

data class Offer(
    val amountPln: Double,
    val distanceKm: Double,
    val durationMinutes: Int? = null,
    /** Dokladny czas aktywnosci z ekranow podsumowania, np. 10 min 7 sec. */
    val durationSeconds: Int? = null,
    /** False dla historii zlecenia: rzeczywisty czas nie powinien dostawac zapasu z ustawien. */
    val applyExtraTimeBuffer: Boolean = true,
    val pickupTimeMinutesOfDay: Int? = null,
    val deliveryTimeMinutesOfDay: Int? = null
)

enum class DurationSource {
    DIRECT_TOTAL,
    PLANNED_DELIVERY,
    UNKNOWN
}

enum class ProfitabilityStatus {
    PROFITABLE,
    ALMOST_PROFITABLE,
    UNPROFITABLE,
    NO_TIME
}

data class Profitability(
    val grossPln: Double,
    /** Kwota po odjeciu ustawionego procentu ZUS, ale przed kosztem pojazdu. */
    val afterZusPln: Double,
    /** Realny wynik uzywany do stawek: po ZUS (jesli wlaczony) i po koszcie pojazdu. */
    val netPln: Double,
    val distanceKm: Double,
    /** Calkowity czas uzyty do kalkulacji, lacznie z zapasem uzytkownika. */
    val durationMinutes: Int?,
    val extraTimeMinutes: Int,
    val zusPercent: Double,
    val netPerKm: Double?,
    val netPerHour: Double?,
    val profitable: Boolean?,
    val status: ProfitabilityStatus,
    val pickupTimeMinutesOfDay: Int?,
    val deliveryTimeMinutesOfDay: Int?,
    val durationSource: DurationSource
)

data class BlacklistHits(
    val restaurant: String? = null,
    val customer: String? = null
) {
    val hasAny: Boolean
        get() = restaurant != null || customer != null
}
