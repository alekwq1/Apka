package pl.fujara.app

import java.util.Locale

/** Co widac na ekranie aplikacji kurierskiej – potrzebne do decyzji i aktywnego zlecenia. */
enum class ScreenKind {
    /** Karta nowej oferty z przyciskiem Akceptuj / Zaakceptuj zlecenie / Jestem zainteresowany. */
    OFFER_CARD,
    /** Aktywne zlecenie: Odbierz na / Dostarcz na / Odbiór za N min / Dostawa do … bez przycisku akceptacji. */
    ACTIVE_JOB,
    /** Szczegoly zakonczonego zlecenia (historia Pyszne). */
    HISTORY_DETAILS,
    OTHER
}

object ScreenClassifier {
    private val acceptMarker = Regex(
        """(?i)(?<!\p{L})(?:akceptuj|zaakceptuj|accept|jestem\s+zainteresowany|przyjmij|прийняти|принять)(?!\p{L})"""
    )
    private val countdownMarker = Regex("""(?i)sekund\s+do\s+automatycznego\s+odrzucenia|seconds?\s+(?:to|until)\s+auto|(?<!\d)0:[0-5]\d(?!\d)""")
    private val navigateMarker = Regex("""(?i)(?<!\p{L})(?:nawiguj|navigate|навігувати|навигация)(?!\p{L})""")
    private val scheduleMarker = Regex("""(?i)(?:odbierz|dostarcz|pickup|deliver)\w*\s+(?:na|by|at|do)\s+\d{1,2}[:.]\d{2}""")
    private val etaMarker = Regex("""(?i)(?:odbi[oó]r|dostawa|pickup|delivery)\s+(?:za|in)\s+-?\d{1,3}\s*min""")
    private val historyMarker = Regex("""(?i)szczeg[oó][lł]y\s+zlecenia|order\s+details|zlecenie\s+dostarczone|zlecenie\s+anulowane|czas\s+aktywno[sś]ci""")
    private val inProgressMarker = Regex("""(?i)zako[nń]cz\s+odbi[oó]r|dostarcz\s+zam[oó]wienie|odbi[oó]r\s+z(?!\p{L})|dostawa\s+do(?!\p{L})|zam[oó]wienie\s+dostarczone|w\s+drodze|rozpocznij\s+odbi[oó]r|zad[zź]wo[nń]\s+do\s+klienta""")
    private val offerOnlyMarker = Regex("""(?i)spodziewany\s+zarobek|szac\.?\s*zarobki|szacowany\s+zarobek|expected\s+earnings""")

    private val pickupSchedule = Regex("""(?i)odbierz\s+na\s+\d{1,2}[:.]\d{2}""")
    private val deliverySchedule = Regex("""(?i)dostarcz\s+na\s+\d{1,2}[:.]\d{2}""")
    private val rejectMarker = Regex("""(?i)(?<!\p{L})odrzu[cć](?!\p{L})""")
    private val pyszneOfferMarker = Regex("""(?i)dostawa\s+do\s+klienta|przystank|odbi[oó]r\s*:""")

    fun classify(text: String): ScreenKind {
        if (text.isBlank()) return ScreenKind.OTHER
        if (historyMarker.containsMatchIn(text) && !acceptMarker.containsMatchIn(text)) return ScreenKind.HISTORY_DETAILS
        if (acceptMarker.containsMatchIn(text) || countdownMarker.containsMatchIn(text) || offerOnlyMarker.containsMatchIn(text)) return ScreenKind.OFFER_CARD
        // Karta oferty Pyszne ma OBA terminy (Odbierz na + Dostarcz na) i "X Odrzuć";
        // ekran aktywnego zlecenia Pyszne pokazuje tylko jeden z nich. Czesciowy odczyt
        // karty bez przycisku "Zaakceptuj" nie moze tworzyc widmowego aktywnego zlecenia.
        if (pickupSchedule.containsMatchIn(text) && deliverySchedule.containsMatchIn(text)) return ScreenKind.OFFER_CARD
        if (rejectMarker.containsMatchIn(text) && pyszneOfferMarker.containsMatchIn(text)) return ScreenKind.OFFER_CARD
        val activeSignal = scheduleMarker.containsMatchIn(text) || etaMarker.containsMatchIn(text) || inProgressMarker.containsMatchIn(text)
        if (activeSignal && (navigateMarker.containsMatchIn(text) || etaMarker.containsMatchIn(text) || inProgressMarker.containsMatchIn(text))) {
            return ScreenKind.ACTIVE_JOB
        }
        return ScreenKind.OTHER
    }
}

