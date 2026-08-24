package pl.fujara.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPrefs

    private var serviceEnabled by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var analysisEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs(this)
        refreshSystemState()

        setContent {
            var languageCode by remember { mutableStateOf(prefs.languageCode) }
            var themeMode by remember { mutableStateOf(prefs.themeMode) }
            var screen by remember {
                mutableStateOf(
                    when {
                        prefs.onboardingCompleted -> AppScreen.HOME
                        prefs.privacyAccepted -> AppScreen.SETUP
                        else -> AppScreen.PRIVACY
                    }
                )
            }

            val language = AppLanguage.fromCode(languageCode)

            val systemDark = isSystemInDarkTheme()
            val useDark = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            val colors = if (useDark) {
                darkColorScheme(
                    primary = Color(0xFF7CE38B),
                    onPrimary = Color(0xFF06210C),
                    secondary = Color(0xFFFFD667),
                    tertiary = Color(0xFFFF7B73),
                    background = Color(0xFF0C1115),
                    onBackground = Color(0xFFF4F7F8),
                    surface = Color(0xFF131A20),
                    onSurface = Color(0xFFF4F7F8),
                    surfaceVariant = Color(0xFF1B242C),
                    onSurfaceVariant = Color(0xFFB6C0C9),
                    outline = Color(0xFF35424C)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF176A35),
                    onPrimary = Color.White,
                    secondary = Color(0xFF7B5E00),
                    tertiary = Color(0xFFB3261E),
                    background = Color(0xFFF6F8F7),
                    onBackground = Color(0xFF17201A),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF17201A),
                    surfaceVariant = Color(0xFFEDF2EE),
                    onSurfaceVariant = Color(0xFF536159),
                    outline = Color(0xFFC7D0C9)
                )
            }

            MaterialTheme(colorScheme = colors) {
                SideEffect {
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !useDark
                        isAppearanceLightNavigationBars = !useDark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        AppScreen.PRIVACY -> PrivacyScreen(
                            language = language,
                            onLanguageChanged = { selected ->
                                prefs.languageCode = selected.code
                                languageCode = selected.code
                            },
                            onCancel = { finish() },
                            onAccept = {
                                prefs.privacyAccepted = true
                                screen = AppScreen.SETUP
                            }
                        )

                        AppScreen.SETUP -> SetupScreen(
                            language = language,
                            serviceEnabled = serviceEnabled,
                            batteryUnrestricted = batteryUnrestricted,
                            isSamsung = Build.MANUFACTURER.contains("samsung", ignoreCase = true),
                            onAccessibility = { openAccessibilitySettings() },
                            onAppInfo = { openAppInfo() },
                            onBatterySettings = { openBatterySettings() },
                            onContinue = {
                                if (serviceEnabled) {
                                    prefs.onboardingCompleted = true
                                    prefs.analysisEnabled = true
                                    analysisEnabled = true
                                    requestNotificationPermissionIfNeeded()
                                    screen = AppScreen.HOME
                                }
                            },
                            onBack = if (prefs.onboardingCompleted) {
                                { screen = AppScreen.HOME }
                            } else {
                                null
                            }
                        )

                        AppScreen.HOME -> HomeScreen(
                            language = language,
                            serviceEnabled = serviceEnabled,
                            analysisEnabled = analysisEnabled,
                            onToggle = {
                                if (!serviceEnabled) {
                                    screen = AppScreen.SETUP
                                } else {
                                    val newValue = !analysisEnabled
                                    prefs.analysisEnabled = newValue
                                    analysisEnabled = newValue
                                }
                            },
                            onSettings = { screen = AppScreen.SETTINGS },
                            onSetup = { screen = AppScreen.SETUP },
                            onPyszneSummary = { screen = AppScreen.PYSZNE_SUMMARY }
                        )

                        AppScreen.PYSZNE_SUMMARY -> PyszneSummaryScreen(
                            prefs = prefs,
                            language = language,
                            onBack = { screen = AppScreen.HOME }
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            prefs = prefs,
                            language = language,
                            onLanguageChanged = {
                                languageCode = it.code
                            },
                            onThemeChanged = { selected ->
                                prefs.themeMode = selected
                                themeMode = selected
                            },
                            onBack = { screen = AppScreen.HOME }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            refreshSystemState()
            if (prefs.onboardingCompleted && serviceEnabled) {
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.notificationPermissionRequested) return

        prefs.notificationPermissionRequested = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7811)
    }

    private fun refreshSystemState() {
        serviceEnabled = isAccessibilityServiceEnabled(this)
        analysisEnabled = prefs.analysisEnabled
        batteryUnrestricted = isBatteryUnrestricted(this)
    }

    private fun openAccessibilitySettings() {
        // Publiczne API Androida otwiera ekran Dostepnosci, ale nie daje aplikacji
        // kontrolowanego sposobu podswietlenia konkretnej pozycji w Ustawieniach.
        // Pokazujemy wiec krotka instrukcje, ktora pozostaje widoczna po przejsciu
        // do Ustawien. Jest to stabilniejsze niz nieudokumentowane extra producentow.
        val hint = when (prefs.languageCode) {
            "en" -> "Tap Installed apps -> FUJARA -> turn the service on"
            "uk" -> "Натисніть Встановлені застосунки -> FUJARA -> увімкніть службу"
            "ru" -> "Нажмите Установленные приложения -> FUJARA -> включите службу"
            else -> "Kliknij Zainstalowane aplikacje -> FUJARA -> Włącz"
        }
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()

        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { openAppInfo() }
    }

    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { openAppInfo() }
    }
}

private enum class AppScreen {
    PRIVACY,
    SETUP,
    HOME,
    PYSZNE_SUMMARY,
    SETTINGS
}

private enum class AppLanguage(
    val code: String,
    val label: String
) {
    PL("pl", "Polski"),
    UK("uk", "Українська"),
    EN("en", "English"),
    RU("ru", "Русский");

    companion object {
        fun fromCode(code: String): AppLanguage = values().firstOrNull { it.code == code } ?: PL
    }
}

