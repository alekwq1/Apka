package pl.deliveryassistant.mvp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPrefs

    private var serviceEnabled by mutableStateOf(false)
    private var analysisEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs(this)
        analysisEnabled = prefs.analysisEnabled

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF167A4A),
                    secondary = Color(0xFF365B4A),
                    surface = Color(0xFFF9FBF9),
                    background = Color(0xFFF4F7F5)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        prefs = prefs,
                        serviceEnabled = serviceEnabled,
                        analysisEnabled = analysisEnabled,
                        toggleAnalysis = {
                            if (!serviceEnabled) {
                                /*
                                 * Android nie pozwala aplikacji samodzielnie
                                 * nadac sobie uprawnienia Accessibility.
                                 * Przy pierwszym wlaczeniu otwieramy wiec
                                 * systemowy ekran i uzytkownik wlacza usluge raz.
                                 */
                                prefs.analysisEnabled = true
                                analysisEnabled = true

                                startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            } else {
                                /*
                                 * Gdy dostep Accessibility jest juz nadany,
                                 * przycisk tylko wlacza/wylacza nasza analize.
                                 * Nie odbieramy systemowego uprawnienia.
                                 */
                                val newValue = !analysisEnabled
                                prefs.analysisEnabled = newValue
                                analysisEnabled = newValue
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        serviceEnabled = isAccessibilityServiceEnabled(this)
        analysisEnabled = prefs.analysisEnabled
    }
}