/** Jedna linia wygladajaca jak adres: "Hynka 71B, 80-465 Gdańsk", "Sympatyczna 20/4, 80-176 Gdańsk". */
object AddressLines {
    private val postal = Regex("""\d{2}-\d{3}""")
    private val streetLike = Regex("""^(?:ul\.?\s+|al\.?\s+|pl\.?\s+)?[\p{L}][\p{L}\s.'\-]{2,50}\s\d{1,4}[a-zA-Z]?(?:/\d{1,4})?(?:\s+lok\.?\s*\d+)?$""")

    fun isAddress(line: String): Boolean {
        val t = line.trim()
        if (t.length !in 5..90) return false
        if (postal.containsMatchIn(t)) return true
        return streetLike.matches(t)
    }

    /** Usuwa dopiski typu "4,21 km" na koncu linii Stuart. */
    fun clean(line: String): String = line.replace(Regex("""\s+\d+[,.]\d+\s*km$"""), "").trim().trimEnd(',')
}

/** Adresy i nazwa lokalu z karty Pyszne (oferta lub aktywne zlecenie). */
data class PyszneOfferDetails(
    val restaurant: String?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val stops: Int
)

object PyszneOfferDetailsParser {
    private val pickupLabel = Regex("""(?i)^(?:odbi[oó]r|pickup)\s*[:\-]?\s*(.*)$""")
    private val dropoffLabel = Regex("""(?i)^(?:dostawa\s+do\s+klienta|dostawa|delivery|dostarcz\s+do)\s*[:\-]?\s*(.*)$""")
    private val stopsMarker = Regex("""(?i)(\d)\s*przystank""")
    private val timeLine = Regex("""(?i)^(?:odbierz|dostarcz)\w*\s+(?:na|do)\s+\d""")
    private val noise = Regex("""(?i)^(?:nawiguj|odrzu[cć]|zaakceptuj|akceptuj|notatki|uwagi|zam[oó]wienie|\d+\s*zam[oó]wie|zako[nń]cz|dostarcz\s+zam|\d+[,.]\d+\s*(?:zł|km))""")

    fun parse(text: String): PyszneOfferDetails {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        var restaurant: String? = null
        var pickupAddress: String? = null
        var dropoffAddress: String? = null

        lines.forEachIndexed { index, line ->
            if (restaurant == null) {
                pickupLabel.find(line)?.let { match ->
                    val inline = match.groupValues[1].trim()
                    val following = lines.drop(index + 1).take(4)
                    val candidates = (if (inline.isNotBlank()) listOf(inline) else emptyList()) + following
                    restaurant = candidates.firstOrNull { c ->
                        !AddressLines.isAddress(c) && !timeLine.containsMatchIn(c) && !noise.containsMatchIn(c) && c.length in 2..80
                    }
                    pickupAddress = candidates.firstOrNull { AddressLines.isAddress(it) && !timeLine.containsMatchIn(it) }
                }
            }
            if (dropoffAddress == null) {
                dropoffLabel.find(line)?.let {
                    dropoffAddress = lines.drop(index + 1).take(3).firstOrNull { AddressLines.isAddress(it) && !timeLine.containsMatchIn(it) }
                }
            }
        }

        val stops = stopsMarker.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 1
        return PyszneOfferDetails(
            restaurant = restaurant?.takeIf { PyszneHistoryParser.normalizeRestaurant(it).isNotBlank() },
            pickupAddress = pickupAddress,
            dropoffAddress = dropoffAddress,
            stops = stops
        )
    }
}