@Composable
private fun PrivacyScreen(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onCancel: () -> Unit,
    onAccept: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    ScreenContainer(scrollable = true) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppLanguage.values().forEach { item ->
                FilterChip(
                    selected = item == language,
                    onClick = { onLanguageChanged(item) },
                    label = { Text(item.label) }
                )
            }
        }

        Text(
            text = tx(language, "FUJARA potrzebuje dostępu do ekranu", "FUJARA needs screen access", "FUJARA потребує доступу до екрана", "FUJARA нужен доступ к экрану"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = tx(language, "Zanim włączysz usługę dostępności, sprawdź dokładnie do czego będzie używana.", "Before enabling Accessibility, please review exactly what it is used for.", "Перед увімкненням служби спеціальних можливостей перевірте, для чого вона використовується.", "Перед включением службы специальных возможностей проверьте, для чего она используется."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        InfoCard(
            title = tx(language, "Dlaczego FUJARA używa AccessibilityService", "Why FUJARA uses AccessibilityService", "Навіщо FUJARA використовує AccessibilityService", "Зачем FUJARA использует AccessibilityService"),
            body = tx(
                language,
                "FUJARA używa systemowej usługi dostępności Android (AccessibilityService), aby rozpoznać widoczną kartę oferty z obsługiwanej aplikacji kurierskiej i wyświetlić nad nią obliczenie opłacalności. Odczytuje z oferty kwotę, dystans, czas oraz planowaną godzinę odbioru/dostawy, jeśli jest dostępna. Gdy oferta jest pływającą kartą nad innym ekranem, zrzut może obejmować także tło, ale dane spoza rozpoznanej oferty nie są dodawane do historii.\n\nNa ekranie historii Pyszne użytkownik może sam nacisnąć „ZAPISZ DANE”. Wtedy FUJARA zapisuje lokalnie tylko datę, nazwę restauracji/punktu odbioru, kwotę, dystans, czas aktywności i techniczny hash do blokowania duplikatów. Nie zapisuje pełnego OCR, screenshotu ani adresu klienta. Gdy otworzysz w Pyszne „Podsumowanie dnia”, FUJARA może lokalnie zapamiętać datę, liczbę zleceń i łączną kwotę wyłącznie do kontroli kompletności logu. Log można usunąć w aplikacji.\n\nWszystkie obliczenia są wykonywane lokalnie na telefonie. FUJARA nie klika, nie przyjmuje ani nie odrzuca zleceń za Ciebie.",
                "FUJARA uses Android's AccessibilityService to recognize a visible offer card from a supported courier app and show a profitability calculation above it. It reads the amount, distance, duration and planned pickup/delivery time when available. If the offer is a floating card over another screen, the screenshot can include the background, but content outside the recognized offer is not added to history.\n\nOn a Pyszne order-history screen the user may explicitly tap “SAVE DATA”. FUJARA then stores locally only the date, restaurant/pickup name, amount, distance, active time and a technical hash used to prevent duplicates. It does not store the full OCR text, screenshot or customer address. When you open Pyszne’s daily summary, FUJARA may locally cache the date, order count and total amount only to verify log completeness. The local log can be deleted in the app.\n\nAll calculations are performed locally on the phone. FUJARA does not click, accept or reject jobs for you.",
                "FUJARA використовує AccessibilityService для локального розпізнавання видимої пропозиції. На екрані історії Pyszne користувач може сам натиснути «ЗБЕРЕГТИ ДАНІ»; тоді локально зберігаються лише дата, ресторан/точка отримання, сума, відстань, активний час і технічний hash для блокування дублікатів. Повний OCR, screenshot та адреса клієнта не зберігаються. На екрані підсумку дня Pyszne локально може зберігатися лише дата, кількість замовлень і загальна сума для перевірки повноти. FUJARA не натискає кнопки та не приймає чи відхиляє замовлення за вас.",
                "FUJARA использует AccessibilityService для локального распознавания видимого предложения. На экране истории Pyszne пользователь может сам нажать «СОХРАНИТЬ ДАННЫЕ»; тогда локально сохраняются только дата, ресторан/точка получения, сумма, расстояние, активное время и технический hash для блокировки дублей. Полный OCR, screenshot и адрес клиента не сохраняются. На экране итогов дня Pyszne локально могут сохраняться только дата, количество заказов и общая сумма для проверки полноты. FUJARA не нажимает кнопки и не принимает или отклоняет заказы за вас."
            )
        )

        InfoCard(
            title = tx(language, "Czego aplikacja nie robi", "What the app does not do", "Чого застосунок не робить", "Чего приложение не делает"),
            body = tx(
                language,
                "• nie klika i nie przyjmuje zleceń za Ciebie\n• nie wysyła odczytanych ofert na serwer\n• nie używa danych do reklam ani profilowania\n• obliczenia wykonywane są lokalnie na telefonie",
                "• it does not click or accept jobs for you\n• it does not send read offers to a server\n• it does not use data for advertising or profiling\n• calculations are performed locally on the phone",
                "• не натискає кнопки й не приймає замовлення за вас\n• не надсилає прочитані пропозиції на сервер\n• не використовує дані для реклами чи профілювання\n• обчислення виконуються локально на телефоні",
                "• не нажимает кнопки и не принимает заказы за вас\n• не отправляет прочитанные предложения на сервер\n• не использует данные для рекламы или профилирования\n• вычисления выполняются локально на телефоне"
            )
        )

        TextButton(
            onClick = { uriHandler.openUri("https://alekwq1.github.io/Apka/privacy.html") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tx(language, "Polityka prywatności", "Privacy policy", "Політика конфіденційності", "Политика конфиденциальности"))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text(tx(language, "Anuluj", "Cancel", "Скасувати", "Отмена"))
            }

            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f)
            ) {
                Text(tx(language, "Rozumiem", "I understand", "Розумію", "Понимаю"))
            }
        }
    }
}

