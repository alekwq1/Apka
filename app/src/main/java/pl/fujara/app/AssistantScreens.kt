package pl.fujara.app

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Ekrany 1.1: "Decyzje" (przyjete / odrzucone / wygasle), "Wolt" (zarobki + dostawy)
 * oraz ustawienia asystenta (glos, trasy, pozycja panelu, auto-zapis).
 * Celowo niezalezne od prywatnych helperow MainActivity – jezyk przychodzi jako kod.
 */

internal fun at(language: String, pl: String, en: String, uk: String, ru: String): String = when (language) {
    "en" -> en
    "uk" -> uk
    "ru" -> ru
    else -> pl
}

private val plLocale: Locale = Locale.forLanguageTag("pl-PL")
private fun money(value: Double): String = String.format(plLocale, "%.2f zł", value)
private fun money0(value: Double): String = String.format(plLocale, "%.0f zł", value)
private fun rateH(value: Double?): String = value?.let { String.format(plLocale, "%.0f zł/h", it) } ?: "—"
private fun rateKm(value: Double?): String = value?.let { String.format(plLocale, "%.2f zł/km", it) } ?: "—"
private fun pct(value: Double?): String = value?.let { String.format(plLocale, "%.0f%%", it) } ?: "—"
private fun clockOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM"))

private val greenC = Color(0xFF2FB36B)
private val yellowC = Color(0xFFE2B100)
private val redC = Color(0xFFE0514F)
private val blueC = Color(0xFF5B7CFA)
private val inkC = Color(0xFF191B20)

internal fun platformName(platform: CourierPlatform): String = when (platform) {
    CourierPlatform.GLOBAL -> "Inne"
    CourierPlatform.UBER -> "Uber"
    CourierPlatform.WOLT -> "Wolt"
    CourierPlatform.GLOVO -> "Glovo"
    CourierPlatform.BOLT -> "Bolt"
    CourierPlatform.PYSZNE -> "Pyszne"
    CourierPlatform.STUART -> "Stuart"
}

private fun statusColor(status: ProfitabilityStatus): Color = when (status) {
    ProfitabilityStatus.PROFITABLE -> greenC
    ProfitabilityStatus.ALMOST_PROFITABLE -> yellowC
    ProfitabilityStatus.UNPROFITABLE -> redC
    ProfitabilityStatus.NO_TIME -> Color(0xFFFF9F43)
}

private fun decisionLabel(decision: OfferDecision, language: String): String = when (decision) {
    OfferDecision.ACCEPTED -> at(language, "PRZYJĘTA", "ACCEPTED", "ПРИЙНЯТА", "ПРИНЯТА")
    OfferDecision.REJECTED -> at(language, "ODRZUCONA", "REJECTED", "ВІДХИЛЕНА", "ОТКЛОНЕНА")
    OfferDecision.EXPIRED -> at(language, "WYGASŁA", "EXPIRED", "МИНУЛА", "ИСТЕКЛА")
    OfferDecision.UNKNOWN -> at(language, "?", "?", "?", "?")
}

private fun decisionColor(decision: OfferDecision): Color = when (decision) {
    OfferDecision.ACCEPTED -> greenC
    OfferDecision.REJECTED -> redC
    OfferDecision.EXPIRED -> Color(0xFF8A8F9A)
    OfferDecision.UNKNOWN -> Color(0xFFB0B4BC)
}

// ---------------------------------------------------------------------------------
// Wspolne kafelki
// ---------------------------------------------------------------------------------

@Composable
internal fun AssistantCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            content()
        }
    }
}

