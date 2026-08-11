package pl.deliveryassistant.mvp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalTime
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var imageCheckState by mutableStateOf<ImageCheckState>(ImageCheckState.Idle)

    private lateinit var prefs: AppPrefs
    private var imageRecognizer: TextRecognizer? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyzeOfferImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs(this)
        imageRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
                        imageCheckState = imageCheckState,
                        openAccessibility = ::openAccessibilitySettings,
                        disableAnalysis = {
                            if (DeliveryAccessibilityService.requestDisable()) {
                                serviceEnabled = false
                            } else {
                                openAccessibilitySettings()
                            }
                        },
                        pickOfferImage = {
                            imagePicker.launch("image/*")
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

    override fun onDestroy() {
        imageRecognizer?.close()
        imageRecognizer = null
        super.onDestroy()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun analyzeOfferImage(uri: Uri) {
        val scanner = imageRecognizer
        if (scanner == null) {
            imageCheckState = ImageCheckState.Failed("OCR nie jest gotowy.")
            return
        }

        val image = runCatching { InputImage.fromFilePath(this, uri) }
            .getOrElse { error ->
                imageCheckState = ImageCheckState.Failed(
                    error.message ?: "Nie udalo sie otworzyc zdjecia."
                )
                return
            }

        imageCheckState = ImageCheckState.Loading

        scanner.process(image)
            .addOnSuccessListener { result ->
                val resolution = OcrTextResolver.resolve(result)
                val rawText = OcrTextResolver.displayText(result).take(MAX_DIAGNOSTIC_TEXT)

                if (resolution == null) {
                    imageCheckState = ImageCheckState.NotRecognized(rawText)
                    return@addOnSuccessListener
                }

                val now = LocalTime.now()
                val profitability = ProfitabilityCalculator.calculate(
                    offer = resolution.offer,
                    rules = currentRules(prefs),
                    currentMinuteOfDay = now.hour * 60 + now.minute
                )

                imageCheckState = ImageCheckState.Recognized(
                    profitability = profitability,
                    rawText = rawText
                )
            }
            .addOnFailureListener { error ->
                imageCheckState = ImageCheckState.Failed(
                    error.message ?: "OCR nie mogl odczytac zdjecia."
                )
            }
    }

    private companion object {
        const val MAX_DIAGNOSTIC_TEXT = 6000
    }
}

private sealed interface ImageCheckState {
    data object Idle : ImageCheckState
    data object Loading : ImageCheckState
    data class Recognized(
        val profitability: Profitability,
        val rawText: String
    ) : ImageCheckState

    data class NotRecognized(val rawText: String) : ImageCheckState
    data class Failed(val message: String) : ImageCheckState
}

@Composable
private fun SettingsScreen(
    prefs: AppPrefs,
    serviceEnabled: Boolean,
    imageCheckState: ImageCheckState,
    openAccessibility: () -> Unit,
    disableAnalysis: () -> Unit,
    pickOfferImage: () -> Unit
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
            text = "Szybka ocena, czy widoczna oferta kurierska sie oplaca. " +
                "Aplikacja niczego nie klika i nie przyjmuje zlecen za Ciebie.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ServiceStatusCard(
            enabled = serviceEnabled,
            openAccessibility = openAccessibility,
            disableAnalysis = disableAnalysis
        )

        ImageOcrCard(
            state = imageCheckState,
            pickOfferImage = pickOfferImage
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
            help = "Twoj koszt 1 km: np. paliwo/prad + serwis + opony + amortyzacja. Ta wartosc jest odejmowana od kwoty oferty.",
            onChange = {
                vehicleCost = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na kilometr",
            value = minKm,
            suffix = "zl/km",
            help = "Kwota po kosztach pojazdu musi osiagnac co najmniej ten wynik na kilometr. Zmiana progu nie wylacza OCR.",
            onChange = {
                minKm = it
                saveMessage = null
            }
        )

        NumberField(
            label = "Minimum po kosztach na godzine",
            value = minHour,
            suffix = "zl/h",
            help = "Stawka po kosztach pojazdu na godzine. To nie jest netto podatkowe. Zmiana progu tylko zmienia ocene oplacalnosci.",
            onChange = {
                minHour = it
                saveMessage = null
            }
        )

        Button(
            onClick = {
                val vehicle = vehicleCost.toDoublePl()?.takeIf { it.isFinite() && it >= 0.0 }
                val km = minKm.toDoublePl()?.takeIf { it.isFinite() && it >= 0.0 }
                val hour = minHour.toDoublePl()?.takeIf { it.isFinite() && it >= 0.0 }

                if (vehicle == null || km == null || hour == null) {
                    saveMessage = "Sprawdz wartosci - wpisz liczby wieksze lub rowne 0."
                } else {
                    prefs.vehicleCostPerKm = vehicle
                    prefs.minimumNetPerKm = km
                    prefs.minimumNetPerHour = hour
                    prefs.targetPackage = targetPackage
                    saveMessage = "Ustawienia zapisane. Analizowanie ofert pozostaje wlaczone."
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
                        "Zwykle zostaw puste - aplikacja sama rozpoznaje Pyszne.pl i Uber. " +
                            "Wpisz pakiet tylko wtedy, gdy chcesz analizowac wylacznie jedna aplikacje."
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        InfoCard(
            title = "Nakladka nad oferta",
            body = "Zielony = oferta spelnia oba Twoje progi. Czerwony = nie spelnia. " +
                "Pomarańczowy BRAK CZASU = nie da sie uczciwie policzyc stawki godzinowej. " +
                "Niezaleznie od progu rozpoznana oferta ma byc pokazana."
        )
    }
}

@Composable
private fun ServiceStatusCard(
    enabled: Boolean,
    openAccessibility: () -> Unit,
    disableAnalysis: () -> Unit
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
                text = if (enabled) "● Analiza ofert jest wlaczona" else "● Analiza ofert jest wylaczona",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color(0xFF146C43) else Color(0xFF8A4B00)
            )
            Text(
                text = if (enabled) {
                    "Mozesz przejsc do aplikacji kurierskiej. Przycisk ponizej wylacza usluge bez szukania jej w ustawieniach Androida."
                } else {
                    "Aby aplikacja mogla odczytywac widoczna oferte, wlacz usluge Delivery Assistant w ustawieniach dostepnosci Androida."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (enabled) {
                Button(
                    onClick = disableAnalysis,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wylacz analizowanie ofert")
                }
            } else {
                Button(
                    onClick = openAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wlacz analizowanie ofert")
                }
            }

            OutlinedButton(
                onClick = openAccessibility,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ustawienia dostepnosci")
            }
        }
    }
}

@Composable
private fun ImageOcrCard(
    state: ImageCheckState,
    pickOfferImage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Test OCR ze zdjecia",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Wybierz stary screenshot oferty z galerii. Ten test dziala niezaleznie od tego, jaka aplikacja jest aktualnie otwarta.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = pickOfferImage,
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is ImageCheckState.Loading
            ) {
                Text(if (state is ImageCheckState.Loading) "Odczytuje zdjecie..." else "Sprawdz zdjecie oferty")
            }

            when (state) {
                ImageCheckState.Idle -> Unit
                ImageCheckState.Loading -> Text("OCR analizuje wybrane zdjecie...")
                is ImageCheckState.Failed -> Text(
                    text = "Blad OCR: ${state.message}",
                    color = MaterialTheme.colorScheme.error
                )
                is ImageCheckState.NotRecognized -> {
                    Text(
                        text = "OCR odczytal tekst, ale parser nie znalazl kompletnej oferty (kwota + dystans).",
                        color = Color(0xFF8A4B00),
                        fontWeight = FontWeight.SemiBold
                    )
                    RawOcrText(state.rawText)
                }
                is ImageCheckState.Recognized -> {
                    DiagnosticResult(state.profitability)
                    RawOcrText(state.rawText)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticResult(result: Profitability) {
    val status = when (result.profitable) {
        true -> "OPLACALNE"
        false -> "NIEOPLACALNE"
        null -> "BRAK CZASU"
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Rozpoznano oferte: $status", fontWeight = FontWeight.Bold)
        Text("Kwota: ${money(result.grossPln)}")
        Text("Trasa: ${number(result.distanceKm)} km")
        Text("Czas: ${result.durationMinutes?.let { "$it min" } ?: "--"}")
        Text("Po kosztach: ${money(result.netPln)}")
        Text("Po kosztach/km: ${result.netPerKm?.let { "${money(it)}/km" } ?: "--"}")
        Text("Po kosztach/h: ${result.netPerHour?.let { "${money(it)}/h" } ?: "--"}")
    }
}

@Composable
private fun RawOcrText(text: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text("Surowy tekst OCR", fontWeight = FontWeight.SemiBold)
    SelectionContainer {
        Text(
            text = text.ifBlank { "OCR nie zwrocil tekstu." },
            style = MaterialTheme.typography.bodySmall
        )
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

private fun currentRules(prefs: AppPrefs): ProfitabilityCalculator.Rules =
    ProfitabilityCalculator.Rules(
        vehicleCostPerKm = prefs.vehicleCostPerKm,
        minimumNetPerKm = prefs.minimumNetPerKm,
        minimumNetPerHour = prefs.minimumNetPerHour
    )

private fun String.toDoublePl(): Double? =
    trim().replace(',', '.').toDoubleOrNull()

private fun formatSetting(value: Double): String =
    String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')

private fun money(value: Double): String =
    String.format(Locale.forLanguageTag("pl-PL"), "%.2f zl", value)

private fun number(value: Double): String =
    String.format(Locale.forLanguageTag("pl-PL"), "%.2f", value)
