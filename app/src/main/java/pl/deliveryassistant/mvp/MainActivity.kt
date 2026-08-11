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
import androidx.compose.foundation.layout.Row
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
    private var serviceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        prefs = AppPrefs(this),
                        serviceEnabled = serviceEnabled,
                        openAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isAccessibilityServiceEnabled(this)
    }
}

@Composable
private fun SettingsScreen(
    prefs: AppPrefs,
    serviceEnabled: Boolean,
    openAccessibility: () -> Unit
) {
    var targetPackage by remember { mutableStateOf(prefs.targetPackage) }
    var vehicleCost by remember { mutableStateOf(formatSetting(prefs.vehicleCostPerKm)) }
    var minKm by remember { mutableStateOf(formatSetting(prefs.minimumNetPerKm)) }
    var minHour by remember { mutableStateOf(formatSetting(prefs.minimumNetPerHour)) }
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
            text = "Szybka ocena, czy widoczna oferta kurierska się opłaca. " +
                "Aplikacja niczego nie klika i nie przyjmuje zleceń za Ciebie.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ServiceStatusCard(
            enabled = serviceEnabled,
            openAccessibility = openAccessibility
        )

        InfoCard(
            title = "Jak liczymy czas",
            body = "Pyszne.pl: od aktualnej godziny telefonu do planowanej godziny dostawy, " +
                "np. 20:42 → 20:57 = 15 min.\n\n" +
                "Uber: używamy czasu całkowitego podanego w ofercie, np. 26 min total.\n\n" +
                "Jeśli wiarygodnego czasu nie ma, aplikacja go nie zgaduje i pokazuje „BRAK CZASU”."
        )

        InfoCard(
            title = "Co oznacza „po kosztach”",
            body = "To nie jest netto podatkowe. Liczymy: kwota oferty − (kilometry × koszt pojazdu/km). " +
                "W koszcie pojazdu możesz uwzględnić paliwo/prąd, serwis, opony i amortyzację."
        )

        Text(
            text = "Twoje progi opłacalności",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        NumberField(
            label = "Koszt pojazdu",
            value = vehicleCost,
            suffix = "zł/km",
            help = "Twój koszt 1 km: np. paliwo/prąd + serwis + opony + amortyzacja. Ta wartość jest odejmowana od kwoty oferty.",
            onChange = {
                vehicleCost = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na kilometr",
            value = minKm,
            suffix = "zł/km",
            help = "Kwota po kosztach pojazdu musi osiągnąć co najmniej ten wynik na kilometr.",
            onChange = {
                minKm = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na godzinę",
            value = minHour,
            suffix = "zł/h",
            help = "Stawka po kosztach pojazdu na godzinę. To nie jest netto podatkowe.",
            onChange = {
                minHour = it
                saveMessage = null
            }
        )

        Button(
            onClick = {
                val vehicle = vehicleCost.toDoublePl()?.takeIf { it >= 0.0 }
                val km = minKm.toDoublePl()?.takeIf { it >= 0.0 }
                val hour = minHour.toDoublePl()?.takeIf { it >= 0.0 }

                if (vehicle == null || km == null || hour == null) {
                    saveMessage = "Sprawdź wartości — wpisz liczby większe lub równe 0."
                } else {
                    prefs.vehicleCostPerKm = vehicle
                    prefs.minimumNetPerKm = km
                    prefs.minimumNetPerHour = hour
                    prefs.targetPackage = targetPackage
                    saveMessage = "Ustawienia zapisane."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zapisz ustawienia")
        }

        saveMessage?.let {
            Text(
                text = it,
                color = if (it.startsWith("Ustawienia")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        HorizontalDivider()

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Ukryj ustawienia zaawansowane" else "Ustawienia zaawansowane")
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
                        "Zwykle zostaw puste — aplikacja sama rozpoznaje Pyszne.pl i Uber. " +
                            "Wpisz pakiet tylko wtedy, gdy chcesz analizować wyłącznie jedną aplikację."
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        InfoCard(
            title = "Nakładka nad ofertą",
            body = "Zielony = oferta spełnia oba Twoje progi. Czerwony = nie spełnia. " +
                "Pomarańczowy „BRAK CZASU” = nie da się uczciwie policzyć stawki godzinowej. " +
                "Nakładka nie przechwytuje dotyku, więc możesz normalnie używać aplikacji kuriera."
        )
    }
}

@Composable
private fun ServiceStatusCard(
    enabled: Boolean,
    openAccessibility: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFFE8F5ED) else Color(0xFFFFF4E5)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "● Analiza ofert jest włączona" else "● Analiza ofert jest wyłączona",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color(0xFF146C43) else Color(0xFF8A4B00)
            )
            Text(
                text = if (enabled) {
                    "Możesz przejść do Pyszne.pl lub Ubera. Gdy pojawi się oferta, zobaczysz małą nakładkę."
                } else {
                    "Aby aplikacja mogła odczytać widoczną ofertę, włącz usługę Delivery Assistant w ustawieniach dostępności Androida."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = openAccessibility) {
                Text(if (enabled) "Ustawienia usługi" else "Włącz analizę ofert")
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
        .any { entry -> ComponentName.unflattenFromString(entry) == expected }
}

private fun String.toDoublePl(): Double? = replace(',', '.').toDoubleOrNull()

private fun formatSetting(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')
