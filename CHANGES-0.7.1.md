# FUJARA 0.7.1

## Poprawki po testach 13.08.2026

- Wolt: obsługa angielskiej karty `PLN 10.27` + `Expected earnings for the full delivery`.
- OCR: liczby z mapy (np. numer drogi 221) nie są już łączone z `PLN` z innej linii.
- OCR: tekst karty może poprawić identyfikację platformy, gdy Android/launcher pozostawi stary sygnał aplikacji.
- Parsery Uber/Wolt/Bolt/Stuart po rozpoznaniu platformy nie wpadają już w ryzykowny ogólny fallback.
- Stuart: brak czasu w ofercie pozostaje poprawnie oznaczony jako brak wiarygodnego czasu.
- Ustawienia: komunikat automatycznego zapisu jest Toastem i nie przesuwa ekranu podczas pracy z suwakami.
- Ustawienia: etykiety `super / żółte / bieda` zmienione na `opłacalna / na granicy / nieopłacalna`.
- Ustawienia: usunięta sekcja `Ustawienia zaawansowane` i czyszczony jest stary filtr package name.
- Nakładka: większa szerokość i automatyczne dopasowanie rozmiaru wartości, żeby `zł/km` i `zł/h` nie były ucinane przy dużej czcionce.
- Dostępność: przed otwarciem ustawień Android pokazuje krótką instrukcję `Zainstalowane aplikacje -> FUJARA -> Włącz`. Publiczne API Androida nie gwarantuje podświetlenia konkretnej pozycji w ustawieniach producenta.
- Wersja: `versionCode 9`, `versionName 0.7.1`.
