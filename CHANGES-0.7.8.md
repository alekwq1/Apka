# FUJARA 0.7.8

- Poprawione Pyszne.pl: calkowity czas zlecenia jest liczony jako `aktualny czas telefonu -> Dostarcz na HH:mm`.
- Parser Pyszne szuka `Odbierz na` i `Dostarcz na` w calym tekscie ekranu, a nie tylko w lokalnym fragmencie przy kwocie i dystansie.
- Bardziej odporny odczyt godzin z OCR: obslugiwane sa m.in. `13:37`, `13.37`, `13 37` oraz `1337` po etykiecie Pyszne.
- Gdy OCR odczyta kwote i dystans, ale zgubi mala linie z godzina dostawy, aplikacja probuje uzupelnic harmonogram z drzewa Accessibility tej samej oferty Pyszne.
- Dodane testy na karte z Gdanska: 20,56 zl, 5,3 km, teraz 13:18, `Dostarcz na 13:37` => 19 min calkowitego czasu.
- Wersja: `versionCode 16`, `versionName 0.7.8`.