/**
 * Ekran aktywnego zlecenia Pyszne:
 *  "Odbierz na 16:15 / Istanbul Döner Kebap / Hynka 71B, 80-465 Gdańsk / 1 zamówienie /
 *   Natalia L. / Zamówienie #6FG63W / Bydgoska 4, 80-394 Gdańsk / Zakończ odbiór"
 *  "Dostarcz na 16:02 / Profesora … / Natalia / … / Dostarcz zamówienie"
 */
data class PyszneActiveScreen(
    val pickupMinuteOfDay: Int?,
    val deliveryMinuteOfDay: Int?,
    val restaurant: String?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val pickedUp: Boolean
)

object PyszneActiveScreenParser {
    private val pickupAt = Regex("""(?i)odbierz\s+na\s+(\d{1,2})[:.](\d{2})""")
    private val deliverAt = Regex("""(?i)dostarcz\s+na\s+(\d{1,2})[:.](\d{2})""")
    private val orderMarker = Regex("""(?i)zam[oó]wienie\s*#""")
    private val pickedUpMarker = Regex("""(?i)dostarcz\s+zam[oó]wienie""")
    private val skip = Regex("""(?i)^(?:nawiguj|\d+\s*zam[oó]wieni|notatki|uwagi|zako[nń]cz|dostarcz|dostawa|odbierz|odbi[oó]r|odrzu[cć]|zam[oó]wienie|\d+[,.]\d+\s*(?:zł|km))""")

    fun parse(text: String): PyszneActiveScreen? {
        if (ScreenClassifier.classify(text) != ScreenKind.ACTIVE_JOB) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val pickup = pickupAt.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val delivery = deliverAt.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val anchor = lines.indexOfFirst { pickupAt.containsMatchIn(it) || deliverAt.containsMatchIn(it) }
        val pickedUp = pickup == null && (delivery != null || pickedUpMarker.containsMatchIn(text))
        var restaurant: String? = null
        var pickupAddress: String? = null
        var dropoffAddress: String? = null

        val after = if (anchor >= 0) lines.drop(anchor + 1) else lines
        val firstAddressIdx = after.indexOfFirst { AddressLines.isAddress(it) }
        if (!pickedUp) {
            restaurant = after.take(if (firstAddressIdx >= 0) firstAddressIdx else 3).firstOrNull { !skip.containsMatchIn(it) && it.length in 2..80 }
            pickupAddress = after.getOrNull(firstAddressIdx)
            val orderIdx = after.indexOfFirst { orderMarker.containsMatchIn(it) }
            dropoffAddress = if (orderIdx >= 0) {
                after.drop(orderIdx + 1).take(3).firstOrNull { AddressLines.isAddress(it) }
            } else {
                after.drop(firstAddressIdx + 1).firstOrNull { AddressLines.isAddress(it) }
            }
        } else {
            dropoffAddress = after.getOrNull(firstAddressIdx)
                ?: after.take(4).joinToString(" ").takeIf { AddressLines.isAddress(it) }
        }
        return PyszneActiveScreen(pickup, delivery, restaurant, pickupAddress, dropoffAddress, pickedUp)
    }
}

/**
 * Aktywne zlecenie kuriera (po akceptacji). Osobno dla kazdej platformy, bo
 * kurier jezdzi na Pyszne i Wolt rownoczesnie.
 */
