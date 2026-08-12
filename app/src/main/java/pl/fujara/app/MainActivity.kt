package pl.fujara.app

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
                colorScheme = darkColorScheme(
                    primary = Color(0xFF82E46B),
                    onPrimary = Color(0xFF071107),
                    secondary = Color(0xFFFFD84A),
                    onSecondary = Color(0xFF171300),
                    tertiary = Color(0xFFFF6565),
                    background = Color(0xFF090C0F),
                    onBackground = Color(0xFFF3F6F8),
                    surface = Color(0xFF11161B),
                    onSurface = Color(0xFFF3F6F8),
                    surfaceVariant = Color(0xFF171D23),
                    onSurfaceVariant = Color(0xFFAAB3BD),
                    outline = Color(0xFF2A333C),
                    error = Color(0xFFFF6565)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    val uriHandler = LocalUriHandler.current

    ScreenContainer(scrollable = true) {
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
                "FUJARA używa systemowej usługi dostępności Android (AccessibilityService), aby rozpoznać widoczną kartę oferty z obsługiwanej aplikacji kurierskiej i wyświetlić nad nią obliczenie opłacalności. Odczytuje z oferty kwotę, dystans, czas oraz planowaną godzinę odbioru/dostawy, jeśli jest dostępna. Gdy oferta jest pływającą kartą nad innym ekranem, zrzut może obejmować także tło, ale dane spoza oferty nie są używane ani zapisywane.\n\nWszystkie obliczenia są wykonywane lokalnie na telefonie. FUJARA nie klika, nie przyjmuje ani nie odrzuca zleceń za Ciebie.",
                "FUJARA uses Android's AccessibilityService to recognize a visible offer card from a supported courier app and show a profitability calculation above it. It reads the amount, distance, duration and planned pickup/delivery time when available. If the offer is a floating card over another screen, the screenshot can include the background, but content outside the offer is not used or stored.\n\nAll calculations are performed locally on the phone. FUJARA does not click, accept or reject jobs for you.",
                "FUJARA використовує системну службу AccessibilityService Android, щоб розпізнати видиму картку пропозиції з підтримуваного кур'єрського застосунку та показати розрахунок вигідності. Дані обробляються локально на телефоні й не зберігаються. FUJARA не натискає кнопки та не приймає чи відхиляє замовлення за вас.",
                "FUJARA использует системную службу AccessibilityService Android, чтобы распознать видимую карточку предложения из поддерживаемого курьерского приложения и показать расчет выгодности. Данные обрабатываются локально на телефоне и не сохраняются. FUJARA не нажимает кнопки и не принимает или отклоняет заказы за вас."
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
    onSetup: () -> Unit
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionCard(
                modifier = Modifier.weight(1f),
                eyebrow = "01",
                title = tx(language, "Telefon", "Phone", "Телефон", "Телефон"),
                subtitle = if (serviceEnabled) tx(language, "Gotowy", "Ready", "Готово", "Готово") else tx(language, "Wymaga konfiguracji", "Setup required", "Потрібне налаштування", "Нужна настройка"),
                onClick = onSetup
            )
            QuickActionCard(
                modifier = Modifier.weight(1f),
                eyebrow = "02",
                title = tx(language, "Zasady", "Rules", "Правила", "Правила"),
                subtitle = tx(language, "Koszty i minima", "Costs & thresholds", "Витрати й пороги", "Расходы и пороги"),
                onClick = onSettings
            )
        }

        SectionCard(
            step = "LIVE",
            title = tx(language, "Tak wygląda analiza", "This is the analysis", "Так виглядає аналіз", "Так выглядит анализ")
        ) {
            Text(
                tx(language, "Panel jest widoczny dokładnie tak długo, jak oferta. Bez osobnego czasu wyświetlania.", "The panel stays visible exactly as long as the offer. No separate display timer.", "Панель видима рівно стільки, скільки пропозиція.", "Панель видна ровно столько, сколько предложение."),
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
private fun QuickActionCard(
    modifier: Modifier,
    eyebrow: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrandEyebrow(eyebrow)
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F12)),
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
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
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
        prefs.languageCode = selectedLanguage.code
        prefs.targetPackage = targetPackage
        onLanguageChanged(selectedLanguage)
        saveMessage = tx(selectedLanguage, "Ustawienia zapisane.", "Settings saved.", "Налаштування збережено.", "Настройки сохранены.")
    }

    ScreenContainer(scrollable = true) {
        TopBar(title = tx(language, "Ustawienia", "Settings", "Налаштування", "Настройки"), onBack = onBack)
        BrandEyebrow(tx(language, "USTAW RAZ, POTEM TYLKO JEDŹ", "SET ONCE, THEN DRIVE", "НАЛАШТУЙТЕ ОДИН РАЗ", "НАСТРОЙТЕ ОДИН РАЗ"))
        Text(
            tx(language, "FUJARA ocenia każdą ofertę według Twoich kosztów i minimów.", "FUJARA rates every offer using your costs and thresholds.", "FUJARA оцінює кожну пропозицію за вашими витратами й порогами.", "FUJARA оценивает каждое предложение по вашим расходам и порогам."),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )

        SectionCard(step = "ZAKRES", title = tx(language, "Gdzie mają działać te ustawienia?", "Where should these settings apply?", "Де застосовувати ці налаштування?", "Где применять эти настройки?")) {
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
                            saveMessage = null
                        },
                        label = { Text(platformLabel(platform)) }
                    )
                }
            }
            Text(
                if (selectedPlatform == CourierPlatform.GLOBAL) {
                    tx(language, "Domyślne wartości dla wszystkich platform.", "Default values for all platforms.", "Типові значення для всіх платформ.", "Значения по умолчанию для всех платформ.")
                } else {
                    tx(language, "Możesz nadpisać ustawienia tylko dla ${platformLabel(selectedPlatform)}.", "You can override settings only for ${platformLabel(selectedPlatform)}.", "Можна окремо налаштувати ${platformLabel(selectedPlatform)}.", "Можно отдельно настроить ${platformLabel(selectedPlatform)}.")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectedPlatform != CourierPlatform.GLOBAL) {
                SwitchRow(
                    title = tx(language, "Własne ustawienia dla tej platformy", "Custom settings for this platform", "Власні налаштування для цієї платформи", "Свои настройки для этой платформы"),
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
        }

        SectionCard(step = "01", title = tx(language, "Koszt przejazdu", "Driving cost", "Вартість поїздки", "Стоимость поездки")) {
            Text(
                tx(language, "Wpisz realny koszt 1 km. To od niego liczymy, ile faktycznie zostaje z oferty.", "Enter your real cost per km. This is used to calculate what remains from the offer.", "Вкажіть реальну вартість 1 км.", "Укажите реальную стоимость 1 км."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NumberField(
                label = tx(language, "Koszt pojazdu", "Vehicle cost", "Вартість поїздки", "Стоимость поездки"),
                value = vehicleCost,
                suffix = "zł/km",
                help = tx(language, "Paliwo/prąd + serwis + opony + amortyzacja.", "Fuel/electricity + service + tyres + depreciation.", "Пальне/електрика + сервіс + шини + амортизація.", "Топливо/электричество + сервис + шины + амортизация."),
                enabled = useCustomRules,
                onChange = { vehicleCost = it; saveMessage = null }
            )
        }

        SectionCard(step = "02", title = tx(language, "Twoje minima", "Your thresholds", "Ваші пороги", "Ваши пороги")) {
            ColorLegend(language)
            NumberField(
                label = tx(language, "Zielone od", "Green from", "Зелений від", "Зелёный от"),
                value = minKm,
                suffix = "zł/km",
                help = tx(language, "Minimalny wynik po kosztach na kilometr.", "Minimum after-cost result per km.", "Мінімум після витрат на км.", "Минимум после расходов на км."),
                enabled = useCustomRules,
                onChange = { minKm = it; saveMessage = null }
            )
            NumberField(
                label = tx(language, "Żółta tolerancja", "Yellow tolerance", "Жовтий допуск", "Жёлтый допуск"),
                value = toleranceKm,
                suffix = "zł/km",
                help = tx(language, "O tyle wynik może spaść poniżej minimum i nadal być żółty.", "How far below the minimum a result may fall and still be yellow.", "Наскільки результат може бути нижчим за мінімум.", "Насколько результат может быть ниже минимума."),
                enabled = useCustomRules,
                onChange = { toleranceKm = it; saveMessage = null }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            NumberField(
                label = tx(language, "Zielone od", "Green from", "Зелений від", "Зелёный от"),
                value = minHour,
                suffix = "zł/h",
                help = tx(language, "Minimalny wynik po kosztach na godzinę.", "Minimum after-cost result per hour.", "Мінімум після витрат за годину.", "Минимум после расходов в час."),
                enabled = useCustomRules,
                onChange = { minHour = it; saveMessage = null }
            )
            NumberField(
                label = tx(language, "Żółta tolerancja", "Yellow tolerance", "Жовтий допуск", "Жёлтый допуск"),
                value = toleranceHour,
                suffix = "zł/h",
                help = tx(language, "O tyle stawka godzinowa może spaść poniżej minimum.", "How far the hourly rate may fall below the minimum.", "Наскільки погодинна ставка може бути нижчою.", "Насколько почасовая ставка может быть ниже."),
                enabled = useCustomRules,
                onChange = { toleranceHour = it; saveMessage = null }
            )
        }

        SectionCard(step = "03", title = tx(language, "Nakładka na ofertę", "Offer overlay", "Накладка на пропозицію", "Накладка на предложение")) {
            Text(
                tx(language, "Wybierz tylko informacje, które chcesz widzieć podczas decyzji.", "Choose only the information you want while deciding.", "Виберіть лише потрібні дані.", "Выберите только нужные данные."),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow("PLN/h", showHourly) { showHourly = it }
            SwitchRow("PLN/km", showPerKm) { showPerKm = it }
            SwitchRow(tx(language, "Kwota oferty", "Offer amount", "Сума пропозиції", "Сумма предложения"), showAmount) { showAmount = it }
            SwitchRow(tx(language, "Po kosztach", "After costs", "Після витрат", "После расходов"), showAfterCosts) { showAfterCosts = it }
            SwitchRow(tx(language, "Czas", "Time", "Час", "Время"), showTime) { showTime = it }
            SwitchRow(tx(language, "Dystans", "Distance", "Відстань", "Расстояние"), showDistance) { showDistance = it }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tx(language, "Krycie panelu", "Panel opacity", "Прозорість панелі", "Прозрачность панели"), fontWeight = FontWeight.Bold)
                Text("${opacity.roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 35f..100f)
            InfoCard(
                title = tx(language, "Bez timera", "No timer", "Без таймера", "Без таймера"),
                body = tx(language, "Nakładka jest widoczna tak długo, jak widoczna jest oferta. Gdy oferta znika, FUJARA znika razem z nią.", "The overlay stays visible as long as the offer is visible. When the offer disappears, FUJARA disappears with it.", "Накладка видима стільки, скільки пропозиція.", "Накладка видна столько, сколько предложение.")
            )
        }

        SectionCard(step = "04", title = tx(language, "Język", "Language", "Мова", "Язык")) {
            AppLanguage.values().forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selectedLanguage = item }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedLanguage == item, onClick = { selectedLanguage = item })
                    Text(item.label, modifier = Modifier.weight(1f))
                    if (selectedLanguage == item) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdvanced) tx(language, "Ukryj zaawansowane", "Hide advanced", "Сховати розширені", "Скрыть расширенные") else tx(language, "Ustawienia zaawansowane", "Advanced settings", "Розширені налаштування", "Расширенные настройки"))
        }

        if (showAdvanced) {
            SectionCard(step = "DEV", title = tx(language, "Zaawansowane", "Advanced", "Розширені", "Расширенные")) {
                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("Package name") },
                    supportingText = { Text(tx(language, "Zostaw puste poza testami jednej konkretnej aplikacji.", "Leave blank except when testing one specific app.", "Залиште порожнім, окрім тестів.", "Оставьте пустым, кроме тестов.")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Button(
            onClick = { saveAll() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            Text(tx(language, "Zapisz ustawienia", "Save settings", "Зберегти налаштування", "Сохранить настройки"), fontWeight = FontWeight.Bold)
        }

        saveMessage?.let {
            Text(
                text = it,
                color = if (it.contains("zapis", ignoreCase = true) || it.contains("saved", ignoreCase = true) || it.contains("збереж", ignoreCase = true) || it.contains("сохран", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
        }

        TextButton(onClick = { uriHandler.openUri("https://alekwq1.github.io/Apka/privacy.html") }, modifier = Modifier.fillMaxWidth()) {
            Text(tx(language, "Polityka prywatności", "Privacy policy", "Політика конфіденційності", "Политика конфиденциальности"))
        }
        Text("FUJARA 0.6.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ColorLegend(language: AppLanguage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.primary, tx(language, "zielone", "green", "зелений", "зелёный"))
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.secondary, tx(language, "na styk", "borderline", "на межі", "на грани"))
        LegendItem(Modifier.weight(1f), MaterialTheme.colorScheme.tertiary, tx(language, "słabe", "poor", "слабо", "слабо"))
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
                color = Color(0xFF090C0F),
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