@Composable
private fun SetupScreen(
    language: AppLanguage,
    serviceEnabled: Boolean,
    batteryUnrestricted: Boolean,
    isSamsung: Boolean,
    onAccessibility: () -> Unit,
    onAppInfo: () -> Unit,
    onBatterySettings: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?
) {
    ScreenContainer(scrollable = true) {
        TopBar(title = "FUJARA", onBack = onBack)

        BrandEyebrow(tx(language, "KONFIGURACJA", "SETUP", "НАЛАШТУВАННЯ", "НАСТРОЙКА"))
        Text(
            text = tx(language, "3 kroki i możesz jechać", "3 steps and you are ready", "3 кроки — і можна їхати", "3 шага — и можно ехать"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black
        )
        Text(
            text = tx(language, "FUJARA potrzebuje tylko dostępu niezbędnego do odczytania widocznej oferty. Reszta dzieje się lokalnie na telefonie.", "FUJARA only needs access required to read the visible offer. Everything else happens locally on your phone.", "FUJARA потрібен лише доступ для читання видимої пропозиції. Решта відбувається локально на телефоні.", "FUJARA нужен только доступ для чтения видимого предложения. Остальное происходит локально на телефоне."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SetupProgress(serviceEnabled = serviceEnabled, batteryUnrestricted = batteryUnrestricted)

        SetupStepCard(
            step = "01",
            ok = serviceEnabled,
            title = tx(language, "Włącz odczyt oferty", "Enable offer reading", "Увімкніть читання пропозиції", "Включите чтение предложения"),
            body = tx(language, "Otwórz Ustawienia → Dostępność → Zainstalowane aplikacje i włącz FUJARA.", "Open Settings → Accessibility → Installed apps and enable FUJARA.", "Відкрийте Налаштування → Спеціальні можливості → Встановлені застосунки та увімкніть FUJARA.", "Откройте Настройки → Специальные возможности → Установленные приложения и включите FUJARA."),
            button = if (serviceEnabled) null else tx(language, "Otwórz dostępność", "Open Accessibility", "Відкрити доступність", "Открыть доступность"),
            onClick = onAccessibility
        )

        SetupStepCard(
            step = "02",
            ok = serviceEnabled,
            title = tx(language, "Nakładka jest gotowa", "Overlay is ready", "Накладка готова", "Накладка готова"),
            body = if (serviceEnabled) {
                tx(language, "Panel FUJARY pokaże się w prawym górnym rogu razem z ofertą i zniknie razem z nią. Nie przechwytuje dotyku.", "The FUJARA panel appears in the top-right with the offer and disappears with it. It does not intercept touches.", "Панель FUJARA з’являється праворуч угорі разом із пропозицією та зникає разом із нею.", "Панель FUJARA появляется справа вверху вместе с предложением и исчезает вместе с ним.")
            } else {
                tx(language, "Ten krok aktywuje się automatycznie po włączeniu dostępu do ekranu.", "This step activates automatically after screen access is enabled.", "Цей крок активується автоматично після надання доступу до екрана.", "Этот шаг активируется автоматически после включения доступа к экрану.")
            },
            button = null,
            onClick = {}
        )

        SetupStepCard(
            step = "03",
            ok = batteryUnrestricted,
            warning = !batteryUnrestricted,
            title = tx(language, "Pozwól działać w tle", "Allow background activity", "Дозвольте роботу у фоні", "Разрешите работу в фоне"),
            body = if (batteryUnrestricted) {
                tx(language, "Gotowe. System nie powinien agresywnie usypiać FUJARY.", "Done. The system should not aggressively put FUJARA to sleep.", "Готово. Система не повинна агресивно присипляти FUJARA.", "Готово. Система не должна агрессивно усыплять FUJARA.")
            } else {
                tx(language, "Zalecane: Informacje o aplikacji → Bateria → Bez ograniczeń. To ogranicza znikanie usługi podczas jazdy.", "Recommended: App info → Battery → Unrestricted. This reduces the chance of the service stopping while you deliver.", "Рекомендовано: Інформація про застосунок → Батарея → Без обмежень.", "Рекомендуется: Сведения о приложении → Батарея → Без ограничений.")
            },
            button = tx(language, "Ustaw baterię", "Battery settings", "Налаштувати батарею", "Настроить батарею"),
            onClick = onAppInfo
        )

        if (isSamsung) {
            InfoCard(
                title = tx(language, "Samsung może usypiać aplikację", "Samsung may put the app to sleep", "Samsung може присипляти застосунок", "Samsung может усыплять приложение"),
                body = tx(language, "Sprawdź też: Bateria → Limity użycia w tle. Usuń FUJARA z aplikacji uśpionych i głęboko uśpionych.", "Also check Battery → Background usage limits and remove FUJARA from sleeping/deep sleeping apps.", "Також перевірте Батарея → Обмеження фонового використання.", "Также проверьте Батарея → Ограничения фонового использования."),
                actionLabel = tx(language, "Otwórz ustawienia baterii", "Open battery settings", "Відкрити батарею", "Открыть батарею"),
                onAction = onBatterySettings
            )
        }

        Button(
            onClick = onContinue,
            enabled = serviceEnabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 15.dp)
        ) {
            Text(tx(language, "Gotowe — przejdź do FUJARY", "Done — open FUJARA", "Готово — перейти до FUJARA", "Готово — перейти в FUJARA"), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupProgress(serviceEnabled: Boolean, batteryUnrestricted: Boolean) {
    val done = if (!serviceEnabled) 0 else if (batteryUnrestricted) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$done / 3", fontWeight = FontWeight.Bold)
            Text("konfiguracja", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (index < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    language: AppLanguage,
    serviceEnabled: Boolean,
    analysisEnabled: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onPyszneSummary: () -> Unit
) {
    val active = serviceEnabled && analysisEnabled
    var showDemo by remember { mutableStateOf(false) }

    ScreenContainer(scrollable = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FujaraBrandMark(
                    level = if (active) 1f else 0.30f,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(width = 34.dp, height = 54.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("FUJARA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text(
                        text = tx(language, "KALKULATOR OPŁACALNOŚCI", "PROFITABILITY CHECK", "ПЕРЕВІРКА ВИГІДНОСТІ", "ПРОВЕРКА ВЫГОДНОСТИ"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            CompactAction("✓", onSetup)
            Spacer(Modifier.width(6.dp))
            CompactAction("⚙", onSettings)
        }

        Text(
            text = tx(language, "Sprawdź marżę, zanim przyjmiesz zlecenie.", "Check the margin before you accept.", "Перевір маржу перед прийняттям.", "Проверь маржу до принятия."),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black
        )

        StatusHeroCard(language = language, active = active, serviceEnabled = serviceEnabled)

        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                contentColor = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            Text(
                if (active) tx(language, "Wyłącz analizę", "Stop analysis", "Вимкнути аналіз", "Выключить анализ")
                else tx(language, "Włącz analizę", "Start analysis", "Увімкнути аналіз", "Включить анализ"),
                fontWeight = FontWeight.Bold
            )
        }

        SectionCard(
            step = "PYSZNE",
            title = tx(language, "Podsumowanie dnia", "Daily summary", "Підсумок дня", "Итоги дня")
        ) {
            Text(
                tx(
                    language,
                    "Zapisuj pojedyncze dostawy z historii Pyszne, a FUJARA policzy km, czas, zł/h, zł/km i najlepsze restauracje.",
                    "Save individual Pyszne deliveries and FUJARA will calculate distance, time, hourly/per-km rates and restaurant ranking.",
                    "Зберігайте окремі доставки Pyszne — FUJARA порахує кілометри, час, ставки та рейтинг ресторанів.",
                    "Сохраняйте отдельные доставки Pyszne — FUJARA посчитает километры, время, ставки и рейтинг ресторанов."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onPyszneSummary, modifier = Modifier.fillMaxWidth()) {
                Text(tx(language, "Otwórz podsumowanie Pyszne", "Open Pyszne summary", "Відкрити підсумок Pyszne", "Открыть итоги Pyszne"))
            }
        }

        SectionCard(
            step = "LIVE",
            title = tx(language, "Tak wygląda analiza", "This is the analysis", "Так виглядає аналіз", "Так выглядит анализ")
        ) {
            Text(
                tx(language, "Podgląd pokazuje wybrane wskaźniki w takim samym układzie jak podczas jazdy.", "The preview shows your selected metrics in the same layout used while delivering.", "Попередній перегляд показує вибрані показники в тому самому макеті.", "Предпросмотр показывает выбранные показатели в том же макете."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showDemo = !showDemo }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showDemo) tx(language, "Ukryj podgląd", "Hide preview", "Сховати", "Скрыть") else tx(language, "Pokaż podgląd nakładki", "Preview overlay", "Показати накладку", "Показать накладку"))
            }
            if (showDemo) DemoAnalysisCard(language)
        }
    }
}

@Composable
private fun PyszneSummaryScreen(
    prefs: AppPrefs,
    language: AppLanguage,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { PyszneLogStore(context) }
    var refreshToken by remember { mutableStateOf(0) }
    val entries = remember(refreshToken) { store.all() }
    val availableDates = entries.map { it.date }.distinct().sortedDescending()
    var selectedDate by remember { mutableStateOf(availableDates.firstOrNull() ?: LocalDate.now()) }

    LaunchedEffect(availableDates) {
        if (availableDates.isNotEmpty() && selectedDate !in availableDates) {
            selectedDate = availableDates.first()
        }
    }

    val dayReference = remember(refreshToken, selectedDate) { store.dayReference(selectedDate) }
    val rules = prefs.rulesForPlatform(CourierPlatform.PYSZNE)
    val summary = PyszneDaySummaryCalculator.calculate(
        date = selectedDate,
        entries = entries,
        rules = rules,
        decisionBasis = prefs.decisionBasis,
        zusPercent = if (prefs.zusEnabled) prefs.zusPercent else 0.0
    )

    var officialCountText by remember { mutableStateOf("") }
    var officialAmountText by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDate, entries.size, dayReference) {
        officialCountText = (dayReference?.orderCount ?: summary.orderCount).toString()
        officialAmountText = String.format(
            Locale.forLanguageTag("pl-PL"),
            "%.2f",
            dayReference?.amountPln ?: summary.grossPln
        )
        showResult = false
        validationMessage = ""
        confirmDelete = false
    }

    val resultProgress by animateFloatAsState(
        targetValue = if (showResult) 1f else 0.10f,
        animationSpec = tween(durationMillis = 1200),
        label = "pyszne_summary_progress"
    )

    ScreenContainer(scrollable = true) {
        TopBar(
            title = tx(language, "Pyszne · dzień", "Pyszne · day", "Pyszne · день", "Pyszne · день"),
            onBack = onBack
        )

        SectionCard(
            step = "1 / ZAPISY",
            title = tx(language, "Zebrane dostawy", "Saved deliveries", "Збережені доставки", "Сохранённые доставки")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val currentIndex = availableDates.indexOf(selectedDate)
                        val older = availableDates.getOrNull(currentIndex + 1)
                        if (older != null) selectedDate = older
                    },
                    enabled = availableDates.indexOf(selectedDate) in 0 until (availableDates.size - 1)
                ) { Text("‹") }

                Text(
                    text = formatSummaryDate(selectedDate, language),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )

                OutlinedButton(
                    onClick = {
                        val currentIndex = availableDates.indexOf(selectedDate)
                        val newer = availableDates.getOrNull(currentIndex - 1)
                        if (newer != null) selectedDate = newer
                    },
                    enabled = availableDates.indexOf(selectedDate) > 0
                ) { Text("›") }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummarySmallMetric(
                    modifier = Modifier.weight(1f),
                    label = tx(language, "ZAPISANE", "SAVED", "ЗБЕРЕЖЕНО", "СОХРАНЕНО"),
                    value = summary.orderCount.toString()
                )
                SummarySmallMetric(
                    modifier = Modifier.weight(1f),
                    label = tx(language, "KWOTA", "AMOUNT", "СУМА", "СУММА"),
                    value = String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", summary.grossPln)
                )
            }

            Text(
                tx(
                    language,
                    "W szczegółach pojedynczego zlecenia Pyszne pojawia się przycisk „ZAPISZ DANE”. Ten sam numer zlecenia nie zostanie dodany drugi raz.",
                    "On an individual Pyszne order screen use “SAVE DATA”. The same order will not be added twice.",
                    "У деталях окремого замовлення Pyszne натисніть «ЗБЕРЕГТИ ДАНІ». Те саме замовлення не додасться двічі.",
                    "В деталях отдельного заказа Pyszne нажмите «СОХРАНИТЬ ДАННЫЕ». Один и тот же заказ не добавится дважды."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = { refreshToken += 1 },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tx(language, "Odśwież logi", "Refresh logs", "Оновити записи", "Обновить записи"))
            }
        }

        SectionCard(
            step = "2 / KONTROLA",
            title = tx(language, "Potwierdź z Pyszne", "Confirm with Pyszne", "Підтвердьте з Pyszne", "Подтвердите с Pyszne")
        ) {
            if (dayReference != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            tx(language, "✓ Odczytano z ekranu Pyszne", "✓ Read from Pyszne screen", "✓ Зчитано з екрана Pyszne", "✓ Считано с экрана Pyszne"),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${dayReference.orderCount} zleceń · ${String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", dayReference.amountPln)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Text(
                tx(
                    language,
                    "Jeżeli wcześniej otworzysz w Pyszne ekran „Podsumowanie dnia”, FUJARA uzupełni te pola automatycznie. Możesz je też wpisać ręcznie. Potem sprawdzi, czy zapisano komplet.",
                    "If you first open Pyszne's daily summary, FUJARA fills these values automatically. You can also enter them manually. It then checks whether the saved log is complete.",
                    "Введіть кількість замовлень і суму з підсумку Pyszne. FUJARA перевірить повноту записів.",
                    "Введите количество заказов и сумму из итогов Pyszne. FUJARA проверит полноту записей."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = officialCountText,
                onValueChange = { officialCountText = it.filter(Char::isDigit).take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tx(language, "Liczba zleceń wg Pyszne", "Orders according to Pyszne", "Замовлень за Pyszne", "Заказов по Pyszne")) },
                singleLine = true
            )

            OutlinedTextField(
                value = officialAmountText,
                onValueChange = { officialAmountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tx(language, "Kwota wg Pyszne", "Amount according to Pyszne", "Сума за Pyszne", "Сумма по Pyszne")) },
                singleLine = true
            )

            Button(
                onClick = {
                    val expectedCount = officialCountText.toIntOrNull()
                    val expectedAmount = officialAmountText.replace(',', '.').toDoubleOrNull()
                    val countOk = expectedCount == summary.orderCount
                    val amountOk = expectedAmount != null && abs(expectedAmount - summary.grossPln) < 0.02
                    validationMessage = when {
                        expectedCount == null || expectedAmount == null -> tx(language, "Uzupełnij liczbę zleceń i kwotę.", "Enter both order count and amount.", "Введіть кількість і суму.", "Введите количество и сумму.")
                        countOk && amountOk -> tx(language, "✓ Zgadza się z logami. Można liczyć dzień.", "✓ Matches the saved log. Ready to calculate.", "✓ Збігається із записами. Можна рахувати день.", "✓ Совпадает с записями. Можно считать день.")
                        else -> {
                            val missing = expectedCount - summary.orderCount
                            val amountDiff = expectedAmount - summary.grossPln
                            tx(
                                language,
                                "⚠ Różnica: zlecenia ${if (missing >= 0) "+$missing" else missing}, kwota ${String.format(Locale.forLanguageTag("pl-PL"), "%+.2f zł", amountDiff)}. Sprawdź brakujące/podwójne zapisy.",
                                "⚠ Difference: orders ${if (missing >= 0) "+$missing" else missing}, amount ${String.format(Locale.US, "%+.2f PLN", amountDiff)}. Check missing/duplicate saves.",
                                "⚠ Є різниця в кількості або сумі. Перевірте збережені замовлення.",
                                "⚠ Есть разница в количестве или сумме. Проверьте сохранённые заказы."
                            )
                        }
                    }
                    // Nie pokazujemy ladnego wyniku na niekompletnych danych.
                    // Najpierw liczba zlecen i kwota musza zgadzac sie z Pyszne.
                    showResult = countOk && amountOk
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = summary.orderCount > 0
            ) {
                Text(tx(language, "Potwierdź i policz", "Confirm and calculate", "Підтвердити й порахувати", "Подтвердить и посчитать"))
            }

            if (validationMessage.isNotBlank()) {
                Text(
                    validationMessage,
                    color = if (validationMessage.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showResult && summary.orderCount > 0) {
            val accent = summaryStatusColor(summary.status)
            SectionCard(
                step = "3 / WYNIK",
                title = summaryCelebration(summary.status, language)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FujaraBrandMark(
                        level = resultProgress,
                        color = accent,
                        modifier = Modifier
                            .size(width = 52.dp, height = 86.dp)
                            .graphicsLayer {
                                scaleX = 0.82f + resultProgress * 0.25f
                                scaleY = 0.82f + resultProgress * 0.25f
                            }
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        BrandEyebrow(tx(language, "PODSUMOWANIE DNIA", "DAILY RESULT", "ПІДСУМОК ДНЯ", "ИТОГ ДНЯ"), accent)
                        Text(
                            text = "${summary.goodOrders} SUPER · ${summary.borderlineOrders} NA STYK · ${summary.poorOrders} FUJARA",
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedSummaryMetric(
                        modifier = Modifier.weight(1f),
                        label = "PLN/H",
                        value = summary.netPerHour ?: 0.0,
                        decimals = 0,
                        suffix = " zł/h",
                        visible = showResult
                    )
                    AnimatedSummaryMetric(
                        modifier = Modifier.weight(1f),
                        label = "PLN/KM",
                        value = summary.netPerKm ?: 0.0,
                        decimals = 2,
                        suffix = " zł/km",
                        visible = showResult
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedSummaryMetric(
                        modifier = Modifier.weight(1f),
                        label = tx(language, "DYSTANS", "DISTANCE", "ВІДСТАНЬ", "РАССТОЯНИЕ"),
                        value = summary.distanceKm,
                        decimals = 1,
                        suffix = " km",
                        visible = showResult
                    )
                    SummarySmallMetric(
                        modifier = Modifier.weight(1f),
                        label = tx(language, "CZAS", "TIME", "ЧАС", "ВРЕМЯ"),
                        value = formatDuration(summary.durationSeconds)
                    )
                }

                Text(
                    tx(language, "Wynik po ustawionych kosztach pojazdu${if (prefs.zusEnabled) " i ZUS" else ""}.", "Result after your configured vehicle cost${if (prefs.zusEnabled) " and ZUS" else ""}.", "Результат після налаштованих витрат.", "Результат после настроенных расходов."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard(
                step = "RESTAURACJE",
                title = tx(language, "Gdzie było SUPER, a gdzie FUJARA", "Best and worst restaurants", "Найкращі й найгірші ресторани", "Лучшие и худшие рестораны")
            ) {
                val best = summary.restaurants.firstOrNull()
                val worst = summary.restaurants.minByOrNull { it.netPerHour ?: Double.POSITIVE_INFINITY }

                if (best != null) {
                    Text(
                        tx(language, "🏆 Najlepiej: ${best.name}", "🏆 Best: ${best.name}", "🏆 Найкраще: ${best.name}", "🏆 Лучшее: ${best.name}"),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (worst != null && worst.name != best?.name) {
                    Text(
                        tx(language, "🪈 Najsłabiej: ${worst.name}", "🪈 Weakest: ${worst.name}", "🪈 Найслабше: ${worst.name}", "🪈 Самое слабое: ${worst.name}"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                summary.restaurants.take(8).forEachIndexed { index, restaurant ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(restaurant.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${restaurant.orderCount} zlec. · ${String.format(Locale.forLanguageTag("pl-PL"), "%.0f zł/h", restaurant.netPerHour ?: 0.0)} · ${String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł/km", restaurant.netPerKm ?: 0.0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "SUPER ${restaurant.goodOrders} · STYK ${restaurant.borderlineOrders} · FUJARA ${restaurant.poorOrders}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        StatusPill(summaryStatusLabel(restaurant.status, language), summaryStatusColor(restaurant.status))
                    }
                }
            }

            SectionCard(
                step = "SHARE",
                title = tx(language, "Podziel się wynikiem", "Share your result", "Поділитися результатом", "Поделиться результатом")
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tx(language, "Nick do wyniku (opcjonalnie)", "Nickname (optional)", "Нік (необов'язково)", "Ник (необязательно)")) },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, summary.shareText(nickname))
                        }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                tx(language, "Udostępnij wynik FUJARA", "Share FUJARA result", "Поділитися FUJARA", "Поделиться FUJARA")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tx(language, "Udostępnij wynik", "Share result", "Поділитися результатом", "Поделиться результатом"))
                }
                Text(
                    tx(
                        language,
                        "Ta wersja udostępnia gotowy wynik przez system Android. Wspólny ranking online wymaga osobnego backendu i zgody użytkownika na wysyłanie danych.",
                        "This version shares a ready result through Android. A shared online leaderboard needs a backend and explicit user consent for uploading data.",
                        "Ця версія ділиться готовим результатом через Android. Спільний онлайн-рейтинг потребує backend та згоди користувача.",
                        "Эта версия делится готовым результатом через Android. Общий онлайн-рейтинг требует backend и согласия пользователя."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (summary.orderCount > 0) {
            TextButton(
                onClick = {
                    if (confirmDelete) {
                        store.deleteDate(selectedDate)
                        refreshToken += 1
                        confirmDelete = false
                    } else {
                        confirmDelete = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (confirmDelete) {
                        tx(language, "Kliknij ponownie: usuń zapisy tego dnia", "Tap again: delete this day", "Ще раз: видалити день", "Ещё раз: удалить день")
                    } else {
                        tx(language, "Usuń zapisy tego dnia", "Delete this day's logs", "Видалити записи дня", "Удалить записи дня")
                    },
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun SummarySmallMetric(
    modifier: Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun AnimatedSummaryMetric(
    modifier: Modifier,
    label: String,
    value: Double,
    decimals: Int,
    suffix: String,
    visible: Boolean
) {
    val animated by animateFloatAsState(
        targetValue = if (visible) value.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1450),
        label = "summary_metric_$label"
    )
    val format = if (decimals == 0) "%.0f" else "%.${decimals}f"
    SummarySmallMetric(
        modifier = modifier,
        label = label,
        value = String.format(Locale.forLanguageTag("pl-PL"), format, animated) + suffix
    )
}

private fun summaryStatusColor(status: ProfitabilityStatus): Color = when (status) {
    ProfitabilityStatus.PROFITABLE -> Color(0xFF176A35)
    ProfitabilityStatus.ALMOST_PROFITABLE -> Color(0xFF9A7400)
    ProfitabilityStatus.UNPROFITABLE -> Color(0xFFB3261E)
    ProfitabilityStatus.NO_TIME -> Color(0xFF7B5E00)
}

private fun summaryStatusLabel(status: ProfitabilityStatus, language: AppLanguage): String = when (status) {
    ProfitabilityStatus.PROFITABLE -> tx(language, "SUPER", "SUPER", "СУПЕР", "СУПЕР")
    ProfitabilityStatus.ALMOST_PROFITABLE -> tx(language, "NA STYK", "BORDERLINE", "НА МЕЖІ", "НА ГРАНИ")
    ProfitabilityStatus.UNPROFITABLE -> "FUJARA"
    ProfitabilityStatus.NO_TIME -> tx(language, "BRAK CZASU", "NO TIME", "НЕМАЄ ЧАСУ", "НЕТ ВРЕМЕНИ")
}

private fun summaryCelebration(status: ProfitabilityStatus, language: AppLanguage): String = when (status) {
    ProfitabilityStatus.PROFITABLE -> tx(language, "🎉 Super robota! FUJARA rośnie", "🎉 Great job! FUJARA grows", "🎉 Супер! FUJARA росте", "🎉 Супер! FUJARA растёт")
    ProfitabilityStatus.ALMOST_PROFITABLE -> tx(language, "🔥 Dobra robota — było na styku", "🔥 Good job — close to target", "🔥 Добре — майже ціль", "🔥 Хорошо — почти цель")
    ProfitabilityStatus.UNPROFITABLE -> tx(language, "🪈 FUJARA wie, gdzie uciekła stawka", "🪈 FUJARA found where the rate leaked", "🪈 FUJARA знає, де втратилась ставка", "🪈 FUJARA знает, где потерялась ставка")
    ProfitabilityStatus.NO_TIME -> tx(language, "Podsumowanie dnia", "Daily summary", "Підсумок дня", "Итог дня")
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatSummaryDate(date: LocalDate, language: AppLanguage): String {
    val locale = when (language) {
        AppLanguage.EN -> Locale.US
        AppLanguage.UK -> Locale.forLanguageTag("uk-UA")
        AppLanguage.RU -> Locale.forLanguageTag("ru-RU")
        AppLanguage.PL -> Locale.forLanguageTag("pl-PL")
    }
    return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
}


@Composable
private fun StatusHeroCard(language: AppLanguage, active: Boolean, serviceEnabled: Boolean) {
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FujaraBrandMark(level = if (active) 1f else 0.30f, color = accent)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BrandEyebrow(if (active) tx(language, "AKTYWNA", "ACTIVE", "АКТИВНА", "АКТИВНА") else tx(language, "PAUZA", "PAUSED", "ПАУЗА", "ПАУЗА"), accent)
                    Text(
                        text = when {
                            !serviceEnabled -> tx(language, "Dokończ konfigurację telefonu", "Finish phone setup", "Завершіть налаштування", "Завершите настройку")
                            active -> tx(language, "Czekam na ofertę", "Waiting for an offer", "Чекаю на пропозицію", "Жду предложение")
                            else -> tx(language, "Analiza jest wyłączona", "Analysis is off", "Аналіз вимкнено", "Анализ выключен")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Text(
                text = when {
                    !serviceEnabled -> tx(language, "Włącz usługę dostępności, żeby FUJARA mogła odczytać widoczną kartę zlecenia.", "Enable Accessibility so FUJARA can read the visible offer card.", "Увімкніть Accessibility, щоб FUJARA могла читати картку пропозиції.", "Включите Accessibility, чтобы FUJARA могла читать карточку предложения.")
                    active -> tx(language, "Gdy pojawi się oferta, dostaniesz wynik po kosztach, zł/km i zł/h w małym panelu w prawym górnym rogu.", "When an offer appears, you will get after-costs, per-km and hourly values in a small top-right panel.", "Коли з’явиться пропозиція, побачите результат після витрат, за км і за годину.", "Когда появится предложение, увидите результат после расходов, за км и за час.")
                    else -> tx(language, "Włącz analizę przed rozpoczęciem jazdy.", "Turn analysis on before you start delivering.", "Увімкніть аналіз перед поїздкою.", "Включите анализ перед поездкой.")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DemoAnalysisCard(language: AppLanguage) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.65f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            FujaraBrandMark(level = 1f, color = accent, modifier = Modifier.size(width = 28.dp, height = 64.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("FUJARA · DEMO", fontWeight = FontWeight.Black)
                        Text(tx(language, "przykładowa oferta", "sample offer", "приклад", "пример"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    StatusPill(tx(language, "OPŁACA SIĘ", "WORTH IT", "ВИГІДНО", "ВЫГОДНО"), accent)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    DemoMetric(Modifier.weight(1f), tx(language, "KWOTA", "AMOUNT", "СУМА", "СУММА"), "24,90 zł")
                    DemoMetric(Modifier.weight(1f), tx(language, "ZOSTAJE", "LEFT", "ЗАЛИШАЄТЬСЯ", "ОСТАЁТСЯ"), "19,46 zł", true)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    DemoMetric(Modifier.weight(1f), "PLN/H", "41,70", true)
                    DemoMetric(Modifier.weight(1f), "PLN/KM", "2,86", true)
                    DemoMetric(Modifier.weight(1f), tx(language, "TRASA", "DISTANCE", "ВІДСТАНЬ", "РАССТОЯНИЕ"), "6,8 km")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DemoMetric(
    modifier: Modifier,
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsScreen(
    prefs: AppPrefs,
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onThemeChanged: (AppThemeMode) -> Unit,
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var selectedPlatform by remember { mutableStateOf(CourierPlatform.GLOBAL) }
    var useCustomRules by remember { mutableStateOf(true) }

    val initialRules = prefs.rulesForPlatform(CourierPlatform.GLOBAL)
    var vehicleCost by remember { mutableStateOf(initialRules.vehicleCostPerKm.toFloat()) }
    var kmYellowRange by remember {
        mutableStateOf(
            (initialRules.minimumNetPerKm - initialRules.toleranceNetPerKm)
                .coerceAtLeast(0.0).toFloat()..initialRules.minimumNetPerKm.toFloat()
        )
    }
    var hourYellowRange by remember {
        mutableStateOf(
            (initialRules.minimumNetPerHour - initialRules.toleranceNetPerHour)
                .coerceAtLeast(0.0).toFloat()..initialRules.minimumNetPerHour.toFloat()
        )
    }
    var extraTime by remember { mutableStateOf(initialRules.extraTimeMinutes.toFloat()) }

    var showHourly by remember { mutableStateOf(prefs.showHourly) }
    var showPerKm by remember { mutableStateOf(prefs.showPerKm) }
    var showAmount by remember { mutableStateOf(prefs.showAmount) }
    var showAfterCosts by remember { mutableStateOf(prefs.showAfterCosts) }
    var showTime by remember { mutableStateOf(prefs.showTime) }
    var showDistance by remember { mutableStateOf(prefs.showDistance) }
    var opacity by remember { mutableStateOf(prefs.overlayOpacityPercent.toFloat()) }
    var fontScale by remember { mutableStateOf(prefs.overlayFontScalePercent.toFloat()) }
    var roundEarnings by remember { mutableStateOf(prefs.roundEarnings) }
    var zusEnabled by remember { mutableStateOf(prefs.zusEnabled) }
    var zusPercent by remember { mutableStateOf(prefs.zusPercent.toFloat()) }
    var restaurantBlacklist by remember {
        mutableStateOf(BlacklistEntryCodec.parse(prefs.restaurantBlacklistText))
    }
    var customerBlacklist by remember {
        mutableStateOf(BlacklistEntryCodec.parse(prefs.customerBlacklistText))
    }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.fromCode(prefs.languageCode)) }
    var selectedTheme by remember { mutableStateOf(prefs.themeMode) }
    var decisionBasis by remember { mutableStateOf(prefs.decisionBasis) }
    val context = LocalContext.current

    fun saved() {
        val message = tx(
            selectedLanguage,
            "Zapisano automatycznie",
            "Saved automatically",
            "Збережено автоматично",
            "Сохранено автоматически"
        )
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun applyRuleState(rules: ProfitabilityCalculator.Rules) {
        vehicleCost = rules.vehicleCostPerKm.toFloat()
        kmYellowRange = (rules.minimumNetPerKm - rules.toleranceNetPerKm)
            .coerceAtLeast(0.0).toFloat()..rules.minimumNetPerKm.toFloat()
        hourYellowRange = (rules.minimumNetPerHour - rules.toleranceNetPerHour)
            .coerceAtLeast(0.0).toFloat()..rules.minimumNetPerHour.toFloat()
        extraTime = rules.extraTimeMinutes.toFloat()
    }

    fun currentRules(): ProfitabilityCalculator.Rules = ProfitabilityCalculator.Rules(
        vehicleCostPerKm = vehicleCost.toDouble(),
        minimumNetPerKm = kmYellowRange.endInclusive.toDouble(),
        toleranceNetPerKm = (kmYellowRange.endInclusive - kmYellowRange.start).coerceAtLeast(0f).toDouble(),
        minimumNetPerHour = hourYellowRange.endInclusive.toDouble(),
        toleranceNetPerHour = (hourYellowRange.endInclusive - hourYellowRange.start).coerceAtLeast(0f).toDouble(),
        extraTimeMinutes = extraTime.roundToInt()
    )

    fun persistRules() {
        val rules = currentRules()
        if (selectedPlatform == CourierPlatform.GLOBAL) {
            prefs.setRules(CourierPlatform.GLOBAL, rules)
        } else if (useCustomRules) {
            prefs.setRules(selectedPlatform, rules)
        }
        saved()
    }

    fun loadPlatform(platform: CourierPlatform) {
        useCustomRules = platform == CourierPlatform.GLOBAL || prefs.hasCustomRules(platform)
        applyRuleState(prefs.rulesForPlatform(platform))
    }

    val ruleControlsEnabled = selectedPlatform == CourierPlatform.GLOBAL || useCustomRules

    ScreenContainer(scrollable = true) {
        TopBar(title = tx(language, "Ustawienia", "Settings", "Налаштування", "Настройки"), onBack = onBack)

        InfoCard(
            title = tx(language, "Ustaw pod realną jazdę", "Tune it for real delivery work", "Налаштуйте під реальну роботу", "Настройте под реальную работу"),
            body = tx(
                language,
                "Najpierw ustaw zapas czasu i koszt auta. Potem progi opłacalności. ZUS i czarne listy są opcjonalne. Wszystko zapisuje się automatycznie.",
                "Start with the time buffer and vehicle cost, then tune profitability thresholds. ZUS and blocklists are optional. Everything saves automatically.",
                "Спочатку задайте запас часу й витрати авто, потім пороги вигідності. ZUS і чорні списки необов’язкові.",
                "Сначала задайте запас времени и расходы авто, затем пороги выгоды. ZUS и черные списки необязательны."
            )
        )

        SectionCard(step = "UI", title = tx(language, "Wygląd i język", "Appearance & language", "Вигляд і мова", "Вид и язык")) {
            Text(
                tx(language, "Motyw aplikacji", "App theme", "Тема застосунку", "Тема приложения"),
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppThemeMode.values().forEach { mode ->
                    val label = when (mode) {
                        AppThemeMode.SYSTEM -> tx(language, "System", "System", "Система", "Система")
                        AppThemeMode.LIGHT -> tx(language, "Jasny", "Light", "Світлий", "Светлый")
                        AppThemeMode.DARK -> tx(language, "Ciemny", "Dark", "Темний", "Тёмный")
                    }
                    FilterChip(
                        selected = selectedTheme == mode,
                        onClick = {
                            selectedTheme = mode
                            prefs.themeMode = mode
                            onThemeChanged(mode)
                            saved()
                        },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            Text(
                tx(language, "Język", "Language", "Мова", "Язык"),
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLanguage.values().forEach { item ->
                    FilterChip(
                        selected = selectedLanguage == item,
                        onClick = {
                            selectedLanguage = item
                            prefs.languageCode = item.code
                            onLanguageChanged(item)
                            saved()
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }

        SectionCard(step = "DECYZJA", title = tx(language, "Na czym oprzeć ocenę?", "How should an offer be rated?", "Як оцінювати пропозицію?", "Как оценивать предложение?")) {
            Text(
                tx(language, "Wybierz wskaźnik, który ma decydować o kolorze wyniku.", "Choose which metric controls the result color.", "Оберіть показник, що визначає колір результату.", "Выберите показатель, определяющий цвет результата."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DecisionBasis.values().forEach { basis ->
                    val label = when (basis) {
                        DecisionBasis.HOURLY -> tx(language, "Za godzinę", "Per hour", "За годину", "За час")
                        DecisionBasis.PER_KM -> tx(language, "Za km", "Per km", "За км", "За км")
                        DecisionBasis.MIXED -> tx(language, "Mieszane", "Mixed", "Змішано", "Смешанно")
                    }
                    FilterChip(
                        selected = decisionBasis == basis,
                        onClick = {
                            decisionBasis = basis
                            prefs.decisionBasis = basis
                            saved()
                        },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                tx(language, "Mieszane = oferta musi przejść oba progi; słabszy wynik decyduje o kolorze.", "Mixed = both thresholds matter; the weaker result decides the color.", "Змішано = враховуються обидва пороги; вирішує слабший результат.", "Смешанно = учитываются оба порога; решает более слабый результат."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(step = "PROGI", title = tx(language, "Czas, koszty i progi", "Time, costs & thresholds", "Час, витрати й пороги", "Время, расходы и пороги")) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CourierPlatform.values().forEach { platform ->
                    FilterChip(
                        selected = selectedPlatform == platform,
                        onClick = {
                            selectedPlatform = platform
                            loadPlatform(platform)
                        },
                        label = { Text(platformLabel(platform)) }
                    )
                }
            }

            if (selectedPlatform != CourierPlatform.GLOBAL) {
                SwitchRow(
                    title = tx(language, "Osobne ustawienia dla ${platformLabel(selectedPlatform)}", "Separate settings for ${platformLabel(selectedPlatform)}", "Окремі налаштування для ${platformLabel(selectedPlatform)}", "Отдельные настройки для ${platformLabel(selectedPlatform)}"),
                    checked = useCustomRules,
                    onCheckedChange = { enabled ->
                        useCustomRules = enabled
                        if (enabled) {
                            val base = prefs.globalRules()
                            applyRuleState(base)
                            prefs.setRules(selectedPlatform, base)
                        } else {
                            prefs.clearCustomRules(selectedPlatform)
                            applyRuleState(prefs.globalRules())
                        }
                        saved()
                    }
                )
            }

            Text(
                tx(language, "Koszt pojazdu / 1 km", "Vehicle cost / 1 km", "Вартість авто / 1 км", "Стоимость авто / 1 км"),
                fontWeight = FontWeight.Bold
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "Paliwo, serwis, opony, amortyzacja", "Fuel, service, tyres, depreciation", "Пальне, сервіс, шини, амортизація", "Топливо, сервис, шины, амортизация"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("${String.format(Locale.ROOT, "%.2f", vehicleCost)} zł/km", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Slider(
                value = vehicleCost,
                onValueChange = { vehicleCost = it },
                onValueChangeFinished = { persistRules() },
                valueRange = 0f..5f,
                enabled = ruleControlsEnabled
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tx(language, "Zapas czasu", "Time buffer", "Запас часу", "Запас времени"), fontWeight = FontWeight.Bold)
                    Text(
                        tx(language, "Parking, wejście do lokalu, oczekiwanie, wydanie zamówienia", "Parking, entering the venue, waiting and hand-off", "Паркування, очікування та видача", "Парковка, ожидание и выдача"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("+${extraTime.roundToInt()} min", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Slider(
                value = extraTime,
                onValueChange = { extraTime = it },
                onValueChangeFinished = { persistRules() },
                valueRange = 0f..120f,
                steps = 119,
                enabled = ruleControlsEnabled
            )
            Text(
                if (selectedPlatform == CourierPlatform.GLOBAL) {
                    tx(language, "Ten zapas jest globalny. Dla konkretnej aplikacji możesz ustawić inny w zakładce powyżej.", "This is the global buffer. You can set a different one for each courier app above.", "Це глобальний запас. Для кожного застосунку можна задати окремий.", "Это глобальный запас. Для каждого приложения можно задать отдельный.")
                } else {
                    tx(language, "Zapas dla ${platformLabel(selectedPlatform)} jest dodawany do czasu zlecenia przed liczeniem PLN/h.", "The ${platformLabel(selectedPlatform)} buffer is added before PLN/h is calculated.", "Запас для ${platformLabel(selectedPlatform)} додається до часу перед розрахунком PLN/h.", "Запас для ${platformLabel(selectedPlatform)} добавляется ко времени перед расчетом PLN/h.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            ColorLegend(language)

            Text("PLN/km", fontWeight = FontWeight.Black)
            Text(
                tx(language,
                    "Nieopłacalna < ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)} · na granicy ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)}–${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)} · opłacalna ≥ ${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)}",
                    "Unprofitable < ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)} · borderline ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)}–${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)} · profitable ≥ ${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)}",
                    "Невигідна < ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)} · на межі ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)}–${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)} · вигідна ≥ ${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)}",
                    "Невыгодная < ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)} · на грани ${String.format(Locale.ROOT, "%.2f", kmYellowRange.start)}–${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)} · выгодная ≥ ${String.format(Locale.ROOT, "%.2f", kmYellowRange.endInclusive)}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RangeSlider(
                value = kmYellowRange,
                onValueChange = { kmYellowRange = it },
                onValueChangeFinished = { persistRules() },
                valueRange = 0f..10f,
                enabled = ruleControlsEnabled
            )

            Text("PLN/h", fontWeight = FontWeight.Black)
            Text(
                tx(language,
                    "Nieopłacalna < ${hourYellowRange.start.roundToInt()} · na granicy ${hourYellowRange.start.roundToInt()}–${hourYellowRange.endInclusive.roundToInt()} · opłacalna ≥ ${hourYellowRange.endInclusive.roundToInt()}",
                    "Unprofitable < ${hourYellowRange.start.roundToInt()} · borderline ${hourYellowRange.start.roundToInt()}–${hourYellowRange.endInclusive.roundToInt()} · profitable ≥ ${hourYellowRange.endInclusive.roundToInt()}",
                    "Невигідна < ${hourYellowRange.start.roundToInt()} · на межі ${hourYellowRange.start.roundToInt()}–${hourYellowRange.endInclusive.roundToInt()} · вигідна ≥ ${hourYellowRange.endInclusive.roundToInt()}",
                    "Невыгодная < ${hourYellowRange.start.roundToInt()} · на грани ${hourYellowRange.start.roundToInt()}–${hourYellowRange.endInclusive.roundToInt()} · выгодная ≥ ${hourYellowRange.endInclusive.roundToInt()}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RangeSlider(
                value = hourYellowRange,
                onValueChange = { hourYellowRange = it },
                onValueChangeFinished = { persistRules() },
                valueRange = 0f..100f,
                enabled = ruleControlsEnabled
            )
        }

        SectionCard(step = "ZUS", title = tx(language, "Realny zarobek po ZUS", "Earnings after ZUS", "Заробіток після ZUS", "Заработок после ZUS")) {
            SwitchRow(
                title = tx(language, "Uwzględniaj procent ZUS", "Apply ZUS percentage", "Враховувати відсоток ZUS", "Учитывать процент ZUS"),
                checked = zusEnabled,
                onCheckedChange = {
                    zusEnabled = it
                    prefs.zusEnabled = it
                    saved()
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "Potrącenie", "Deduction", "Відрахування", "Удержание"), fontWeight = FontWeight.Bold)
                Text("${String.format(Locale.ROOT, "%.1f", zusPercent)}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Slider(
                value = zusPercent,
                onValueChange = {
                    zusPercent = it
                    prefs.zusPercent = it.toDouble()
                },
                onValueChangeFinished = { saved() },
                valueRange = 0f..100f,
                steps = 199,
                enabled = zusEnabled
            )
            Text(
                tx(language, "Gdy funkcja jest włączona, PLN/h i PLN/km liczą się z kwoty po ZUS i po koszcie pojazdu. W panelu pojawia się też kwota „Po ZUS”.", "When enabled, PLN/h and PLN/km use earnings after ZUS and vehicle cost. The overlay also shows the amount after ZUS.", "Коли ввімкнено, PLN/h і PLN/km рахуються після ZUS та витрат авто.", "Когда включено, PLN/h и PLN/km считаются после ZUS и расходов авто."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(step = "LISTY", title = tx(language, "Czarne listy", "Blocklists", "Чорні списки", "Черные списки")) {
            Text(
                tx(
                    language,
                    "Dodawaj pozycje przyciskiem +. Sama nazwa jest dopasowywana ściśle. Jeśli kilka lokali ma tę samą nazwę, dopisz adres — wtedy FUJARA sprawdzi nazwę i adres razem.",
                    "Add entries with +. A name-only entry is matched strictly. If several places share a name, add the address so FUJARA checks both.",
                    "Додавайте записи кнопкою +. Назва без адреси збігається точно; для однакових назв додайте адресу.",
                    "Добавляйте записи кнопкой +. Название без адреса совпадает точно; для одинаковых названий добавьте адрес."
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            BlacklistEditor(
                language = language,
                title = tx(language, "Fujarne restauracje", "Blocked restaurants", "Небажані ресторани", "Нежелательные рестораны"),
                nameLabel = tx(language, "Nazwa restauracji", "Restaurant name", "Назва ресторану", "Название ресторана"),
                entries = restaurantBlacklist,
                onEntriesChanged = { entries ->
                    restaurantBlacklist = entries
                    prefs.restaurantBlacklistText = BlacklistEntryCodec.serialize(entries)
                }
            )

            BlacklistEditor(
                language = language,
                title = tx(language, "Fujarni odbiorcy", "Blocked customers", "Небажані клієнти", "Нежелательные получатели"),
                nameLabel = tx(language, "Nazwa / imię odbiorcy", "Customer name", "Ім'я клієнта", "Имя получателя"),
                entries = customerBlacklist,
                onEntriesChanged = { entries ->
                    customerBlacklist = entries
                    prefs.customerBlacklistText = BlacklistEntryCodec.serialize(entries)
                }
            )

            Text(
                tx(language, "Wielkość liter i polskie znaki nie mają znaczenia. Adres jest opcjonalny. Dane zostają tylko w telefonie.", "Letter case and diacritics are ignored. Address is optional. Lists stay on your phone.", "Регістр і діакритика не мають значення. Адреса необов’язкова.", "Регистр и диакритика не имеют значения. Адрес необязателен."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard(step = "PANEL", title = tx(language, "Nakładka oferty", "Offer overlay", "Накладка пропозиції", "Накладка предложения")) {
            Text(
                tx(language, "Domyślnie pokazujemy tylko PLN/h i PLN/km. Przycisk „−” na panelu chowa go do małego przycisku FUJARA, żeby podejrzeć trasę.", "By default only PLN/h and PLN/km are shown. The “−” button collapses the overlay to a small FUJARA button so you can inspect the route.", "Типово показуємо PLN/h і PLN/km. Кнопка «−» згортає панель.", "По умолчанию показываем PLN/h и PLN/km. Кнопка «−» сворачивает панель."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow("PLN/h", showHourly) {
                showHourly = it; prefs.showHourly = it; saved()
            }
            SwitchRow("PLN/km", showPerKm) {
                showPerKm = it; prefs.showPerKm = it; saved()
            }
            SwitchRow(tx(language, "Kwota oferty", "Offer amount", "Сума пропозиції", "Сумма предложения"), showAmount) {
                showAmount = it; prefs.showAmount = it; saved()
            }
            SwitchRow(tx(language, "Po kosztach", "After costs", "Після витрат", "После расходов"), showAfterCosts) {
                showAfterCosts = it; prefs.showAfterCosts = it; saved()
            }
            SwitchRow(tx(language, "Czas", "Time", "Час", "Время"), showTime) {
                showTime = it; prefs.showTime = it; saved()
            }
            SwitchRow(tx(language, "Dystans", "Distance", "Відстань", "Расстояние"), showDistance) {
                showDistance = it; prefs.showDistance = it; saved()
            }
            SwitchRow(tx(language, "Zaokrąglaj zarobki do pełnych PLN", "Round earnings to whole PLN", "Округляти заробіток до цілих PLN", "Округлять заработок до целых PLN"), roundEarnings) {
                roundEarnings = it; prefs.roundEarnings = it; saved()
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "Rozmiar liczb", "Number size", "Розмір чисел", "Размер чисел"), fontWeight = FontWeight.Bold)
                Text("${fontScale.roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Slider(
                value = fontScale,
                onValueChange = {
                    fontScale = it
                    prefs.overlayFontScalePercent = it.roundToInt()
                },
                onValueChangeFinished = {
                    saved()
                },
                valueRange = 80f..170f
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    if (showHourly && !showPerKm && !showAmount && !showAfterCosts && !showTime && !showDistance) "48 PLN/h" else "48 PLN/h   ·   3.10 PLN/km",
                    modifier = Modifier.padding(14.dp),
                    fontWeight = FontWeight.Black,
                    fontSize = (18f * (fontScale / 100f)).coerceIn(14f, 31f).sp,
                    textAlign = TextAlign.Center
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "Krycie panelu", "Panel opacity", "Прозорість панелі", "Прозрачность панели"), fontWeight = FontWeight.Bold)
                Text("${opacity.roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
            }
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                onValueChangeFinished = {
                    prefs.overlayOpacityPercent = opacity.roundToInt()
                    saved()
                },
                valueRange = 45f..100f
            )
        }

        Text(
            tx(language, "Zmiany zapisują się automatycznie — nie ma przycisku Zapisz.", "Changes save automatically — there is no Save button.", "Зміни зберігаються автоматично — кнопка Зберегти не потрібна.", "Изменения сохраняются автоматически — кнопка Сохранить не нужна."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        TextButton(onClick = { uriHandler.openUri("https://alekwq1.github.io/Apka/privacy.html") }, modifier = Modifier.fillMaxWidth()) {
            Text(tx(language, "Polityka prywatności", "Privacy policy", "Політика конфіденційності", "Политика конфиденциальности"))
        }
        Text("FUJARA 0.8.2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun BlacklistEditor(
    language: AppLanguage,
    title: String,
    nameLabel: String,
    entries: List<BlacklistEntry>,
    onEntriesChanged: (List<BlacklistEntry>) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }

    Text(title, fontWeight = FontWeight.Bold)

    entries.forEachIndexed { index, entry ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (entry.name.isNotBlank()) {
                        Text(entry.name, fontWeight = FontWeight.Bold)
                    }
                    if (entry.address.isNotBlank()) {
                        Text(
                            entry.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = {
                    onEntriesChanged(entries.filterIndexed { i, _ -> i != index })
                }) {
                    Text(tx(language, "− Usuń", "− Remove", "− Видалити", "− Удалить"))
                }
            }
        }
    }

    OutlinedTextField(
        value = newName,
        onValueChange = { newName = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(nameLabel) },
        singleLine = true
    )
    OutlinedTextField(
        value = newAddress,
        onValueChange = { newAddress = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(tx(language, "Adres (opcjonalnie)", "Address (optional)", "Адреса (необов’язково)", "Адрес (необязательно)")) },
        singleLine = true
    )
    Button(
        onClick = {
            val entry = BlacklistEntry(newName.trim(), newAddress.trim())
            if (entry.name.isNotBlank() || entry.address.isNotBlank()) {
                onEntriesChanged(entries + entry)
                newName = ""
                newAddress = ""
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = newName.isNotBlank() || newAddress.isNotBlank()
    ) {
        Text(tx(language, "+ Dodaj", "+ Add", "+ Додати", "+ Добавить"))
    }
}

@Composable
private fun ColorLegend(language: AppLanguage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.primary, tx(language, "opłacalna", "profitable", "вигідна", "выгодная"))
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.secondary, tx(language, "na granicy", "borderline", "на межі", "на грани"))
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.tertiary, tx(language, "nieopłacalna", "unprofitable", "невигідна", "невыгодная"))
    }
}

@Composable
private fun LegendItem(modifier: Modifier, color: Color, label: String) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(99.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FujaraBrandMark(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val holeColor = MaterialTheme.colorScheme.background
    Canvas(modifier = modifier.size(width = 46.dp, height = 72.dp)) {
        val tubeWidth = size.width * 0.30f
        val left = (size.width - tubeWidth) / 2f
        val top = size.height * 0.05f
        val bottom = size.height * 0.84f
        val radius = tubeWidth / 2f
        val trackColor = Color(0xFF2A333C)
        val red = Color(0xFFFF6565)
        val yellow = Color(0xFFFFD84A)
        val green = Color(0xFF82E46B)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(left, top),
            size = Size(tubeWidth, bottom - top),
            cornerRadius = CornerRadius(radius, radius)
        )

        val safeLevel = level.coerceIn(0f, 1f)
        val fullHeight = bottom - top
        val segmentGap = size.height * 0.012f
        val segmentHeight = (fullHeight - segmentGap * 2f) / 3f
        val segments = listOf(
            Triple(bottom - segmentHeight, bottom, red),
            Triple(bottom - segmentHeight * 2f - segmentGap, bottom - segmentHeight - segmentGap, yellow),
            Triple(top, top + segmentHeight, green)
        )
        val fillHeight = fullHeight * safeLevel
        val fillBoundary = bottom - fillHeight

        segments.forEach { (segmentTop, segmentBottom, segmentColor) ->
            val visibleTop = maxOf(segmentTop, fillBoundary)
            if (visibleTop < segmentBottom) {
                drawRoundRect(
                    color = segmentColor,
                    topLeft = Offset(left, visibleTop),
                    size = Size(tubeWidth, segmentBottom - visibleTop),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }

        val bellColor = if (safeLevel > 0.02f) red else trackColor
        val bellTop = bottom - size.height * 0.02f
        drawRoundRect(
            color = bellColor,
            topLeft = Offset(size.width * 0.18f, bellTop),
            size = Size(size.width * 0.64f, size.height * 0.13f),
            cornerRadius = CornerRadius(size.width * 0.10f, size.width * 0.10f)
        )

        listOf(0.32f, 0.48f, 0.64f).forEach { fraction ->
            drawCircle(
                color = holeColor,
                radius = size.width * 0.045f,
                center = Offset(size.width / 2f, top + (bottom - top) * fraction)
            )
        }

        // Subtle status dot keeps the mark tied to the current app state.
        drawCircle(
            color = color,
            radius = size.width * 0.035f,
            center = Offset(size.width * 0.76f, size.height * 0.12f)
        )
    }
}

@Composable
private fun ScreenContainer(
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 18.dp)
        .let {
            if (scrollable) it.verticalScroll(rememberScrollState()) else it
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(4.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SetupStepCard(
    step: String,
    ok: Boolean,
    warning: Boolean = false,
    title: String,
    body: String,
    button: String?,
    onClick: () -> Unit
) {
    val accent = when {
        ok -> MaterialTheme.colorScheme.primary
        warning -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (ok) "✓" else step, color = accent, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (button != null) {
                    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text(button) }
                }
            }
        }
    }
}

@Composable
private fun BrandEyebrow(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black
    )
}

@Composable
private fun SectionCard(
    title: String,
    step: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (step != null) BrandEyebrow(step)
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            content()
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun platformLabel(platform: CourierPlatform): String = when (platform) {
    CourierPlatform.GLOBAL -> "Global"
    CourierPlatform.UBER -> "Uber"
    CourierPlatform.WOLT -> "Wolt"
    CourierPlatform.GLOVO -> "Glovo"
    CourierPlatform.BOLT -> "Bolt"
    CourierPlatform.PYSZNE -> "Pyszne"
    CourierPlatform.STUART -> "Stuart"
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, DeliveryAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()

    return enabled.split(':').any { entry ->
        ComponentName.unflattenFromString(entry) == expected
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
        .getOrDefault(false)
}


private fun tx(
    language: AppLanguage,
    pl: String,
    en: String,
    uk: String,
    ru: String
): String = when (language) {
    AppLanguage.PL -> pl
    AppLanguage.EN -> en
    AppLanguage.UK -> uk
    AppLanguage.RU -> ru
}