data class ActiveJob(
    val platform: CourierPlatform,
    val acceptedAtMillis: Long,
    val restaurant: String?,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val pickupMinuteOfDay: Int?,
    val deliveryMinuteOfDay: Int?,
    /** Szacowany czas pelnej dostawy z oferty (Wolt/Stuart/Uber), gdy brak godzin. */
    val estimatedMinutes: Int?,
    val distanceKm: Double,
    val amountPln: Double,
    val stops: Int = 1,
    val stage: Stage = Stage.TO_PICKUP,
    val lastSeenAtMillis: Long = acceptedAtMillis,
    /** "Odbiór za 7 min" / "Dostawa za 24 min" z Wolt – ETA w minutach od [lastSeenAtMillis]. */
    val etaMinutes: Int? = null,
    /** Moment odebrania (klikniecie "Zakończ odbiór" / "Rozpocznij odbiór"). */
    val pickedUpAtMillis: Long? = null
) {
    enum class Stage { TO_PICKUP, TO_CUSTOMER }

    /** Minuta dnia, o ktorej powinienem skonczyc biezace zlecenie. */
    fun expectedFinishMinuteOfDay(currentMinuteOfDay: Int): Int {
        deliveryMinuteOfDay?.let { return normalizeAfter(it, currentMinuteOfDay) }
        if (etaMinutes != null && stage == Stage.TO_CUSTOMER) {
            val seen = DecisionAnalytics.localDateTime(lastSeenAtMillis)
            return seen.hour * 60 + seen.minute + etaMinutes
        }
        val startMinute = acceptedMinuteOfDay()
        val minutes = estimatedMinutes ?: (distanceKm * 3.2 + 8).toInt().coerceIn(8, 90)
        return startMinute + minutes
    }

    fun acceptedMinuteOfDay(): Int {
        val local = DecisionAnalytics.localDateTime(acceptedAtMillis)
        return local.hour * 60 + local.minute
    }

    /** Punkty, ktore jeszcze musze odwiedzic (adres, czyOdbior) w kolejnosci. */
    fun remainingAddresses(): List<Pair<String, Boolean>> = buildList {
        if (stage == Stage.TO_PICKUP) (pickupAddress ?: restaurant)?.let { add(it to true) }
        dropoffAddress?.let { add(it to false) }
    }

    val label: String
        get() = restaurant ?: pickupAddress ?: platform.key.replaceFirstChar { it.uppercase() }

    private fun normalizeAfter(minute: Int, now: Int): Int = if (minute < now - 12 * 60) minute + 24 * 60 else minute
}

