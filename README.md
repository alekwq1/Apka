# Delivery Assistant

Android/Kotlin — pomocnik do szybkiej oceny widocznej oferty kurierskiej.

Aplikacja:

1. najpierw odczytuje tekst z `AccessibilityService`,
2. jeśli trzeba, używa OCR z ML Kit na screenshocie,
3. rozpoznaje kwotę i dystans,
4. dla **Pyszne.pl** odczytuje planowany odbiór i dostawę, np. `Odbierz na 20:54` / `Dostarcz na 20:57`,
5. dla **Ubera** wykorzystuje podany w ofercie czas całkowity, np. `26 min total`,
6. liczy koszt pojazdu, netto, netto/km i netto/h,
7. pokazuje wynik w małej nakładce nad aplikacją kuriera.

## Jak liczony jest czas

### Pyszne.pl

Nie ma już sztucznego domyślnego czasu 15 minut.

Czas oferty jest liczony jako:

`planowana godzina dostawy - aktualna godzina telefonu`

Przykład:

- teraz: `20:42`,
- odbiór: `20:54`,
- planowana dostawa: `20:57`,
- czas do dostawy: `15 min`.

Obsługiwane jest również przejście przez północ, np. `23:58 -> 00:10 = 12 min`.

### Uber

Jeżeli oferta zawiera np. `26 min (5.4 km) total`, aplikacja używa bezpośrednio `26 min`.

### Brak czasu

Jeżeli nie da się wiarygodnie ustalić czasu, aplikacja **nie zgaduje**. Nakładka pokazuje `BRAK CZASU`, a stawka godzinowa pozostaje pusta.

## Formuły

`netto = kwota - dystans * koszt_pojazdu_na_km`

`netto/km = netto / dystans`

`netto/h = netto / czas_min * 60`

Wartość netto może być ujemna — nie jest już sztucznie ograniczana do zera.

Oferta jest oznaczona jako opłacalna tylko wtedy, gdy znamy czas i jednocześnie spełnia próg netto/km oraz netto/h.

## OCR i nakładka

- Android 14+: aplikacja próbuje robić screenshot konkretnego okna kuriera przez `takeScreenshotOfWindow()`, dzięki czemu własna nakładka nie zasłania OCR.
- Android 11-13: screenshot całego ekranu maskuje tylko dokładny obszar własnej nakładki, zamiast dużego stałego prostokąta.
- Screenshoty są ograniczane czasowo, aby nie wywoływać błędu `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`.
- Nakładka ma `FLAG_NOT_TOUCHABLE`, więc nie blokuje przycisków aplikacji kurierskiej.

## Filtrowanie aplikacji

Domyślnie Delivery Assistant próbuje automatycznie rozpoznawać Pyszne.pl i Ubera. W ustawieniach zaawansowanych można wpisać dokładny `package name`, aby ograniczyć analizę tylko do jednej aplikacji.

Jeśli filtr jest ustawiony, jest faktycznie respektowany zarówno podczas odczytu Accessibility, jak i przy wyborze okna do OCR.

## Uruchomienie

1. Otwórz projekt w aktualnym Android Studio.
2. Poczekaj na Gradle Sync.
3. Zbuduj i zainstaluj APK na Androidzie 8+.
4. Ustaw koszt pojazdu i progi opłacalności.
5. Naciśnij **Włącz analizę ofert** i aktywuj `Delivery Assistant - odczyt oferty` w ustawieniach dostępności.
6. Otwórz Pyszne.pl albo Ubera i wyświetl ofertę.

## Package name — opcjonalnie

Zwykle nie trzeba nic wpisywać. Jeśli chcesz ograniczyć aplikację do jednego pakietu, możesz ustalić go przez ADB:

```bash
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
```

albo:

```bash
adb shell pm list packages | grep -i courier
```

## Bezpieczeństwo działania

- aplikacja niczego automatycznie nie akceptuje,
- nie wykonuje kliknięć,
- nie wysyła odczytanych ofert na serwer,
- obliczenia wykonywane są lokalnie na telefonie.

## Testy

Testy jednostkowe obejmują m.in.:

- format Pyszne.pl z godzinami odbioru i dostawy,
- OCR z kropką zamiast dwukropka w godzinie,
- format Ubera `PLN25.42 / 26 min / 5.4 km`,
- kilka ofert na jednym ekranie,
- liczenie czasu do dostawy przez północ,
- brak czasu bez wymyślania wartości zastępczej,
- zachowanie ujemnego netto.

## APK bez Android Studio

Workflow `.github/workflows/build-apk.yml` uruchamia testy i buduje debug APK.
Szczegóły: `INSTRUKCJA-GITHUB-APK.md`.
