package pl.deliveryassistant.mvp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        prefs = AppPrefs(this),
                        openAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(prefs: AppPrefs, openAccessibility: () -> Unit) {
    var targetPackage by remember { mutableStateOf(prefs.targetPackage) }
    var vehicleCost by remember { mutableStateOf(prefs.vehicleCostPerKm.toString()) }
    var minKm by remember { mutableStateOf(prefs.minimumNetPerKm.toString()) }
    var minHour by remember { mutableStateOf(prefs.minimumNetPerHour.toString()) }
    var fallbackMinutes by remember { mutableStateOf(prefs.fallbackMinutes.toString()) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Delivery Assistant MVP", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Aplikacja tylko odczytuje widoczną ofertę i pokazuje obliczenia w nakładce. " +
                "Nie akceptuje zleceń automatycznie."
        )

        OutlinedTextField(
            value = targetPackage,
            onValueChange = { targetPackage = it; saved = false },
            label = { Text("Pakiet aplikacji kuriera (opcjonalnie)") },
            supportingText = { Text("Puste = parser działa na aktywnym oknie tylko wtedy, gdy znajdzie kwotę i km.") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        NumberField("Koszt pojazdu / km [zł]", vehicleCost) { vehicleCost = it; saved = false }
        NumberField("Minimalne netto / km [zł]", minKm) { minKm = it; saved = false }
        NumberField("Minimalne netto / godz. [zł]", minHour) { minHour = it; saved = false }
        NumberField("Domyślny czas, gdy brak minut", fallbackMinutes) { fallbackMinutes = it; saved = false }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                prefs.targetPackage = targetPackage
                prefs.vehicleCostPerKm = vehicleCost.toDoublePl() ?: prefs.vehicleCostPerKm
                prefs.minimumNetPerKm = minKm.toDoublePl() ?: prefs.minimumNetPerKm
                prefs.minimumNetPerHour = minHour.toDoublePl() ?: prefs.minimumNetPerHour
                prefs.fallbackMinutes = fallbackMinutes.toIntOrNull()?.coerceIn(1, 180) ?: prefs.fallbackMinutes
                saved = true
            }) {
                Text("Zapisz")
            }

            Button(onClick = openAccessibility) {
                Text("Włącz usługę")
            }
        }

        if (saved) Text("Zapisano ustawienia.")
        Spacer(Modifier.height(4.dp))
        Text(
            "MVP rozpoznaje wzorce np. „17,52 zł”, „3,8 km” i opcjonalnie „15 min”. " +
                "Jeśli aplikacja kurierska nie udostępnia tekstu w drzewie dostępności, kolejnym krokiem będzie OCR z MediaProjection."
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun String.toDoublePl(): Double? = replace(',', '.').toDoubleOrNull()