class ActiveJobTracker(
    /** Po tylu minutach bez sygnalu zlecenie uznajemy za zakonczone. */
    private val staleAfterMs: Long = 35 * 60_000L
) {
    private val jobs = java.util.concurrent.ConcurrentHashMap<CourierPlatform, ActiveJob>()

    fun onAccepted(
        platform: CourierPlatform,
        offer: Offer,
        nowMillis: Long,
        restaurant: String?,
        pickupAddress: String?,
        dropoffAddress: String?,
        stops: Int
    ) {
        jobs[platform] = ActiveJob(
            platform = platform,
            acceptedAtMillis = nowMillis,
            restaurant = restaurant,
            pickupAddress = pickupAddress,
            dropoffAddress = dropoffAddress,
            pickupMinuteOfDay = offer.pickupTimeMinutesOfDay,
            deliveryMinuteOfDay = offer.deliveryTimeMinutesOfDay,
            estimatedMinutes = offer.durationMinutes,
            distanceKm = offer.distanceKm,
            amountPln = offer.amountPln,
            stops = stops
        )
    }

    /** Ekran aktywnego zlecenia potwierdza, ze zlecenie trwa; doprecyzowuje adresy i etap. */
    fun onActiveScreen(
        platform: CourierPlatform,
        nowMillis: Long,
        pickupMinute: Int?,
        deliveryMinute: Int?,
        restaurant: String?,
        pickedUp: Boolean,
        pickupAddress: String? = null,
        dropoffAddress: String? = null,
        etaMinutes: Int? = null
    ) {
        val existing = jobs[platform]?.takeIf { nowMillis - it.lastSeenAtMillis <= staleAfterMs }
        // Inna restauracja niz w zapamietanym zleceniu = nowe zlecenie tej platformy.
        val sameJob = existing != null && (
            restaurant == null || existing.restaurant == null ||
                PyszneHistoryParser.normalizeRestaurant(restaurant) == PyszneHistoryParser.normalizeRestaurant(existing.restaurant)
            )
        if (existing == null || !sameJob) {
            jobs[platform] = ActiveJob(
                platform = platform,
                acceptedAtMillis = nowMillis,
                restaurant = restaurant,
                pickupAddress = pickupAddress,
                dropoffAddress = dropoffAddress,
                pickupMinuteOfDay = pickupMinute,
                deliveryMinuteOfDay = deliveryMinute,
                estimatedMinutes = null,
                distanceKm = 0.0,
                amountPln = 0.0,
                stage = if (pickedUp) ActiveJob.Stage.TO_CUSTOMER else ActiveJob.Stage.TO_PICKUP,
                lastSeenAtMillis = nowMillis,
                etaMinutes = etaMinutes
            )
            return
        }
        jobs[platform] = existing.copy(
            pickupMinuteOfDay = pickupMinute ?: existing.pickupMinuteOfDay,
            deliveryMinuteOfDay = deliveryMinute ?: existing.deliveryMinuteOfDay,
            restaurant = existing.restaurant ?: restaurant,
            pickupAddress = existing.pickupAddress ?: pickupAddress,
            dropoffAddress = existing.dropoffAddress ?: dropoffAddress,
            stage = if (pickedUp) ActiveJob.Stage.TO_CUSTOMER else existing.stage,
            lastSeenAtMillis = nowMillis,
            etaMinutes = etaMinutes ?: existing.etaMinutes
        )
    }

    /** Klikniecie "Zakończ odbiór" / "Rozpocznij odbiór" / "Odebrano". */
    fun onPickedUp(platform: CourierPlatform, nowMillis: Long) {
        val job = jobs[platform] ?: return
        jobs[platform] = job.copy(stage = ActiveJob.Stage.TO_CUSTOMER, lastSeenAtMillis = nowMillis, pickedUpAtMillis = job.pickedUpAtMillis ?: nowMillis)
    }

    /** Klikniecie "Dostarcz zamówienie" / "Zamówienie dostarczone" / "Odrzuć dostawę". Zwraca zakonczone zlecenie. */
    fun onFinished(platform: CourierPlatform): ActiveJob? = jobs.remove(platform)

    /** Uzupelnia zlecenie danymi z zaakceptowanej oferty (kwota, km, czas, adresy), gdy ich brakuje. */
    fun enrichFromOffer(platform: CourierPlatform, event: OfferEvent) {
        val job = jobs[platform] ?: return
        jobs[platform] = job.copy(
            amountPln = if (job.amountPln > 0.0) job.amountPln else event.amountPln,
            distanceKm = if (job.distanceKm > 0.0) job.distanceKm else event.distanceKm,
            estimatedMinutes = job.estimatedMinutes ?: event.durationMinutes,
            restaurant = job.restaurant ?: event.restaurant,
            pickupAddress = job.pickupAddress ?: event.pickupAddress,
            dropoffAddress = job.dropoffAddress ?: event.dropoffAddress,
            pickupMinuteOfDay = job.pickupMinuteOfDay ?: event.pickupMinuteOfDay,
            deliveryMinuteOfDay = job.deliveryMinuteOfDay ?: event.deliveryMinuteOfDay,
            stops = maxOf(job.stops, event.stops),
            acceptedAtMillis = minOf(job.acceptedAtMillis, event.decidedAtMillis ?: job.acceptedAtMillis)
        )
    }

    /** Przywrocenie po restarcie uslugi (znaczniki czasu zapisane trwale). */
    fun restore(saved: List<ActiveJob>, nowMillis: Long) {
        saved.filter { nowMillis - it.lastSeenAtMillis <= staleAfterMs }.forEach { jobs[it.platform] = it }
    }

    fun snapshot(): List<ActiveJob> = jobs.values.toList()

    fun activeJobs(nowMillis: Long): List<ActiveJob> {
        jobs.entries.removeIf { nowMillis - it.value.lastSeenAtMillis > staleAfterMs }
        return jobs.values.sortedBy { it.acceptedAtMillis }
    }

    fun activeAt(nowMillis: Long, platform: CourierPlatform? = null): ActiveJob? {
        val list = activeJobs(nowMillis)
        return if (platform == null) list.firstOrNull() else list.firstOrNull { it.platform == platform }
    }

    fun clear() = jobs.clear()
}

