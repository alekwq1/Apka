package pl.deliveryassistant.mvp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
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

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF58AF99),
                    onPrimary = Color.White,
                    secondary = Color(0xFF42675D),
                    surface = Color(0xFFFBFCFB),
                    background = Color(0xFFF4F6F5),
                    surfaceVariant = Color(0xFFEFF4F2)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        AppScreen.PRIVACY -> PrivacyScreen(
                            language = language,
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
                            onSetup = { screen = AppScreen.SETUP }
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            prefs = prefs,
                            language = language,
                            onLanguageChanged = {
                                languageCode = it.code
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
        if (::prefs.isInitialized) refreshSystemState()
    }

    private fun refreshSystemState() {
        serviceEnabled = isAccessibilityServiceEnabled(this)
        analysisEnabled = prefs.analysisEnabled
        batteryUnrestricted = isBatteryUnrestricted(this)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
    onCancel: () -> Unit,
    onAccept: () -> Unit
) {
    ScreenContainer(scrollable = true) {
        Text(
            text = tx(language, "Delivery Assistant potrzebuje dostępu do ekranu", "Delivery Assistant needs screen access", "Delivery Assistant потребує доступу до екрана", "Delivery Assistant нужен доступ к экрану"),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = tx(language, "Zanim włączysz usługę dostępności, sprawdź dokładnie do czego będzie używana.", "Before enabling Accessibility, please review exactly what it is used for.", "Перед увімкненням служби спеціальних можливостей перевірте, для чого вона використовується.", "Перед включением службы специальных возможностей проверьте, для чего она используется."),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        InfoCard(
            title = tx(language, "Co aplikacja odczytuje", "What the app reads", "Що читає застосунок", "Что читает приложение"),
            body = tx(
                language,
                "Tylko ekran ofert w obsługiwanych aplikacjach kurierskich. Z oferty odczytywana jest kwota, dystans, czas oraz planowana godzina odbioru/dostawy, jeśli jest dostępna.\n\nDane służą wyłącznie do policzenia opłacalności i pokazania małej nakładki nad ofertą.",
                "Only offer screens in supported courier apps. The app reads the amount, distance, time and planned pickup/delivery time when available.\n\nThe data is used only to calculate profitability and show a small overlay above the offer.",
                "Лише екрани пропозицій у підтримуваних кур'єрських застосунках. Зчитуються сума, відстань, час і плановий час забору/доставки, якщо він доступний.\n\nДані використовуються лише для розрахунку вигідності та показу невеликої накладки.",
                "Только экраны предложений в поддерживаемых курьерских приложениях. Считываются сумма, расстояние, время и плановое время забора/доставки, если оно доступно.\n\nДанные используются только для расчета выгодности и показа небольшой накладки."
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
        TopBar(
            title = "Delivery Assistant",
            onBack = onBack
        )

        Text(
            text = tx(language, "Skonfiguruj aplikację do pracy w tle", "Set up the app to work in the background", "Налаштуйте застосунок для роботи у фоні", "Настройте приложение для работы в фоне"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SetupStepCard(
            ok = serviceEnabled,
            title = tx(language, "1. Dostęp do ekranu", "1. Screen access", "1. Доступ до екрана", "1. Доступ к экрану"),
            body = tx(language, "Włącz Delivery Assistant w Ustawienia → Dostępność → Zainstalowane aplikacje.", "Enable Delivery Assistant in Settings → Accessibility → Installed apps.", "Увімкніть Delivery Assistant у Налаштування → Спеціальні можливості → Встановлені застосунки.", "Включите Delivery Assistant в Настройки → Специальные возможности → Установленные приложения."),
            button = if (serviceEnabled) null else tx(language, "Otwórz ustawienia", "Open settings", "Відкрити налаштування", "Открыть настройки"),
            onClick = onAccessibility
        )

        SetupStepCard(
            ok = serviceEnabled,
            title = tx(language, "2. Nakładka nad ofertą", "2. Offer overlay", "2. Накладка над пропозицією", "2. Накладка над предложением"),
            body = if (serviceEnabled) {
                tx(language, "Gotowe. Nakładka działa jako część usługi dostępności i nie przechwytuje dotyku.", "Ready. The overlay runs as part of Accessibility and does not intercept touches.", "Готово. Накладка працює як частина служби спеціальних можливостей і не перехоплює дотики.", "Готово. Накладка работает как часть службы специальных возможностей и не перехватывает касания.")
            } else {
                tx(language, "Najpierw włącz dostęp do ekranu. W tej wersji nie potrzeba osobnego pozwolenia „wyświetlaj nad innymi aplikacjami”.", "Enable screen access first. This build does not require a separate “display over other apps” permission.", "Спочатку увімкніть доступ до екрана. У цій версії окремий дозвіл «поверх інших застосунків» не потрібен.", "Сначала включите доступ к экрану. В этой версии отдельное разрешение «поверх других приложений» не требуется.")
            },
            button = null,
            onClick = {}
        )

        SetupStepCard(
            ok = batteryUnrestricted,
            warning = !batteryUnrestricted,
            title = tx(language, "3. Działanie w tle", "3. Background activity", "3. Робота у фоні", "3. Работа в фоне"),
            body = if (batteryUnrestricted) {
                tx(language, "Optymalizacja baterii wygląda na wyłączoną dla aplikacji.", "Battery optimization appears to be disabled for the app.", "Оптимізацію батареї для застосунку, схоже, вимкнено.", "Оптимизация батареи для приложения, похоже, отключена.")
            } else {
                tx(language, "Zalecane: otwórz informacje o aplikacji → Bateria i ustaw tryb Bez ograniczeń / Unrestricted.", "Recommended: open App info → Battery and set Unrestricted.", "Рекомендовано: відкрийте Інформацію про застосунок → Батарея та виберіть Без обмежень.", "Рекомендуется: откройте Сведения о приложении → Батарея и выберите Без ограничений.")
            },
            button = tx(language, "Otwórz informacje o aplikacji", "Open app info", "Відкрити інформацію про застосунок", "Открыть сведения о приложении"),
            onClick = onAppInfo
        )

        if (isSamsung) {
            InfoCard(
                title = tx(language, "Samsung: dodatkowe ustawienia", "Samsung: additional settings", "Samsung: додаткові налаштування", "Samsung: дополнительные настройки"),
                body = tx(
                    language,
                    "Aby system nie usypiał aplikacji, sprawdź: Ustawienia → Bateria → Limity użycia w tle. Usuń Delivery Assistant z list „Aplikacje w uśpieniu” i „Głębokie uśpienie”. Wyłącz też automatyczne usypianie nieużywanych aplikacji.",
                    "To reduce background killing, check Settings → Battery → Background usage limits. Remove Delivery Assistant from Sleeping apps and Deep sleeping apps, and disable automatic sleeping for unused apps if needed.",
                    "Щоб система не присипляла застосунок, перевірте Налаштування → Батарея → Обмеження фонового використання. Приберіть Delivery Assistant зі списків сплячих застосунків.",
                    "Чтобы система не усыпляла приложение, проверьте Настройки → Батарея → Ограничения фонового использования. Уберите Delivery Assistant из списков спящих приложений."
                ),
                actionLabel = tx(language, "Otwórz ustawienia baterii", "Open battery settings", "Відкрити налаштування батареї", "Открыть настройки батареи"),
                onAction = onBatterySettings
            )
        }

        Text(
            text = tx(language, "Wymagany jest krok 1. Ustawienia baterii są zalecane, szczególnie na Samsungu.", "Step 1 is required. Battery settings are recommended, especially on Samsung devices.", "Крок 1 обов'язковий. Налаштування батареї рекомендовані, особливо на Samsung.", "Шаг 1 обязателен. Настройки батареи рекомендуются, особенно на Samsung."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onContinue,
            enabled = serviceEnabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tx(language, "Kontynuuj", "Continue", "Продовжити", "Продолжить"))
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
    onSetup: () -> Unit
) {
    val active = serviceEnabled && analysisEnabled

    ScreenContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Delivery Assistant",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSetup) {
                Text("✓")
            }
            TextButton(onClick = onSettings) {
                Text("⚙")
            }
        }

        Spacer(Modifier.height(40.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F8F6))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 30.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (active) {
                        "● ${tx(language, "Aktywny", "Active", "Активний", "Активен")}"
                    } else {
                        "● ${tx(language, "Nieaktywny", "Inactive", "Неактивний", "Неактивен")}"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color(0xFF2FA84F) else Color(0xFFE65353)
                )

                Text(
                    text = when {
                        !serviceEnabled -> tx(language, "Wymaga dokończenia konfiguracji", "Setup required", "Потрібно завершити налаштування", "Требуется завершить настройку")
                        active -> tx(language, "Wykrywanie ofert", "Offer detection", "Виявлення пропозицій", "Обнаружение предложений")
                        else -> tx(language, "Zatrzymano", "Stopped", "Зупинено", "Остановлено")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (active) {
                    tx(language, "Stop", "Stop", "Стоп", "Стоп")
                } else {
                    tx(language, "Start", "Start", "Старт", "Старт")
                }
            )
        }

        OutlinedButton(
            onClick = onSetup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tx(language, "Sprawdź konfigurację telefonu", "Check phone setup", "Перевірити налаштування телефона", "Проверить настройки телефона"))
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = tx(language, "Nakładka pojawi się automatycznie w prawym górnym rogu, gdy aplikacja rozpozna ofertę.", "The overlay appears automatically in the top-right corner when an offer is detected.", "Накладка автоматично з'явиться у правому верхньому куті після розпізнавання пропозиції.", "Накладка автоматически появится в правом верхнем углу после распознавания предложения."),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsScreen(
    prefs: AppPrefs,
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onBack: () -> Unit
) {
    var selectedPlatform by remember { mutableStateOf(CourierPlatform.GLOBAL) }
    var useCustomRules by remember { mutableStateOf(true) }
    var vehicleCost by remember { mutableStateOf(formatSetting(prefs.vehicleCostPerKm)) }
    var minKm by remember { mutableStateOf(formatSetting(prefs.minimumNetPerKm)) }
    var toleranceKm by remember { mutableStateOf(formatSetting(prefs.toleranceNetPerKm)) }
    var minHour by remember { mutableStateOf(formatSetting(prefs.minimumNetPerHour)) }
    var toleranceHour by remember { mutableStateOf(formatSetting(prefs.toleranceNetPerHour)) }

    var showHourly by remember { mutableStateOf(prefs.showHourly) }
    var showPerKm by remember { mutableStateOf(prefs.showPerKm) }
    var showAmount by remember { mutableStateOf(prefs.showAmount) }
    var showAfterCosts by remember { mutableStateOf(prefs.showAfterCosts) }
    var showTime by remember { mutableStateOf(prefs.showTime) }
    var showDistance by remember { mutableStateOf(prefs.showDistance) }
    var opacity by remember { mutableStateOf(prefs.overlayOpacityPercent.toFloat()) }
    var displaySeconds by remember { mutableStateOf(prefs.overlayDisplaySeconds.toFloat()) }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.fromCode(prefs.languageCode)) }
    var targetPackage by remember { mutableStateOf(prefs.targetPackage) }
    var showAdvanced by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    fun loadPlatform(platform: CourierPlatform) {
        val rules = prefs.rulesForPlatform(platform)
        vehicleCost = formatSetting(rules.vehicleCostPerKm)
        minKm = formatSetting(rules.minimumNetPerKm)
        toleranceKm = formatSetting(rules.toleranceNetPerKm)
        minHour = formatSetting(rules.minimumNetPerHour)
        toleranceHour = formatSetting(rules.toleranceNetPerHour)
        useCustomRules = platform == CourierPlatform.GLOBAL || prefs.hasCustomRules(platform)
    }

    fun saveAll() {
        val rules = ProfitabilityCalculator.Rules(
            vehicleCostPerKm = vehicleCost.toDoublePl()?.takeIf { it >= 0.0 } ?: run {
                saveMessage = tx(language, "Sprawdź koszt pojazdu.", "Check vehicle cost.", "Перевірте вартість поїздки.", "Проверьте стоимость поездки.")
                return
            },
            minimumNetPerKm = minKm.toDoublePl()?.takeIf { it >= 0.0 } ?: run {
                saveMessage = tx(language, "Sprawdź minimum na kilometr.", "Check minimum per km.", "Перевірте мінімум на кілометр.", "Проверьте минимум на километр.")
                return
            },
            toleranceNetPerKm = toleranceKm.toDoublePl()?.takeIf { it >= 0.0 } ?: run {
                saveMessage = tx(language, "Sprawdź tolerancję na kilometr.", "Check per-km tolerance.", "Перевірте допуск на кілометр.", "Проверьте допуск на километр.")
                return
            },
            minimumNetPerHour = minHour.toDoublePl()?.takeIf { it >= 0.0 } ?: run {
                saveMessage = tx(language, "Sprawdź minimum na godzinę.", "Check minimum per hour.", "Перевірте мінімум на годину.", "Проверьте минимум в час.")
                return
            },
            toleranceNetPerHour = toleranceHour.toDoublePl()?.takeIf { it >= 0.0 } ?: run {
                saveMessage = tx(language, "Sprawdź tolerancję na godzinę.", "Check hourly tolerance.", "Перевірте погодинний допуск.", "Проверьте почасовой допуск.")
                return
            }
        )

        if (selectedPlatform == CourierPlatform.GLOBAL) {
            prefs.setRules(CourierPlatform.GLOBAL, rules)
        } else if (useCustomRules) {
            prefs.setRules(selectedPlatform, rules)
        } else {
            prefs.clearCustomRules(selectedPlatform)
        }

        prefs.showHourly = showHourly
        prefs.showPerKm = showPerKm
        prefs.showAmount = showAmount
        prefs.showAfterCosts = showAfterCosts
        prefs.showTime = showTime
        prefs.showDistance = showDistance
        prefs.overlayOpacityPercent = opacity.roundToInt()
        prefs.overlayDisplaySeconds = displaySeconds.roundToInt()
        prefs.languageCode = selectedLanguage.code
        prefs.targetPackage = targetPackage
        onLanguageChanged(selectedLanguage)
        saveMessage = tx(selectedLanguage, "Ustawienia zapisane.", "Settings saved.", "Налаштування збережено.", "Настройки сохранены.")
    }

    ScreenContainer(scrollable = true) {
        TopBar(
            title = tx(language, "Ustawienia", "Settings", "Налаштування", "Настройки"),
            onBack = onBack
        )

        SectionCard(
            title = tx(language, "Progi opłacalności", "Profitability thresholds", "Пороги вигідності", "Пороги выгодности")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CourierPlatform.values().forEach { platform ->
                    FilterChip(
                        selected = selectedPlatform == platform,
                        onClick = {
                            selectedPlatform = platform
                            loadPlatform(platform)
                            saveMessage = null
                        },
                        label = { Text(platformLabel(platform)) }
                    )
                }
            }

            Text(
                text = if (selectedPlatform == CourierPlatform.GLOBAL) {
                    tx(language, "Domyślne progi dla wszystkich platform.", "Default thresholds for all platforms.", "Типові пороги для всіх платформ.", "Пороговые значения по умолчанию для всех платформ.")
                } else {
                    tx(language, "Możesz ustawić inne progi tylko dla ${platformLabel(selectedPlatform)}.", "You can set different thresholds only for ${platformLabel(selectedPlatform)}.", "Можна встановити окремі пороги лише для ${platformLabel(selectedPlatform)}.", "Можно задать отдельные пороги только для ${platformLabel(selectedPlatform)}.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedPlatform != CourierPlatform.GLOBAL) {
                SwitchRow(
                    title = tx(language, "Własne progi dla tej platformy", "Custom thresholds for this platform", "Власні пороги для цієї платформи", "Свои пороги для этой платформы"),
                    checked = useCustomRules,
                    onCheckedChange = { enabled ->
                        useCustomRules = enabled
                        if (!enabled) {
                            val global = prefs.globalRules()
                            vehicleCost = formatSetting(global.vehicleCostPerKm)
                            minKm = formatSetting(global.minimumNetPerKm)
                            toleranceKm = formatSetting(global.toleranceNetPerKm)
                            minHour = formatSetting(global.minimumNetPerHour)
                            toleranceHour = formatSetting(global.toleranceNetPerHour)
                        }
                        saveMessage = null
                    }
                )
            }

            NumberField(
                label = tx(language, "Koszt pojazdu", "Vehicle cost", "Вартість поїздки", "Стоимость поездки"),
                value = vehicleCost,
                suffix = "zł/km",
                help = tx(language, "Paliwo/prąd + serwis + opony + amortyzacja na 1 km.", "Fuel/electricity + service + tyres + depreciation per km.", "Пальне/електрика + сервіс + шини + амортизація на 1 км.", "Топливо/электричество + сервис + шины + амортизация на 1 км."),
                enabled = useCustomRules,
                onChange = { vehicleCost = it; saveMessage = null }
            )

            NumberField(
                label = tx(language, "Minimum po kosztach na kilometr", "Minimum after costs per km", "Мінімум після витрат на км", "Минимум после расходов на км"),
                value = minKm,
                suffix = "zł/km",
                help = tx(language, "Zielony wynik wymaga co najmniej tej wartości.", "Green requires at least this value.", "Зелений результат вимагає щонайменше цього значення.", "Зеленый результат требует как минимум этого значения."),
                enabled = useCustomRules,
                onChange = { minKm = it; saveMessage = null }
            )

            NumberField(
                label = tx(language, "Tolerancja na kilometr", "Per-km tolerance", "Допуск на кілометр", "Допуск на километр"),
                value = toleranceKm,
                suffix = "zł/km",
                help = tx(language, "O tyle wynik może zejść poniżej minimum i nadal być żółty.", "How far below the minimum the result may fall and still be yellow.", "Наскільки результат може бути нижчим за мінімум і залишатися жовтим.", "Насколько результат может быть ниже минимума и оставаться желтым."),
                enabled = useCustomRules,
                onChange = { toleranceKm = it; saveMessage = null }
            )

            NumberField(
                label = tx(language, "Minimum po kosztach na godzinę", "Minimum after costs per hour", "Мінімум після витрат за годину", "Минимум после расходов в час"),
                value = minHour,
                suffix = "zł/h",
                help = tx(language, "Zielony wynik wymaga co najmniej tej stawki godzinowej.", "Green requires at least this hourly rate.", "Зелений результат вимагає щонайменше цієї погодинної ставки.", "Зеленый результат требует как минимум этой почасовой ставки."),
                enabled = useCustomRules,
                onChange = { minHour = it; saveMessage = null }
            )

            NumberField(
                label = tx(language, "Tolerancja na godzinę", "Hourly tolerance", "Погодинний допуск", "Почасовой допуск"),
                value = toleranceHour,
                suffix = "zł/h",
                help = tx(language, "O tyle stawka może zejść poniżej minimum i nadal być żółta.", "How far below the minimum the hourly rate may fall and still be yellow.", "Наскільки ставка може бути нижчою за мінімум і залишатися жовтою.", "Насколько ставка может быть ниже минимума и оставаться желтой."),
                enabled = useCustomRules,
                onChange = { toleranceHour = it; saveMessage = null }
            )
        }

        SectionCard(
            title = tx(language, "Co pokazywać na nakładce", "What to show on overlay", "Що показувати на накладці", "Что показывать на накладке")
        ) {
            SwitchRow("PLN/h", showHourly) { showHourly = it }
            SwitchRow("PLN/km", showPerKm) { showPerKm = it }
            SwitchRow(tx(language, "Kwota", "Amount", "Сума", "Сумма"), showAmount) { showAmount = it }
            SwitchRow(tx(language, "Po kosztach", "After costs", "Після витрат", "После расходов"), showAfterCosts) { showAfterCosts = it }
            SwitchRow(tx(language, "Czas (min)", "Time (min)", "Час (хв)", "Время (мин)"), showTime) { showTime = it }
            SwitchRow(tx(language, "Dystans (km)", "Distance (km)", "Відстань (км)", "Расстояние (км)"), showDistance) { showDistance = it }
        }

        SectionCard(
            title = tx(language, "Wygląd nakładki", "Overlay appearance", "Вигляд накладки", "Вид накладки")
        ) {
            Text(tx(language, "Przezroczystość: ${opacity.roundToInt()}%", "Opacity: ${opacity.roundToInt()}%", "Прозорість: ${opacity.roundToInt()}%", "Прозрачность: ${opacity.roundToInt()}%"))
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                valueRange = 35f..100f
            )

            Text(tx(language, "Czas wyświetlania: ${displaySeconds.roundToInt()} s", "Display time: ${displaySeconds.roundToInt()} s", "Час показу: ${displaySeconds.roundToInt()} с", "Время показа: ${displaySeconds.roundToInt()} с"))
            Slider(
                value = displaySeconds,
                onValueChange = { displaySeconds = it },
                valueRange = 5f..60f
            )
        }

        SectionCard(
            title = tx(language, "Język aplikacji", "App language", "Мова застосунку", "Язык приложения")
        ) {
            AppLanguage.values().forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedLanguage == item,
                        onClick = { selectedLanguage = item }
                    )
                    Text(item.label)
                }
            }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(
                if (showAdvanced) {
                    tx(language, "Ukryj ustawienia zaawansowane", "Hide advanced settings", "Сховати розширені налаштування", "Скрыть расширенные настройки")
                } else {
                    tx(language, "Ustawienia zaawansowane", "Advanced settings", "Розширені налаштування", "Расширенные настройки")
                }
            )
        }

        if (showAdvanced) {
            SectionCard(
                title = tx(language, "Zaawansowane", "Advanced", "Розширені", "Расширенные")
            ) {
                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("Package name") },
                    supportingText = {
                        Text(tx(language, "Zwykle zostaw puste. Użyj tylko do testów jednej konkretnej aplikacji.", "Normally leave blank. Use only to test one specific app.", "Зазвичай залишайте порожнім. Використовуйте лише для тестування одного застосунку.", "Обычно оставляйте пустым. Используйте только для тестирования одного приложения."))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        InfoCard(
            title = tx(language, "Jak czytać kolory", "How to read the colors", "Як читати кольори", "Как читать цвета"),
            body = tx(
                language,
                "Zielony = oferta spełnia minimum na kilometr i godzinę. Żółty = mieści się w ustawionej tolerancji. Czerwony = jest poniżej tolerancji. Pomarańczowy BRAK CZASU = aplikacja nie ma wiarygodnego czasu do policzenia stawki godzinowej.",
                "Green = the offer meets both per-km and hourly minimums. Yellow = it is within your tolerance. Red = below tolerance. Orange NO TIME = there is no reliable duration for an hourly-rate calculation.",
                "Зелений = пропозиція відповідає мінімуму на км і годину. Жовтий = у межах допуску. Червоний = нижче допуску. Помаранчевий НЕМАЄ ЧАСУ = немає надійної тривалості.",
                "Зеленый = предложение соответствует минимуму на км и час. Желтый = в пределах допуска. Красный = ниже допуска. Оранжевый НЕТ ВРЕМЕНИ = нет надежной длительности."
            )
        )

        Button(
            onClick = { saveAll() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tx(language, "Zapisz ustawienia", "Save settings", "Зберегти налаштування", "Сохранить настройки"))
        }

        saveMessage?.let {
            Text(
                text = it,
                color = if (it.contains("zapis", ignoreCase = true) || it.contains("saved", ignoreCase = true) || it.contains("збереж", ignoreCase = true) || it.contains("сохран", ignoreCase = true)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        HorizontalDivider()

        Text(
            text = "Delivery Assistant ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    ok: Boolean,
    warning: Boolean = false,
    title: String,
    body: String,
    button: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F9F7))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = when {
                    ok -> "✓"
                    warning -> "!"
                    else -> "×"
                },
                style = MaterialTheme.typography.titleLarge,
                color = when {
                    ok -> Color(0xFF54B36B)
                    warning -> Color(0xFFD79727)
                    else -> Color(0xFFE05A5A)
                },
                modifier = Modifier.width(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (button != null) {
                    OutlinedButton(onClick = onClick) {
                        Text(button)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
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

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    help: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(help) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun platformLabel(platform: CourierPlatform): String = when (platform) {
    CourierPlatform.GLOBAL -> "Global"
    CourierPlatform.UBER -> "Uber"
    CourierPlatform.WOLT -> "Wolt"
    CourierPlatform.GLOVO -> "Glovo"
    CourierPlatform.BOLT -> "Bolt"
    CourierPlatform.PYSZNE -> "Pyszne"
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

private fun String.toDoublePl(): Double? =
    replace(',', '.').toDoubleOrNull()

private fun formatSetting(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')

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