@Composable
private fun SettingsScreen(
    prefs: AppPrefs,
    serviceEnabled: Boolean,
    analysisEnabled: Boolean,
    toggleAnalysis: () -> Unit
) {
    var targetPackage by remember { mutableStateOf(prefs.targetPackage) }
    var vehicleCost by remember { mutableStateOf(formatSetting(prefs.vehicleCostPerKm)) }
    var minKm by remember { mutableStateOf(formatSetting(prefs.minimumNetPerKm)) }
    var toleranceKm by remember { mutableStateOf(formatSetting(prefs.toleranceNetPerKm)) }
    var minHour by remember { mutableStateOf(formatSetting(prefs.minimumNetPerHour)) }
    var toleranceHour by remember { mutableStateOf(formatSetting(prefs.toleranceNetPerHour)) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Delivery Assistant",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Szybka ocena, czy widoczna oferta kurierska sie oplaca. " +
                "Aplikacja niczego nie klika i nie przyjmuje zlecen za Ciebie.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ServiceStatusCard(
            serviceEnabled = serviceEnabled,
            analysisEnabled = analysisEnabled,
            toggleAnalysis = toggleAnalysis
        )

        InfoCard(
            title = "Jak testowac stare screenshoty",
            body = "Po wlaczeniu analizy po prostu otworz Galerie lub Zdjecia i wyswietl screenshot oferty na ekranie. " +
                "Delivery Assistant sprobuje odczytac go tak samo jak oferte widoczna w aplikacji kurierskiej."
        )

        InfoCard(
            title = "Jak liczymy czas",
            body = "Pyszne.pl: od aktualnej godziny telefonu do planowanej godziny dostawy, " +
                "np. 20:42 -> 20:57 = 15 min.\n\n" +
                "Uber: uzywamy czasu calkowitego podanego w ofercie, np. 26 min total.\n\n" +
                "Jesli wiarygodnego czasu nie ma, aplikacja go nie zgaduje i pokazuje BRAK CZASU."
        )

        InfoCard(
            title = "Co oznacza po kosztach",
            body = "To nie jest netto podatkowe. Liczymy: kwota oferty - (kilometry x koszt pojazdu/km). " +
                "W koszcie pojazdu mozesz uwzglednic paliwo/prad, serwis, opony i amortyzacje."
        )

        Text(
            text = "Twoje progi oplacalnosci",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        NumberField(
            label = "Koszt pojazdu",
            value = vehicleCost,
            suffix = "zl/km",
            help = "Koszt 1 km, np. paliwo/prad + serwis + opony + amortyzacja.",
            onChange = {
                vehicleCost = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na kilometr",
            value = minKm,
            suffix = "zl/km",
            help = "Kwota po kosztach pojazdu musi osiagnac co najmniej ten wynik na kilometr.",
            onChange = {
                minKm = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Tolerancja na kilometr",
            value = toleranceKm,
            suffix = "zl/km",
            help = "O ile ponizej minimum oferta moze zejsc i nadal byc oznaczona na zolto jako PRAWIE OPLACALNE.",
            onChange = {
                toleranceKm = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na godzine",
            value = minHour,
            suffix = "zl/h",
            help = "Stawka po kosztach pojazdu na godzine. To nie jest netto podatkowe.",
            onChange = {
                minHour = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Tolerancja na godzine",
            value = toleranceHour,
            suffix = "zl/h",
            help = "O ile ponizej minimum oferta moze zejsc i nadal byc oznaczona na zolto jako PRAWIE OPLACALNE.",
            onChange = {
                toleranceHour = it
                saveMessage = null
            }
        )

        Button(
            onClick = {
                val vehicle = vehicleCost.toDoublePl()?.takeIf { it >= 0.0 }
                val km = minKm.toDoublePl()?.takeIf { it >= 0.0 }
                val kmTolerance = toleranceKm.toDoublePl()?.takeIf { it >= 0.0 }
                val hour = minHour.toDoublePl()?.takeIf { it >= 0.0 }
                val hourTolerance = toleranceHour.toDoublePl()?.takeIf { it >= 0.0 }

                if (
                    vehicle == null ||
                    km == null ||
                    kmTolerance == null ||
                    hour == null ||
                    hourTolerance == null
                ) {
                    saveMessage = "Sprawdz wartosci - wpisz liczby wieksze lub rowne 0."
                } else {
                    prefs.vehicleCostPerKm = vehicle
                    prefs.minimumNetPerKm = km
                    prefs.toleranceNetPerKm = kmTolerance
                    prefs.minimumNetPerHour = hour
                    prefs.toleranceNetPerHour = hourTolerance
                    prefs.targetPackage = targetPackage
                    saveMessage = "Ustawienia zapisane."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zapisz ustawienia")
        }

        saveMessage?.let { message ->
            Text(
                text = message,
                color = if (message.startsWith("Ustawienia")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        HorizontalDivider()

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(
                if (showAdvanced) {
                    "Ukryj ustawienia zaawansowane"
                } else {
                    "Ustawienia zaawansowane"
                }
            )
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = targetPackage,
                onValueChange = {
                    targetPackage = it
                    saveMessage = null
                },
                label = { Text("Filtr package name") },
                supportingText = {
                    Text(
                        "Zwykle zostaw puste. Jesli wpiszesz pakiet konkretnej aplikacji kurierskiej, " +
                            "screenshoty otwarte w Galerii/Zdjeciach nadal beda analizowane."
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        InfoCard(
            title = "Nakladka nad oferta",
            body = "Zielony = oferta spelnia oba minima. Zolty PRAWIE OPLACALNE = oferta jest ponizej minimum, " +
                "ale miesci sie w ustawionej tolerancji. Czerwony = jest ponizej tolerancji. " +
                "Pomaranczowy BRAK CZASU = nie da sie uczciwie policzyc stawki godzinowej. " +
                "Nakladka nie przechwytuje dotyku."
        )
    }
}

@Composable
private fun ServiceStatusCard(
    serviceEnabled: Boolean,
    analysisEnabled: Boolean,
    toggleAnalysis: () -> Unit
) {
    val active = serviceEnabled && analysisEnabled

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                Color(0xFFE8F5ED)
            } else {
                Color(0xFFFFF4E5)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (active) {
                    "● Analiza ofert jest wlaczona"
                } else {
                    "● Analiza ofert jest wylaczona"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) {
                    Color(0xFF146C43)
                } else {
                    Color(0xFF8A4B00)
                }
            )

            Text(
                text = when {
                    !serviceEnabled ->
                        "Przy pierwszym wlaczeniu Android poprosi o jednorazowe wlaczenie uslugi Delivery Assistant w Dostepnosci."

                    active ->
                        "Analiza dziala w aplikacjach kurierskich oraz dla screenshotow otwartych normalnie w Galerii/Zdjeciach."

                    else ->
                        "Analiza jest zatrzymana. Dostep systemowy pozostaje wlaczony, dlatego ponowne wlaczenie dziala od razu."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = toggleAnalysis,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (active) {
                        "Wylacz analizowanie ofert"
                    } else {
                        "Wlacz analizowanie ofert"
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    help: String,
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
        modifier = Modifier.fillMaxWidth()
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        DeliveryAccessibilityService::class.java
    )

    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()

    return enabled
        .split(':')
        .any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
}

private fun String.toDoublePl(): Double? =
    replace(',', '.').toDoubleOrNull()

private fun formatSetting(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')