/** Etykiety przyciskow zmieniajacych etap zlecenia. */
object JobStageButtons {
    private val pickedUp = Regex("""(?i)^(?:zako[nń]cz\s+odbi[oó]r|rozpocznij\s+odbi[oó]r|odbierz\s+zam[oó]wieni[ae]|odebrano|picked\s+up|odbierz)$""")
    private val delivered = Regex("""(?i)^(?:dostarcz\s+zam[oó]wienie|zam[oó]wienie\s+dostarczone|dostarczono|delivered|zako[nń]cz\s+dostaw[eę])$""")
    private val cancelled = Regex("""(?i)^(?:odrzu[cć]\s+dostaw[eę]|anuluj\s+dostaw[eę]|cancel\s+delivery)$""")

    fun isPickedUp(text: String): Boolean = pickedUp.matches(text.trim())
    fun isDelivered(text: String): Boolean = delivered.matches(text.trim())
    /** Zrzucenie przyjetej dostawy – zlecenie znika, ale NIE jest zrealizowane. */
    fun isCancelled(text: String): Boolean = cancelled.matches(text.trim())
}

/** Ocena czasowa nowej oferty w trakcie aktywnego zlecenia. */
data class OfferFeasibility(
    val verdict: Verdict,
    /** Minuty zapasu (dodatnie) albo spoznienia (ujemne) wzgledem odbioru nowej oferty. */
    val slackMinutes: Int?,
    val currentFinishMinuteOfDay: Int,
    val newPickupMinuteOfDay: Int?,
    val note: String
) {
    enum class Verdict { OK, TIGHT, CONFLICT, UNKNOWN }
}

object OfferFeasibilityCalculator {
    fun evaluate(
        job: ActiveJob,
        newOffer: Offer,
        currentMinuteOfDay: Int,
        travelToNewPickupMinutes: Int?,
        language: String = "pl"
    ): OfferFeasibility {
        val finish = job.expectedFinishMinuteOfDay(currentMinuteOfDay)
        val newPickup = newOffer.pickupTimeMinutesOfDay?.let { if (it < currentMinuteOfDay - 12 * 60) it + 1440 else it }
        val travel = travelToNewPickupMinutes ?: 6

        if (newPickup != null) {
            val slack = newPickup - (finish + travel)
            val verdict = when {
                slack >= 3 -> OfferFeasibility.Verdict.OK
                slack >= -4 -> OfferFeasibility.Verdict.TIGHT
                else -> OfferFeasibility.Verdict.CONFLICT
            }
            val note = when (verdict) {
                OfferFeasibility.Verdict.OK -> tr(language,
                    "Czas OK: koniec ~${clock(finish)}, odbiór ${clock(newPickup)} (+$slack min)",
                    "Time OK: done ~${clock(finish)}, pickup ${clock(newPickup)} (+$slack min)",
                    "Час OK: ~${clock(finish)}, забір ${clock(newPickup)} (+$slack хв)",
                    "Время OK: ~${clock(finish)}, забор ${clock(newPickup)} (+$slack мин)")
                OfferFeasibility.Verdict.TIGHT -> tr(language,
                    "Czas na styk: koniec ~${clock(finish)}, odbiór ${clock(newPickup)} (${slack} min)",
                    "Tight: done ~${clock(finish)}, pickup ${clock(newPickup)} (${slack} min)",
                    "На межі: ~${clock(finish)}, забір ${clock(newPickup)} (${slack} хв)",
                    "Впритык: ~${clock(finish)}, забор ${clock(newPickup)} (${slack} мин)")
                else -> tr(language,
                    "Odbiór ${clock(newPickup)} wypada przed końcem bieżącego ~${clock(finish)} (${slack} min)",
                    "Pickup ${clock(newPickup)} falls before current ends ~${clock(finish)} (${slack} min)",
                    "Забір ${clock(newPickup)} до кінця поточного ~${clock(finish)}",
                    "Забор ${clock(newPickup)} до конца текущего ~${clock(finish)}")
            }
            return OfferFeasibility(verdict, slack, finish, newPickup, note)
        }

        val remaining = finish - currentMinuteOfDay
        val verdict = when {
            remaining <= 4 -> OfferFeasibility.Verdict.OK
            remaining <= 12 -> OfferFeasibility.Verdict.TIGHT
            else -> OfferFeasibility.Verdict.CONFLICT
        }
        val note = when (verdict) {
            OfferFeasibility.Verdict.OK -> tr(language,
                "Bieżące kończysz za ~$remaining min",
                "Current job ends in ~$remaining min",
                "Поточне закінчиш за ~$remaining хв",
                "Текущее закончишь через ~$remaining мин")
            OfferFeasibility.Verdict.TIGHT -> tr(language,
                "Bieżące jeszcze ~$remaining min; nowa dojdzie do kolejki (${newOffer.durationMinutes ?: "?"} min)",
                "Current still ~$remaining min; new one queues (${newOffer.durationMinutes ?: "?"} min)",
                "Поточне ще ~$remaining хв; нове в черзі",
                "Текущее ещё ~$remaining мин; новое в очереди")
            else -> tr(language,
                "Bieżące jeszcze ~$remaining min – nowe się opóźni",
                "Current still ~$remaining min – new job will be delayed",
                "Поточне ще ~$remaining хв – нове запізниться",
                "Текущее ещё ~$remaining мин – новое задержится")
        }
        return OfferFeasibility(verdict, -remaining, finish, null, note)
    }