@Composable
private fun MiniMetric(modifier: Modifier, label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface, supporting: String? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        if (supporting != null) Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(99.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ChipRow(items: List<Pair<String, Boolean>>, onClick: (Int) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { index, (label, selected) ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { onClick(index) },
                    shape = RoundedCornerShape(17.dp),
                    color = if (selected) inkC else Color.Transparent
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        textAlign = TextAlign.Center,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** Prosty wykres slupkowy: para (pokazane, przyjete) na kazda kolumne. */
@Composable
private fun PairedBarChart(labels: List<String>, totals: List<Int>, accepted: List<Int>, barColor: Color, acceptedColor: Color) {
    val max = (totals.maxOrNull() ?: 0).coerceAtLeast(1)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val count = totals.size.coerceAtLeast(1)
            val slot = size.width / count
            val barWidth = slot * 0.62f
            totals.forEachIndexed { index, total ->
                val x = index * slot + (slot - barWidth) / 2f
                val h = size.height * total / max
                drawRoundRect(barColor.copy(alpha = 0.35f), topLeft = Offset(x, size.height - h), size = Size(barWidth, h), cornerRadius = CornerRadius(6f, 6f))
                val acc = accepted.getOrNull(index) ?: 0
                val ah = size.height * acc / max
                drawRoundRect(acceptedColor, topLeft = Offset(x, size.height - ah), size = Size(barWidth, ah), cornerRadius = CornerRadius(6f, 6f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = textColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun AnalysisModeSelector(mode: Int, language: String, onSelected: (Int) -> Unit) {
    ChipRow(
        items = listOf(
            at(language, "Dni (Pyszne)", "Days (Pyszne)", "Дні (Pyszne)", "Дни (Pyszne)") to (mode == 0),
            at(language, "Decyzje", "Decisions", "Рішення", "Решения") to (mode == 1),
            "Wolt" to (mode == 2)
        ),
        onClick = onSelected
    )
}

// ---------------------------------------------------------------------------------
// CZAS PRACY (Start / Pauza / Stop)
// ---------------------------------------------------------------------------------

@Composable
internal fun WorkShiftCard(language: String) {
    val context = LocalContext.current
    val store = remember { WorkSessionStore(context) }
    var state by remember { mutableStateOf(store.state()) }
    var minutes by remember { mutableStateOf(store.todayMinutes()) }
    LaunchedEffect(state) {
        while (true) {
            minutes = store.todayMinutes()
            delay(if (state == WorkState.RUNNING) 15_000 else 60_000)
        }
    }
    val running = state == WorkState.RUNNING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (running) greenC.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(at(language, "Czas pracy dziś", "Worked today", "Час роботи сьогодні", "Время работы сегодня"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Text(WorkAnalytics.formatHours(minutes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                }
                Pill(
                    when (state) {
                        WorkState.RUNNING -> at(language, "PRACUJĘ", "WORKING", "ПРАЦЮЮ", "РАБОТАЮ")
                        WorkState.PAUSED -> at(language, "PAUZA", "PAUSED", "ПАУЗА", "ПАУЗА")
                        WorkState.STOPPED -> at(language, "STOP", "STOPPED", "СТОП", "СТОП")
                    },
                    when (state) { WorkState.RUNNING -> greenC; WorkState.PAUSED -> yellowC; WorkState.STOPPED -> Color(0xFF8A8F9A) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = { state = store.start(); minutes = store.todayMinutes() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = greenC)) {
                        Text(if (state == WorkState.PAUSED) at(language, "Wznów", "Resume", "Продовжити", "Продолжить") else at(language, "Start", "Start", "Старт", "Старт"), fontWeight = FontWeight.Black)
                    }
                } else {
                    Button(onClick = { state = store.pause(); minutes = store.todayMinutes() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = yellowC)) {
                        Text(at(language, "Pauza", "Pause", "Пауза", "Пауза"), fontWeight = FontWeight.Black)
                    }
                }
                if (state != WorkState.STOPPED) {
                    OutlinedButton(onClick = { state = store.stop(); minutes = store.todayMinutes() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(at(language, "Stop", "Stop", "Стоп", "Стоп"), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                at(language, "Godzinówka z realnego czasu pracy: Analiza → Decyzje.", "Real hourly rate: Analysis → Decisions.", "Реальна погодинна: Аналіз → Рішення.", "Реальная почасовая: Анализ → Решения."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun HourlyReportCard(language: String, from: LocalDate, to: LocalDate, events: List<OfferEvent>, refresh: Int) {
    val context = LocalContext.current
    val workStore = remember { WorkSessionStore(context) }
    val logStore = remember { PyszneLogStore(context) }
    val woltOrders = remember { WoltOrderStore(context) }
    val report = remember(refresh, from, to, events) {
        val pyszne = logStore.all().filter { !it.cancelled }.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.amountPln } }
        HourlyReportCalculator.build(
            from = from, to = to, segments = workStore.segments(), nowMillis = System.currentTimeMillis(),
            pyszneByDate = pyszne, woltOfficialByDate = woltOrders.dayTotals(), events = events
        )
    }
    AssistantCard(title = at(language, "Godzinówka (czas pracy Start/Stop)", "Hourly rate (Start/Stop time)", "Погодинна (Старт/Стоп)", "Почасовая (Старт/Стоп)")) {
        if (report.workedMinutes < 10) {
            Text(
                at(language, "Brak zarejestrowanego czasu pracy w tym okresie. Klikaj Start / Pauza / Stop na ekranie Start.", "No recorded work time in this period. Use Start / Pause / Stop on the Home screen.", "Немає часу роботи. Натискай Старт / Пауза / Стоп.", "Нет времени работы. Нажимай Старт / Пауза / Стоп."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Czas pracy", "Worked", "Час", "Время"), WorkAnalytics.formatHours(report.workedMinutes))
                MiniMetric(Modifier.weight(1f), at(language, "Przychód brutto", "Gross income", "Дохід", "Доход"), money0(report.totalGrossPln), greenC)
                MiniMetric(Modifier.weight(1f), at(language, "PLN/h realnie", "Real PLN/h", "PLN/год", "PLN/ч"), rateH(report.grossPerHour), inkC)
            }
            Text(
                at(
                    language,
                    "Pyszne ${money0(report.pyszneGrossPln)} · Wolt ${money0(report.woltGrossPln)}${if (report.woltIsOfficial) "" else " (szacunek z ofert)"}${if (report.otherGrossPln > 0) " · inne ${money0(report.otherGrossPln)}" else ""} · ofert/h: ${report.offersPerHour?.let { String.format(plLocale, "%.1f", it) } ?: "—"} · przyjętych/h: ${report.acceptedPerHour?.let { String.format(plLocale, "%.1f", it) } ?: "—"}",
                    "Pyszne ${money0(report.pyszneGrossPln)} · Wolt ${money0(report.woltGrossPln)}${if (report.woltIsOfficial) "" else " (offer estimate)"}${if (report.otherGrossPln > 0) " · other ${money0(report.otherGrossPln)}" else ""} · offers/h: ${report.offersPerHour?.let { String.format(plLocale, "%.1f", it) } ?: "—"} · accepted/h: ${report.acceptedPerHour?.let { String.format(plLocale, "%.1f", it) } ?: "—"}",
                    "Pyszne ${money0(report.pyszneGrossPln)} · Wolt ${money0(report.woltGrossPln)}",
                    "Pyszne ${money0(report.pyszneGrossPln)} · Wolt ${money0(report.woltGrossPln)}"
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun exportDecisionsCsv(context: android.content.Context, events: List<OfferEvent>, language: String) {
    val sb = StringBuilder("data;godzina;platforma;decyzja;zrodlo;kwota_pln;netto_pln;km;min;pln_h;pln_km;status;restauracja;odbior;dostawa;w_trakcie\n")
    events.sortedBy { it.shownAtMillis }.forEach { e ->
        fun q(v: String?): String = (v ?: "").replace(";", ",").replace("\n", " ")
        sb.append(e.date).append(';').append(clockOf(e.shownAtMillis)).append(';').append(platformName(e.platform)).append(';')
            .append(e.decision.key).append(';').append(e.decisionSource.key).append(';')
            .append(String.format(Locale.ROOT, "%.2f", e.amountPln)).append(';').append(String.format(Locale.ROOT, "%.2f", e.netPln)).append(';')
            .append(String.format(Locale.ROOT, "%.1f", e.distanceKm)).append(';').append(e.durationMinutes ?: "").append(';')
            .append(e.netPerHour?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "").append(';')
            .append(e.netPerKm?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "").append(';')
            .append(e.status.name).append(';').append(q(e.restaurant)).append(';').append(q(e.pickupAddress)).append(';').append(q(e.dropoffAddress)).append(';')
            .append(if (e.duringActiveJob) 1 else 0).append('\n')
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "FUJARA – decyzje ${LocalDate.now()}")
        putExtra(Intent.EXTRA_TEXT, sb.toString())
    }
    runCatching { context.startActivity(Intent.createChooser(intent, at(language, "Eksport CSV", "Export CSV", "Експорт CSV", "Экспорт CSV")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------------------------------------------------------------------------
// DECYZJE
// ---------------------------------------------------------------------------------

@Composable
internal fun DecisionsAnalysisSection(language: String) {
    val context = LocalContext.current
    val store = remember { OfferEventStore(context) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            refresh += 1
        }
    }
    val all = remember(refresh) { store.all() }
    var rangeIndex by remember { mutableStateOf(1) }
    val today = LocalDate.now()
    val from = when (rangeIndex) {
        0 -> today
        1 -> today.minusDays(6)
        2 -> today.minusDays(29)
        else -> LocalDate.of(2000, 1, 1)
    }
    val events = remember(all, rangeIndex) { DecisionAnalytics.between(all, from, today) }
    val stats = remember(events) { DecisionAnalytics.stats(events) }
    var platformFilter by remember { mutableStateOf<CourierPlatform?>(null) }
    var decisionFilter by remember { mutableStateOf<OfferDecision?>(null) }
    val filtered = remember(events, platformFilter) { platformFilter?.let { p -> events.filter { it.platform == p } } ?: events }
    val filteredStats = remember(filtered) { DecisionAnalytics.stats(filtered) }
    val listed = remember(filtered, decisionFilter) {
        when (decisionFilter) {
            null -> filtered
            OfferDecision.REJECTED -> filtered.filter { it.decision == OfferDecision.REJECTED || it.decision == OfferDecision.EXPIRED }
            else -> filtered.filter { it.decision == decisionFilter }
        }
    }

    ChipRow(
        items = listOf(
            at(language, "Dziś", "Today", "Сьогодні", "Сегодня") to (rangeIndex == 0),
            at(language, "7 dni", "7 days", "7 днів", "7 дней") to (rangeIndex == 1),
            at(language, "30 dni", "30 days", "30 днів", "30 дней") to (rangeIndex == 2),
            at(language, "Wszystko", "All", "Усе", "Всё") to (rangeIndex == 3)
        ),
        onClick = { rangeIndex = it }
    )

    if (events.isEmpty()) {
        AssistantCard(title = at(language, "Brak zapisanych decyzji", "No decisions yet", "Ще немає рішень", "Пока нет решений")) {
            Text(
                at(
                    language,
                    "FUJARA zapisuje każdą pokazaną ofertę i to, co z nią zrobiłeś: Akceptuj / Odrzuć / wygasła. Wystarczy jeździć z włączoną analizą – statystyki pojawią się tutaj.",
                    "FUJARA records every shown offer and what you did with it: Accept / Decline / expired. Just ride with analysis on – stats will appear here.",
                    "FUJARA записує кожну показану пропозицію і твоє рішення.",
                    "FUJARA записывает каждое показанное предложение и твоё решение."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    // Hero
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = inkC)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(at(language, "Decyzje", "Decisions", "Рішення", "Решения"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pct(filteredStats.acceptanceRatePercent), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Text(at(language, "przyjętych", "accepted", "прийнято", "принято"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Pokazane", "Shown", "Показано", "Показано"), "${filteredStats.shown}", Color.White)
                MiniMetric(Modifier.weight(1f), "✓ " + at(language, "Przyjęte", "Accepted", "Прийняті", "Принятые"), "${filteredStats.accepted}", greenC)
                MiniMetric(Modifier.weight(1f), "✗ " + at(language, "Odrzucone", "Declined", "Відхилені", "Отклонённые"), "${filteredStats.rejected}", redC, supporting = at(language, "+${filteredStats.expired} wygasłe", "+${filteredStats.expired} expired", "+${filteredStats.expired} минули", "+${filteredStats.expired} истекли"))
            }
        }
    }

    HourlyReportCard(language = language, from = if (rangeIndex == 3) (all.minOfOrNull { it.date } ?: today) else from, to = today, events = filtered, refresh = refresh)

    // Platform filter
    val platforms = remember(events) { DecisionAnalytics.byPlatform(events) }
    if (platforms.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = platformFilter == null, onClick = { platformFilter = null }, label = { Text(at(language, "Wszystkie", "All", "Усі", "Все")) })
            platforms.forEach { item ->
                FilterChip(
                    selected = platformFilter == item.platform,
                    onClick = { platformFilter = if (platformFilter == item.platform) null else item.platform },
                    label = { Text("${platformName(item.platform)} ${item.stats.shown}") }
                )
            }
        }
    }

    // Accepted vs rejected quality
    AssistantCard(title = at(language, "Co przyjmujesz, a co odrzucasz", "What you accept vs decline", "Що приймаєш, а що відхиляєш", "Что принимаешь, а что отклоняешь")) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniMetric(Modifier.weight(1f), at(language, "PLN/h przyjęte", "PLN/h accepted", "PLN/год прийняті", "PLN/ч принятые"), rateH(filteredStats.avgNetPerHourAccepted), greenC)
            MiniMetric(Modifier.weight(1f), at(language, "PLN/h odrzucone", "PLN/h declined", "PLN/год відхилені", "PLN/ч отклонённые"), rateH(filteredStats.avgNetPerHourRejected), redC)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniMetric(Modifier.weight(1f), at(language, "PLN/km przyjęte", "PLN/km accepted", "PLN/км прийняті", "PLN/км принятые"), rateKm(filteredStats.avgNetPerKmAccepted), greenC)
            MiniMetric(Modifier.weight(1f), at(language, "PLN/km odrzucone", "PLN/km declined", "PLN/км відхилені", "PLN/км отклонённые"), rateKm(filteredStats.avgNetPerKmRejected), redC)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniMetric(Modifier.weight(1f), at(language, "Śr. kwota przyjętej", "Avg accepted amount", "Сер. сума прийнятої", "Ср. сумма принятой"), filteredStats.avgAmountAccepted?.let { money(it) } ?: "—")
            MiniMetric(Modifier.weight(1f), at(language, "Czas decyzji (mediana)", "Decision time (median)", "Час рішення", "Время решения"), filteredStats.medianDecisionSeconds?.let { "$it s" } ?: "—")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val goodShare = filteredStats.acceptedGoodShare
        Text(
            at(
                language,
                "${pct(goodShare)} przyjętych ofert było DOBRYCH wg FUJARY. Przyjąłeś ${filteredStats.acceptedPoorCount} słabych, a odrzuciłeś ${filteredStats.rejectedGoodCount} dobrych (ok. ${money0(filteredStats.rejectedGoodNetTotal)} po kosztach).",
                "${pct(goodShare)} of accepted offers were GOOD per FUJARA. You accepted ${filteredStats.acceptedPoorCount} poor ones and declined ${filteredStats.rejectedGoodCount} good ones (≈${money0(filteredStats.rejectedGoodNetTotal)} net).",
                "${pct(goodShare)} прийнятих були ДОБРИМИ. Прийнято слабких: ${filteredStats.acceptedPoorCount}, відхилено добрих: ${filteredStats.rejectedGoodCount}.",
                "${pct(goodShare)} принятых были ХОРОШИМИ. Принято слабых: ${filteredStats.acceptedPoorCount}, отклонено хороших: ${filteredStats.rejectedGoodCount}."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            at(language, "Suma po kosztach z przyjętych: ${money(filteredStats.acceptedNetTotal)}", "Net total of accepted: ${money(filteredStats.acceptedNetTotal)}", "Разом після витрат: ${money(filteredStats.acceptedNetTotal)}", "Итого после расходов: ${money(filteredStats.acceptedNetTotal)}"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }

    // By hour
    val hours = remember(filtered) { DecisionAnalytics.byHour(filtered) }
    val activeHours = hours.filter { it.shown > 0 }
    if (activeHours.isNotEmpty()) {
        val first = activeHours.first().hour
        val last = activeHours.last().hour
        val slice = hours.filter { it.hour in first..last }
        AssistantCard(title = at(language, "Oferty wg godziny", "Offers by hour", "Пропозиції за годинами", "Предложения по часам")) {
            Text(
                at(language, "Jasny słupek = pokazane, zielony = przyjęte", "Light bar = shown, green = accepted", "Світлий = показані, зелений = прийняті", "Светлый = показанные, зелёный = принятые"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            PairedBarChart(
                labels = slice.map { if (slice.size > 10 && it.hour % 2 == 1) "" else "${it.hour}" },
                totals = slice.map { it.shown },
                accepted = slice.map { it.accepted },
                barColor = blueC,
                acceptedColor = greenC
            )
            val best = activeHours.filter { it.shown >= 2 }.maxByOrNull { it.avgNetPerHourShown ?: 0.0 }
            if (best != null) {
                Text(
                    at(language, "Najlepsze stawki ok. ${best.hour}:00 (${rateH(best.avgNetPerHourShown)} śr. z ofert)", "Best rates around ${best.hour}:00 (${rateH(best.avgNetPerHourShown)} avg offered)", "Найкращі ставки ~${best.hour}:00", "Лучшие ставки ~${best.hour}:00"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Daily series (if range > 1 day)
    if (rangeIndex != 0) {
        val series = remember(filtered, rangeIndex) {
            val start = if (rangeIndex == 3) (filtered.minOfOrNull { it.date } ?: today).coerceAtLeast(today.minusDays(29)) else from
            DecisionAnalytics.dailySeries(filtered, start, today)
        }
        AssistantCard(title = at(language, "Dzień po dniu", "Day by day", "День за днем", "День за днём")) {
            PairedBarChart(
                labels = series.map { if (series.size > 10 && series.indexOf(it) % 3 != 0) "" else shortDate(it.first) },
                totals = series.map { it.second },
                accepted = series.map { it.third },
                barColor = blueC,
                acceptedColor = greenC
            )
        }
    }

    // Restaurants
    val restaurants = remember(filtered) { DecisionAnalytics.byRestaurant(filtered, limit = 8) }
    if (restaurants.isNotEmpty()) {
        AssistantCard(title = at(language, "Restauracje", "Restaurants", "Ресторани", "Рестораны")) {
            restaurants.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.restaurant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            at(language, "${row.shown} ofert · ✓${row.accepted} ✗${row.rejected} · ${rateH(row.avgNetPerHour)}", "${row.shown} offers · ✓${row.accepted} ✗${row.rejected} · ${rateH(row.avgNetPerHour)}", "${row.shown} · ✓${row.accepted} ✗${row.rejected}", "${row.shown} · ✓${row.accepted} ✗${row.rejected}"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Raport odrzuconych: ile, kiedy, za ile
    val rejectedAll = remember(filtered) { filtered.filter { it.decision == OfferDecision.REJECTED || it.decision == OfferDecision.EXPIRED } }
    if (rejectedAll.isNotEmpty()) {
        AssistantCard(title = at(language, "Raport odrzuconych", "Declined report", "Звіт відхилених", "Отчёт отклонённых")) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Odrzucone + wygasłe", "Declined + expired", "Відхилені", "Отклонённые"), "${rejectedAll.size}", redC)
                MiniMetric(Modifier.weight(1f), at(language, "Suma kwot", "Total amount", "Сума", "Сумма"), money0(rejectedAll.sumOf { it.amountPln }))
                MiniMetric(Modifier.weight(1f), at(language, "Śr. PLN/h", "Avg PLN/h", "Сер. PLN/год", "Ср. PLN/ч"), rateH(rejectedAll.mapNotNull { it.netPerHour }.takeIf { it.isNotEmpty() }?.average()))
            }
            val byDay = rejectedAll.groupBy { it.date }.toSortedMap(compareByDescending { it })
            byDay.entries.take(10).forEach { (day, list) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(shortDate(day), modifier = Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(
                        at(language, "${list.size} szt. · ${money0(list.sumOf { it.amountPln })} · godz. ${list.map { it.hourOfDay }.distinct().sorted().joinToString(",")}", "${list.size} pcs · ${money0(list.sumOf { it.amountPln })} · hours ${list.map { it.hourOfDay }.distinct().sorted().joinToString(",")}", "${list.size} · ${money0(list.sumOf { it.amountPln })}", "${list.size} · ${money0(list.sumOf { it.amountPln })}"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    // Lista ofert z filtrem decyzji + eksport
    AssistantCard(title = at(language, "Lista ofert", "Offer list", "Список пропозицій", "Список предложений")) {
        ChipRow(
            items = listOf(
                at(language, "Wszystkie", "All", "Усі", "Все") to (decisionFilter == null),
                "✓ " + at(language, "Przyjęte", "Accepted", "Прийняті", "Принятые") to (decisionFilter == OfferDecision.ACCEPTED),
                "✗ " + at(language, "Odrzucone", "Declined", "Відхилені", "Отклонённые") to (decisionFilter == OfferDecision.REJECTED)
            ),
            onClick = { decisionFilter = when (it) { 1 -> OfferDecision.ACCEPTED; 2 -> OfferDecision.REJECTED; else -> null } }
        )
        listed.sortedByDescending { it.shownAtMillis }.take(40).forEach { event ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(99.dp)).background(statusColor(event.status)))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${shortDate(event.date)} ${clockOf(event.shownAtMillis)} · ${platformName(event.platform)}" + (event.restaurant?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        "${money(event.amountPln)} · ${String.format(plLocale, "%.1f", event.distanceKm)} km · ${rateH(event.netPerHour)} · ${rateKm(event.netPerKm)}" +
                            (if (event.duringActiveJob) at(language, " · w trakcie", " · during job", " · під час", " · во время") else ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                Pill(decisionLabel(event.decision, language), decisionColor(event.decision))
            }
        }
        OutlinedButton(onClick = { exportDecisionsCsv(context, listed, language) }, shape = RoundedCornerShape(14.dp)) {
            Text(at(language, "Eksportuj CSV (${listed.size})", "Export CSV (${listed.size})", "Експорт CSV (${listed.size})", "Экспорт CSV (${listed.size})"))
        }
    }
}

// ---------------------------------------------------------------------------------
// WOLT
// ---------------------------------------------------------------------------------

@Composable
internal fun WoltAnalysisSection(language: String) {
    val context = LocalContext.current
    val eventStore = remember { OfferEventStore(context) }
    val earningsStore = remember { WoltEarningsStore(context) }
    val orderStore = remember { WoltOrderStore(context) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            refresh += 1
        }
    }
    val events = remember(refresh) { eventStore.all().filter { it.platform == CourierPlatform.WOLT } }
    val snapshots = remember(refresh) { earningsStore.all() }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val summary = remember(events, snapshots, date) { WoltDaySummaryCalculator.calculate(date, events, earningsStore) }
    val daySnapshots = remember(snapshots, date) { snapshots.filter { it.date == date } }
    val dayAccepted = remember(events, date) { events.filter { it.date == date && it.decision == OfferDecision.ACCEPTED }.sortedBy { it.shownAtMillis } }
    val officialTotal = remember(refresh, date) { orderStore.dayTotal(date) }
    val officialOrders = remember(refresh, date) { orderStore.ordersForDate(date) }
    val officialTotals = remember(refresh) { orderStore.dayTotals() }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { date = date.minusDays(1) }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        Text(
            date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", plLocale)).replaceFirstChar { it.uppercase() },
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = { date = date.plusDays(1) }, enabled = date < LocalDate.now()) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }

    if (summary.acceptedOrders == 0 && daySnapshots.isEmpty() && summary.shownOffers == 0 && officialTotal == null && officialOrders.isEmpty()) {
        AssistantCard(title = at(language, "Brak danych Wolt tego dnia", "No Wolt data this day", "Немає даних Wolt", "Нет данных Wolt")) {
            Text(
                at(
                    language,
                    "Wolt nie ma ekranu historii zlecenia jak Pyszne, więc FUJARA składa dzień z dwóch źródeł: (1) przyjęte oferty – kwota, km i czas z karty w chwili akceptacji, (2) ekran Wolt → Finanse → „Zarobki brutto”, który wystarczy otworzyć raz na jakiś czas (np. na koniec zmiany), a FUJARA zapisze kwotę i policzy przyrost dnia.",
                    "Wolt has no per-order history screen like Pyszne, so FUJARA builds the day from two sources: (1) accepted offers – amount, km and time from the card at acceptance, (2) Wolt → Finance → “Gross earnings”, which you just open now and then (e.g. end of shift); FUJARA stores the value and computes the day's delta.",
                    "Wolt не має історії замовлень, тому FUJARA складає день з прийнятих пропозицій та екрана Фінанси.",
                    "У Wolt нет истории заказов, поэтому FUJARA собирает день из принятых предложений и экрана Финансы."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = inkC)) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Wolt · " + at(language, "szacunek z przyjętych ofert", "estimate from accepted offers", "оцінка з прийнятих", "оценка из принятых"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
            Text(money(summary.estimatedGrossPln), color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Dostawy", "Deliveries", "Доставки", "Доставки"), "${summary.acceptedOrders}", Color.White, supporting = at(language, "z ${summary.shownOffers} ofert", "of ${summary.shownOffers} offers", "з ${summary.shownOffers}", "из ${summary.shownOffers}"))
                MiniMetric(Modifier.weight(1f), at(language, "Po kosztach", "Net", "Після витрат", "После расходов"), money0(summary.estimatedNetPln), greenC)
                MiniMetric(Modifier.weight(1f), "PLN/h", rateH(summary.netPerHour), Color.White, supporting = "${summary.estimatedMinutes} min")
            }
        }
    }

    if (officialTotal != null || officialOrders.isNotEmpty()) {
        AssistantCard(title = at(language, "Oficjalnie z Wolt (Zarobki brutto → dzień)", "Official from Wolt (gross → day)", "Офіційно з Wolt", "Официально из Wolt")) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Suma dnia wg Wolt", "Day total per Wolt", "Сума дня", "Сумма дня"), officialTotal?.let { money(it) } ?: money(officialOrders.sumOf { it.grossPln }), greenC)
                MiniMetric(Modifier.weight(1f), at(language, "Zlecenia", "Orders", "Замовлення", "Заказы"), "${officialOrders.size}", supporting = officialOrders.mapNotNull { it.distanceKm }.sum().takeIf { it > 0 }?.let { String.format(plLocale, "%.1f km", it) })
                MiniMetric(Modifier.weight(1f), at(language, "Różnica vs szacunek", "Diff vs estimate", "Різниця", "Разница"), officialTotal?.let { String.format(plLocale, "%+.2f zł", it - summary.estimatedGrossPln) } ?: "—")
            }
            if (officialOrders.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                officialOrders.sortedByDescending { it.minuteOfDay }.forEach { order ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(DecisionAnalytics.minuteOfDayLabel(order.minuteOfDay), modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.weight(1f)) {
                            Text(order.venue + (if (order.stops > 1) " · ${order.stops}×" else ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            order.distanceKm?.let { Text(String.format(plLocale, "%.2f km · %.2f zł/km", it, order.grossPln / it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(money(order.grossPln), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Black)
                    }
                }
            }
            Text(
                at(language, "Otwieraj w Wolt: Zarobki brutto → Podsumowanie dzienne → dzień. FUJARA zapisze sumy dni i pojedyncze zlecenia.", "Open in Wolt: Gross earnings → Daily summary → day. FUJARA stores day totals and single orders.", "Відкривай у Wolt: Заробіток → Підсумок дня.", "Открывай в Wolt: Заработок → Сводка дня."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall
            )
        }
    }

    AssistantCard(title = at(language, "Finanse Wolt (Zarobki brutto)", "Wolt Finance (gross)", "Фінанси Wolt", "Финансы Wolt")) {
        if (daySnapshots.isEmpty()) {
            Text(
                at(language, "Otwórz w Wolt zakładkę Finanse – FUJARA sama zapisze „Zarobki brutto”.", "Open Finance in Wolt – FUJARA will store “Gross earnings” automatically.", "Відкрий Фінанси у Wolt.", "Открой Финансы в Wolt."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniMetric(Modifier.weight(1f), at(language, "Ostatni odczyt", "Latest reading", "Останнє", "Последнее"), summary.financeLatestGrossPln?.let { money(it) } ?: "—", supporting = clockOf(daySnapshots.last().capturedAtMillis))
                MiniMetric(Modifier.weight(1f), at(language, "Przyrost dnia", "Day delta", "Приріст дня", "Прирост дня"), summary.financeDeltaPln?.let { money(it) } ?: "—", greenC)
            }
            summary.reconciliationPln?.let { diff ->
                Text(
                    at(
                        language,
                        "Różnica Finanse − szacunek z ofert: ${String.format(plLocale, "%+.2f zł", diff)} (napiwki, bonusy, korekty).",
                        "Finance − offer estimate: ${String.format(plLocale, "%+.2f zł", diff)} (tips, bonuses, adjustments).",
                        "Різниця: ${String.format(plLocale, "%+.2f zł", diff)}",
                        "Разница: ${String.format(plLocale, "%+.2f zł", diff)}"
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            daySnapshots.takeLast(8).forEach { snap ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(clockOf(snap.capturedAtMillis), modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(money(snap.grossPln), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    snap.nextPayoutLabel?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                }
            }
        }
    }

    if (dayAccepted.isNotEmpty()) {
        AssistantCard(title = at(language, "Przyjęte dostawy", "Accepted deliveries", "Прийняті доставки", "Принятые доставки")) {
            dayAccepted.forEach { event ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(99.dp)).background(statusColor(event.status)))
                    Column(Modifier.weight(1f)) {
                        Text("${clockOf(event.shownAtMillis)} · ${event.restaurant ?: "Wolt"}" + (if (event.stops > 1) " · ${event.stops}×" else ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${String.format(plLocale, "%.1f", event.distanceKm)} km · ${event.durationMinutes ?: "?"} min · ${rateH(event.netPerHour)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(money(event.amountPln), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Black)
                }
            }
        }
    }

    // 7-day series
    val week = remember(events, date, officialTotals) {
        (6 downTo 0).map { back ->
            val d = date.minusDays(back.toLong())
            d to (officialTotals[d] ?: events.filter { it.date == d && it.decision == OfferDecision.ACCEPTED }.sumOf { it.amountPln })
        }
    }
    if (week.any { it.second > 0.0 }) {
        AssistantCard(title = at(language, "Ostatnie 7 dni (oficjalne lub szacunek)", "Last 7 days (official or estimate)", "Останні 7 днів", "Последние 7 дней")) {
            PairedBarChart(
                labels = week.map { shortDate(it.first) },
                totals = week.map { it.second.toInt() },
                accepted = week.map { it.second.toInt() },
                barColor = blueC,
                acceptedColor = blueC
            )
            Text(
                at(language, "Suma: ${money(week.sumOf { it.second })}", "Total: ${money(week.sumOf { it.second })}", "Разом: ${money(week.sumOf { it.second })}", "Итого: ${money(week.sumOf { it.second })}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------------------------------------------------------------------------
// WOLT – dzien (do ekranu Dzien i Start, razem z Pyszne)
// ---------------------------------------------------------------------------------

@Composable
internal fun WoltDayCard(language: String, date: LocalDate, refresh: Int, compact: Boolean = false) {
    val context = LocalContext.current
    val orderStore = remember { WoltOrderStore(context) }
    val eventStore = remember { OfferEventStore(context) }
    val orders = remember(refresh, date) { orderStore.mergedForDate(date) }
    val officialTotal = remember(refresh, date) { orderStore.dayTotal(date) }
    val acceptedEvents = remember(refresh, date) { eventStore.forDate(date).filter { it.platform == CourierPlatform.WOLT && it.decision == OfferDecision.ACCEPTED } }
    if (orders.isEmpty() && officialTotal == null && acceptedEvents.isEmpty()) return

    val total = officialTotal ?: orders.sumOf { it.grossPln }.takeIf { it > 0 } ?: acceptedEvents.sumOf { it.amountPln }
    val count = if (orders.isNotEmpty()) orders.size else acceptedEvents.size
    val km = orders.mapNotNull { it.distanceKm }.sum().takeIf { it > 0 } ?: acceptedEvents.sumOf { it.distanceKm }
    val measuredMinutes = orders.mapNotNull { it.durationMinutes }.sum()
    val source = when {
        officialTotal != null -> at(language, "oficjalnie z Wolt", "official from Wolt", "офіційно з Wolt", "официально из Wolt")
        orders.any { it.source == WoltOrder.SOURCE_LIVE } -> at(language, "zmierzone przez FUJARĘ", "measured by FUJARA", "виміряно FUJARA", "измерено FUJARA")
        else -> at(language, "szacunek z przyjętych ofert", "estimate from accepted offers", "оцінка", "оценка")
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF00C2E8).copy(alpha = 0.12f))) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WOLT · " + source, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Text(money(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(at(language, "$count dostaw", "$count deliveries", "$count доставок", "$count доставок"), fontWeight = FontWeight.Bold)
                    Text(String.format(plLocale, "%.1f km", km) + (if (measuredMinutes > 0) " · ${WorkAnalytics.formatHours(measuredMinutes)}" else ""), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!compact && orders.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                orders.sortedByDescending { it.minuteOfDay }.forEach { order ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(DecisionAnalytics.minuteOfDayLabel(order.minuteOfDay), modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.weight(1f)) {
                            Text(order.venue + (if (order.stops > 1) " · ${order.stops}×" else ""), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    order.distanceKm?.let { String.format(plLocale, "%.1f km", it) },
                                    order.durationMinutes?.let { "$it min" },
                                    if (order.source == WoltOrder.SOURCE_LIVE) at(language, "pomiar", "measured", "вимір", "замер") else "Wolt"
                                ).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(money(order.grossPln), fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// USTAWIENIA ASYSTENTA
// ---------------------------------------------------------------------------------

@Composable
internal fun AssistantSettingsSection(prefs: AppPrefs, language: String) {
    val context = LocalContext.current
    var decisionTracking by remember { mutableStateOf(prefs.decisionTrackingEnabled) }
    var showCounter by remember { mutableStateOf(prefs.showDecisionCounter) }
    var autoSave by remember { mutableStateOf(prefs.pyszneAutoSaveEnabled) }
    var woltTracking by remember { mutableStateOf(prefs.woltTrackingEnabled) }
    var voiceEnabled by remember { mutableStateOf(prefs.voiceRejectEnabled) }
    var voiceKeyword by remember { mutableStateOf(prefs.voiceRejectKeyword) }
    var voiceConfirm by remember { mutableStateOf(prefs.voiceRejectConfirmWord) }
    var voiceSpeak by remember { mutableStateOf(prefs.voiceRejectSpeakPrompt) }
    var routesEnabled by remember { mutableStateOf(prefs.routesEnabled) }
    var routeProfile by remember { mutableStateOf(RouteProfile.fromKey(prefs.routeProfile)) }
    var cityHint by remember { mutableStateOf(prefs.routeCityHint) }
    var tomtomKey by remember { mutableStateOf(prefs.tomtomApiKey) }
    var woltOffset by remember { mutableStateOf(prefs.overlayTopOffsetDp(CourierPlatform.WOLT).toFloat()) }
    var micGranted by remember { mutableStateOf(VoicePermissions.recordAudioGranted(context)) }
    var locationGranted by remember { mutableStateOf(VoicePermissions.locationGranted(context)) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) {
            prefs.voiceRejectEnabled = true
            voiceEnabled = true
        } else {
            Toast.makeText(context, at(language, "Bez mikrofonu głosowe odrzucanie nie zadziała", "Voice decline needs the microphone", "Потрібен мікрофон", "Нужен микрофон"), Toast.LENGTH_LONG).show()
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        locationGranted = granted
        if (granted) {
            prefs.routesEnabled = true
            routesEnabled = true
        } else {
            Toast.makeText(context, at(language, "Bez lokalizacji nie policzę dojazdu", "Routes need location", "Потрібна локація", "Нужна геолокация"), Toast.LENGTH_LONG).show()
        }
    }

    AssistantCard(title = at(language, "Decyzje i zapis danych", "Decisions & data capture", "Рішення та дані", "Решения и данные")) {
        AssistantSwitch(at(language, "Zapisuj pokazane oferty i decyzje", "Record shown offers and decisions", "Записувати пропозиції та рішення", "Записывать предложения и решения"), decisionTracking) {
            decisionTracking = it; prefs.decisionTrackingEnabled = it
        }
        Text(
            at(
                language,
                "Akceptuj / Odrzuć są rozpoznawane po kliknięciu przycisku w aplikacji kurierskiej. Jeśli oferta zniknie bez kliknięcia (np. Stuart po 10 s), trafia do „wygasłe”.",
                "Accept / Decline are detected from the button tap in the courier app. An offer that disappears untouched (e.g. Stuart after 10 s) is logged as “expired”.",
                "Прийняти / Відхилити розпізнаються за натисканням кнопки.",
                "Принять / Отклонить распознаются по нажатию кнопки."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        AssistantSwitch(at(language, "Licznik ✓/✗ dnia w panelu", "Today's ✓/✗ counter in the panel", "Лічильник ✓/✗ у панелі", "Счётчик ✓/✗ в панели"), showCounter) {
            showCounter = it; prefs.showDecisionCounter = it
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        AssistantSwitch(at(language, "Pyszne: automatyczny zapis zakończonych zleceń", "Pyszne: auto-save finished orders", "Pyszne: автозбереження", "Pyszne: автосохранение"), autoSave) {
            autoSave = it; prefs.pyszneAutoSaveEnabled = it
        }
        Text(
            at(
                language,
                "Wystarczy otworzyć szczegóły zlecenia w historii Pyszne – FUJARA zapisze je sama, bez klikania ZAPISZ (ta sama ochrona przed duplikatami). Pyszne nie ma publicznego API dla kurierów, więc to najszybsza możliwa automatyzacja.",
                "Just open an order's details in Pyszne history – FUJARA saves it without tapping SAVE (same duplicate protection). Pyszne has no public courier API, so this is the fastest automation possible.",
                "Достатньо відкрити деталі замовлення в історії Pyszne.",
                "Достаточно открыть детали заказа в истории Pyszne."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        AssistantSwitch(at(language, "Wolt: zapisuj „Zarobki brutto” z ekranu Finanse", "Wolt: capture “Gross earnings” from Finance", "Wolt: зберігати Фінанси", "Wolt: сохранять Финансы"), woltTracking) {
            woltTracking = it; prefs.woltTrackingEnabled = it
        }
    }

    AssistantCard(title = at(language, "Odrzucanie głosem", "Voice decline", "Відхилення голосом", "Отклонение голосом")) {
        AssistantSwitch(at(language, "Włącz hasło głosowe", "Enable voice keyword", "Увімкнути голосове слово", "Включить голосовое слово"), voiceEnabled) { wanted ->
            if (wanted && !micGranted) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                voiceEnabled = wanted
                prefs.voiceRejectEnabled = wanted
            }
        }
        Text(
            at(
                language,
                "Gdy widać kartę oferty, FUJARA słucha hasła. Po usłyszeniu pyta „Na pewno odrzucić?” i dopiero po słowie potwierdzającym klika Odrzuć w Wolt / Pyszne. „Nie” lub cisza anuluje. Nasłuch działa tylko przy widocznej ofercie; niektóre telefony wydają krótki dźwięk przy starcie rozpoznawania.",
                "While an offer card is visible, FUJARA listens for the keyword, then asks “Really decline?” and taps Decline in Wolt / Pyszne only after the confirmation word. “No” or silence cancels. Listening runs only while an offer is visible; some phones beep when recognition starts.",
                "Коли видно картку пропозиції, FUJARA слухає слово, питає підтвердження і лише тоді натискає Відхилити.",
                "Когда видна карточка предложения, FUJARA слушает слово, спрашивает подтверждение и только тогда нажимает Отклонить."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = voiceKeyword,
            onValueChange = { voiceKeyword = it; prefs.voiceRejectKeyword = it },
            label = { Text(at(language, "Hasło (np. odrzuć)", "Keyword (e.g. decline)", "Слово", "Слово")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = voiceConfirm,
            onValueChange = { voiceConfirm = it; prefs.voiceRejectConfirmWord = it },
            label = { Text(at(language, "Potwierdzenie (np. odrzucam; „tak” też działa)", "Confirmation (e.g. confirm; “yes” also works)", "Підтвердження", "Подтверждение")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        var voiceConfirmRequired by remember { mutableStateOf(prefs.voiceRejectRequireConfirmation) }
        AssistantSwitch(at(language, "Pytaj o potwierdzenie (domyślnie: hasło = odrzucenie od razu)", "Ask for confirmation (default: keyword declines immediately)", "Питати підтвердження", "Спрашивать подтверждение"), voiceConfirmRequired) {
            voiceConfirmRequired = it; prefs.voiceRejectRequireConfirmation = it
        }
        AssistantSwitch(at(language, "Pytaj głosem (TTS)", "Ask aloud (TTS)", "Питати голосом", "Спрашивать голосом"), voiceSpeak) {
            voiceSpeak = it; prefs.voiceRejectSpeakPrompt = it
        }
        Text(
            at(
                language,
                "Radio: systemowy rozpoznawacz mowy Androida przejmuje mikrofon i tzw. audio focus, więc na czas nasłuchu odtwarzacz/radio jest wyciszane lub pauzowane – tego nie da się wyłączyć z poziomu aplikacji. Nasłuch trwa tylko, gdy widać kartę oferty, więc przerwa jest krótka. Pełne nasłuchiwanie w tle bez przerywania muzyki wymagałoby własnego silnika słów-kluczy (offline), to osobna, większa funkcja.",
                "Radio: Android's system speech recognizer takes the mic and audio focus, so media is ducked/paused while listening – apps cannot turn this off. Listening runs only while an offer card is visible, so the interruption is short.",
                "Радіо: системний розпізнавач бере аудіофокус, тому музика ставиться на паузу під час прослуховування.",
                "Радио: системный распознаватель берёт аудиофокус, поэтому музыка ставится на паузу во время прослушивания."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        if (!micGranted) {
            Text(at(language, "Brak zgody na mikrofon.", "Microphone permission missing.", "Немає дозволу на мікрофон.", "Нет разрешения на микрофон."), color = redC, style = MaterialTheme.typography.labelMedium)
        }
    }

    AssistantCard(title = at(language, "Trasa i „czy zdążę”", "Route & “will I make it”", "Маршрут", "Маршрут")) {
        AssistantSwitch(at(language, "Licz dojazd do odbioru (lokalizacja + internet)", "Compute travel to pickup (location + internet)", "Рахувати дорогу (локація + інтернет)", "Считать дорогу (геолокация + интернет)"), routesEnabled) { wanted ->
            if (wanted && !locationGranted) {
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                routesEnabled = wanted
                prefs.routesEnabled = wanted
            }
        }
        Text(
            at(
                language,
                "Przy nowej ofercie panel pokaże: km i minuty z Twojej pozycji do odbioru, dojazd po zakończeniu bieżącego zlecenia i ocenę „zdążysz / na styk / kolizja” względem godziny odbioru. Adresy są geokodowane (system / OpenStreetMap). Bez klucza TomTom trasa liczona jest przez OSRM bez ruchu (z zapasem miejskim +25%). Z darmowym kluczem TomTom czas uwzględnia aktualny ruch. Wysyłane są tylko adresy i współrzędne – nic więcej.",
                "On a new offer the panel shows km/minutes from your position to pickup, travel after your current job ends, and a “ok / tight / conflict” verdict versus the pickup time. Addresses are geocoded (system / OpenStreetMap). Without a TomTom key, OSRM routes without traffic (+25% city buffer). With a free TomTom key, live traffic is included. Only addresses and coordinates are sent.",
                "Панель покаже дорогу до забору та оцінку «встигнеш / на межі / конфлікт».",
                "Панель покажет дорогу до забора и оценку «успеешь / впритык / конфликт»."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteProfile.entries.forEach { profile ->
                FilterChip(
                    selected = routeProfile == profile,
                    onClick = { routeProfile = profile; prefs.routeProfile = profile.key },
                    label = {
                        Text(
                            when (profile) {
                                RouteProfile.CAR -> at(language, "Auto", "Car", "Авто", "Авто")
                                RouteProfile.SCOOTER -> at(language, "Skuter", "Scooter", "Скутер", "Скутер")
                                RouteProfile.BIKE -> at(language, "Rower", "Bike", "Велосипед", "Велосипед")
                            }
                        )
                    }
                )
            }
        }
        var woltExpand by remember { mutableStateOf(prefs.woltAutoExpandOffer) }
        AssistantSwitch(at(language, "Wolt: sam rozwiń kartę oferty, żeby odczytać adresy", "Wolt: auto-expand offer card to read addresses", "Wolt: авто-розгортання картки", "Wolt: авто-раскрытие карточки"), woltExpand) {
            woltExpand = it; prefs.woltAutoExpandOffer = it
        }
        var autoDismiss by remember { mutableStateOf(prefs.autoDismissAfterReject) }
        AssistantSwitch(at(language, "Po odrzuceniu głosem zamknij pozostały arkusz gestem", "After voice decline, swipe away leftover sheet", "Після відхилення закрити аркуш жестом", "После отказа закрыть лист жестом"), autoDismiss) {
            autoDismiss = it; prefs.autoDismissAfterReject = it
        }
        var mapTiles by remember { mutableStateOf(prefs.mapTilesEnabled) }
        AssistantSwitch(at(language, "Mini-mapa w panelu z podkładem OpenStreetMap", "Mini-map in panel with OpenStreetMap tiles", "Міні-мапа з OSM", "Мини-карта с OSM"), mapTiles) {
            mapTiles = it; prefs.mapTilesEnabled = it
        }
        Text(
            at(
                language,
                "Przy nowej ofercie panel pokazuje duży werdykt PO DRODZE / MAŁY OBJAZD / W PRZECIWNĄ STRONĘ (objazd w km liczony względem WSZYSTKICH aktywnych zleceń – Pyszne i Wolt naraz) oraz mapę: niebieski = Ty, pomarańczowe A/B = odbiór/dostawa bieżącego, zielone 1/2 = odbiór/dostawa nowej oferty.",
                "On a new offer the panel shows a big ON THE WAY / SMALL DETOUR / WRONG DIRECTION verdict (detour km vs ALL active jobs – Pyszne and Wolt together) plus a map: blue = you, orange A/B = current pickup/drop, green 1/2 = new pickup/drop.",
                "Панель показує великий вердикт ПО ДОРОЗІ / ОБ'ЇЗД / ПРОТИЛЕЖНИЙ БІК та мапу.",
                "Панель показывает крупный вердикт ПО ПУТИ / ОБЪЕЗД / В ДРУГУЮ СТОРОНУ и карту."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = cityHint,
            onValueChange = { cityHint = it; prefs.routeCityHint = it },
            label = { Text(at(language, "Miasto do adresów (np. Gdańsk)", "City for addresses (e.g. Gdańsk)", "Місто", "Город")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tomtomKey,
            onValueChange = { tomtomKey = it; prefs.tomtomApiKey = it },
            label = { Text(at(language, "Klucz TomTom (opcjonalnie, ruch drogowy)", "TomTom key (optional, live traffic)", "Ключ TomTom (опційно)", "Ключ TomTom (опционально)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!locationGranted) {
            Text(at(language, "Brak zgody na lokalizację.", "Location permission missing.", "Немає дозволу на локацію.", "Нет разрешения на геолокацию."), color = redC, style = MaterialTheme.typography.labelMedium)
        }
    }

    AssistantCard(title = at(language, "Pozycja panelu", "Panel position", "Позиція панелі", "Позиция панели")) {
        Text(
            at(
                language,
                "Panel można przeciągnąć palcem za uchwyt „−” (w górę/dół i na drugą stronę ekranu); pozycja jest zapamiętywana osobno dla każdej aplikacji. W Wolt panel domyślnie stoi niżej, żeby nie zasłaniać przycisku „Odrzuć”.",
                "Drag the panel by the “−” handle (up/down and to the other side); the position is remembered per app. In Wolt the panel sits lower by default so it does not cover the “Decline” button.",
                "Панель можна перетягнути за «−»; позиція запам'ятовується для кожного застосунку.",
                "Панель можно перетащить за «−»; позиция запоминается для каждого приложения."
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        Text(at(language, "Wolt – odstęp od góry: ${woltOffset.toInt()} dp", "Wolt – top offset: ${woltOffset.toInt()} dp", "Wolt – відступ зверху: ${woltOffset.toInt()} dp", "Wolt – отступ сверху: ${woltOffset.toInt()} dp"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Slider(
            value = woltOffset,
            onValueChange = { woltOffset = it },
            onValueChangeFinished = { prefs.setOverlayTopOffsetDp(CourierPlatform.WOLT, woltOffset.toInt()) },
            valueRange = 40f..400f
        )
        OutlinedButton(onClick = {
            prefs.resetOverlayPositions()
            woltOffset = prefs.overlayTopOffsetDp(CourierPlatform.WOLT).toFloat()
            Toast.makeText(context, at(language, "Pozycje panelu zresetowane", "Panel positions reset", "Скинуто", "Сброшено"), Toast.LENGTH_SHORT).show()
        }, shape = RoundedCornerShape(14.dp)) {
            Text(at(language, "Przywróć domyślne pozycje", "Reset positions", "Скинути позиції", "Сбросить позиции"))
        }
    }

    AssistantCard(title = at(language, "Dane asystenta", "Assistant data", "Дані асистента", "Данные ассистента")) {
        var confirmClear by remember { mutableStateOf(false) }
        Text(
            at(language, "Decyzje i snapshoty Wolt są zapisywane lokalnie i wchodzą do kopii zapasowej JSON.", "Decisions and Wolt snapshots are stored locally and included in the JSON backup.", "Зберігаються локально.", "Хранятся локально."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
        )
        if (!confirmClear) {
            OutlinedButton(onClick = { confirmClear = true }, shape = RoundedCornerShape(14.dp)) {
                Text(at(language, "Usuń historię decyzji i dane Wolt", "Delete decisions & Wolt data", "Видалити рішення і дані Wolt", "Удалить решения и данные Wolt"))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    OfferEventStore(context).clear()
                    WoltEarningsStore(context).clear()
                    WoltOrderStore(context).clear()
                    confirmClear = false
                    Toast.makeText(context, at(language, "Usunięto", "Deleted", "Видалено", "Удалено"), Toast.LENGTH_SHORT).show()
                }, shape = RoundedCornerShape(14.dp)) { Text(at(language, "Tak, usuń", "Yes, delete", "Так, видалити", "Да, удалить")) }
                TextButton(onClick = { confirmClear = false }) { Text(at(language, "Anuluj", "Cancel", "Скасувати", "Отмена")) }
            }
        }
    }
}

@Composable
private fun AssistantSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