    private fun clock(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", (minute / 60) % 24, minute % 60)

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
        "en" -> en
        "uk" -> uk
        "ru" -> ru
        else -> pl
    }
}

// ---------------------------------------------------------------------------------
// Analiza przestrzenna: "po drodze" czy "w przeciwna strone"
// ---------------------------------------------------------------------------------

data class MapPoint(
    val point: GeoPoint,
    val label: String,
    val kind: Kind
) {
    enum class Kind { ME, ACTIVE_PICKUP, ACTIVE_DROP, NEW_PICKUP, NEW_DROP }
}

/** Wynik porownania nowej oferty z trasa biezacego zlecenia (km po drogach ≈ prosta × 1,3). */
data class SpatialVerdict(
    val verdict: Verdict,
    /** Ile km dokladam ponad to, co i tak musze przejechac dla nowej oferty. */
    val detourKm: Double,
    /** Trasa bez nowej oferty (od mojej pozycji przez pozostale punkty biezacego). */
    val baseKm: Double,
    /** Trasa z nowa oferta wpleciona w najlepszym miejscu. */
    val combinedKm: Double,
    /** Odleglosc od mojej pozycji do nowego odbioru. */
    val toNewPickupKm: Double?,
    /** Odleglosc od punktu koncowego biezacego do nowego odbioru. */
    val fromActiveEndKm: Double?,
    val note: String
) {
    enum class Verdict { ON_THE_WAY, SMALL_DETOUR, OPPOSITE, UNKNOWN }
}

object SpatialAdvisor {
    private const val ROAD_FACTOR = 1.3

    fun km(a: GeoPoint, b: GeoPoint): Double = Geo.haversineKm(a, b) * ROAD_FACTOR

    private fun pathKm(points: List<GeoPoint>): Double = points.zipWithNext().sumOf { (a, b) -> km(a, b) }

    /**
     * @param me aktualna pozycja (moze byc null – wtedy start = pierwszy punkt biezacego)
     * @param activeRemaining punkty biezacych zlecen w kolejnosci (odbior jesli jeszcze nie odebrane, potem dostawa)
     * @param newPickup odbior nowej oferty (wymagany)
     * @param newDrop dostawa nowej oferty (Wolt czesto nie pokazuje – wtedy null)
     */
    fun evaluate(
        me: GeoPoint?,
        activeRemaining: List<GeoPoint>,
        newPickup: GeoPoint,
        newDrop: GeoPoint?,
        language: String = "pl"
    ): SpatialVerdict {
        val start = me ?: activeRemaining.firstOrNull() ?: newPickup
        val base = pathKm(listOf(start) + activeRemaining)
        val newLeg = listOfNotNull(newPickup, newDrop)
        val toNewPickup = me?.let { km(it, newPickup) }
        val fromActiveEnd = activeRemaining.lastOrNull()?.let { km(it, newPickup) }

        if (activeRemaining.isEmpty()) {
            val note = toNewPickup?.let {
                tr(language, "Do odbioru ${fmt(it)} km (brak aktywnego zlecenia)", "To pickup ${fmt(it)} km (no active job)", "До забору ${fmt(it)} км", "До забора ${fmt(it)} км")
            } ?: tr(language, "Brak aktywnego zlecenia", "No active job", "Немає активного", "Нет активного")
            return SpatialVerdict(SpatialVerdict.Verdict.UNKNOWN, 0.0, 0.0, pathKm(listOf(start) + newLeg), toNewPickup, null, note)
        }

        // Wplatamy nowy odcinek (odbior → dostawa) w kazde mozliwe miejsce trasy i bierzemy najkrotsza.
        var best = Double.MAX_VALUE
        for (insertAt in 0..activeRemaining.size) {
            val seq = listOf(start) + activeRemaining.take(insertAt) + newLeg + activeRemaining.drop(insertAt)
            best = minOf(best, pathKm(seq))
        }
        val detour = (best - base).coerceAtLeast(0.0)
        val newOwnKm = if (newLeg.size == 2) pathKm(newLeg) else 0.0
        val extra = (detour - newOwnKm).coerceAtLeast(0.0)
        val verdict = when {
            extra <= 1.5 -> SpatialVerdict.Verdict.ON_THE_WAY
            extra <= 4.0 -> SpatialVerdict.Verdict.SMALL_DETOUR
            else -> SpatialVerdict.Verdict.OPPOSITE
        }
        val note = when (verdict) {
            SpatialVerdict.Verdict.ON_THE_WAY -> tr(language,
                "PO DRODZE: +${fmt(extra)} km objazdu (razem ${fmt(best)} km)",
                "ON THE WAY: +${fmt(extra)} km detour (total ${fmt(best)} km)",
                "ПО ДОРОЗІ: +${fmt(extra)} км", "ПО ПУТИ: +${fmt(extra)} км")
            SpatialVerdict.Verdict.SMALL_DETOUR -> tr(language,
                "MAŁY OBJAZD: +${fmt(extra)} km (razem ${fmt(best)} km zamiast ${fmt(base)})",
                "SMALL DETOUR: +${fmt(extra)} km (total ${fmt(best)} km vs ${fmt(base)})",
                "НЕВЕЛИКИЙ ОБ'ЇЗД: +${fmt(extra)} км", "НЕБОЛЬШОЙ ОБЪЕЗД: +${fmt(extra)} км")
            SpatialVerdict.Verdict.OPPOSITE -> tr(language,
                "W PRZECIWNĄ STRONĘ: +${fmt(extra)} km objazdu (razem ${fmt(best)} km zamiast ${fmt(base)})",
                "WRONG DIRECTION: +${fmt(extra)} km detour (total ${fmt(best)} km vs ${fmt(base)})",
                "У ПРОТИЛЕЖНИЙ БІК: +${fmt(extra)} км", "В ДРУГУЮ СТОРОНУ: +${fmt(extra)} км")
            else -> ""
        }
        return SpatialVerdict(verdict, extra, base, best, toNewPickup, fromActiveEnd, note)
    }

    private fun fmt(v: Double): String = String.format(Locale.forLanguageTag("pl-PL"), "%.1f", v)

    private fun tr(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
        "en" -> en
        "uk" -> uk
        "ru" -> ru
        else -> pl
    }
}
