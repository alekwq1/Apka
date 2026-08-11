# Delivery Assistant MVP

Prototyp Android/Kotlin, który:

1. odbiera zdarzenia z `AccessibilityService`,
2. czyta tekst z aktualnie widocznego okna,
3. wyszukuje kwotę (`17,52 zł`), dystans (`3,8 km`) i opcjonalnie czas (`15 min`),
4. liczy koszt przejazdu, netto, netto/km i netto/h,
5. pokazuje małą nakładkę `TYPE_ACCESSIBILITY_OVERLAY` nad aplikacją kuriera.

## Ważne

- To jest MVP do testów lokalnych/sideloadingu.
- Nie wykonuje kliknięć i nie akceptuje zleceń.
- Jeśli aplikacja kuriera nie udostępnia danych jako tekst dostępności, parser nic nie pokaże. Wtedy trzeba dodać wariant MediaProjection + OCR.
- Domyślny czas to 15 min, jeśli na ekranie nie ma tekstu typu `15 min`.
- Dla prywatności najlepiej wpisać dokładny `package name` aplikacji kuriera w ustawieniach MVP.

## Uruchomienie

1. Otwórz katalog w aktualnym Android Studio.
2. Poczekaj na Gradle Sync.
3. Zbuduj i zainstaluj apkę na telefonie z Androidem 8+.
4. Otwórz MVP i ustaw progi opłacalności.
5. Naciśnij **Włącz usługę** i w ustawieniach Androida aktywuj `Delivery Assistant - odczyt oferty`.
6. Otwórz aplikację kuriera i wyświetl ofertę.

## Jak ustalić package name aplikacji kuriera

Najprościej przez ADB na komputerze po otwarciu aplikacji:

```bash
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
```

albo:

```bash
adb shell pm list packages | grep -i courier
```

Wynik w stylu `com.example.courier` wpisz do pola w MVP.

## Formuła MVP

`netto = kwota - dystans * koszt_pojazdu_na_km`

`netto/km = netto / dystans`

`netto/h = netto / czas_min * 60`

Zlecenie jest oznaczane jako opłacalne, gdy jednocześnie spełnia próg netto/km i netto/h.

## Co dodać w następnej iteracji

- automatyczne wykrywanie package name,
- zapamiętywanie historii ofert,
- pływający przycisk do zmiany progów,
- pozycjonowanie/przeciąganie overlayu,
- parser dopasowany do konkretnej aplikacji Pyszne/Just Eat Courier,
- opcjonalny OCR przez MediaProjection, jeśli Accessibility API nie widzi tekstu,
- eksport statystyk dziennych.

---

## APK bez Android Studio

W tej paczce znajduje się gotowy workflow GitHub Actions: `.github/workflows/build-apk.yml`.
Dokładna instrukcja: **`INSTRUKCJA-GITHUB-APK.md`**.

Ręczne uruchomienie workflow (`Actions -> Build APK -> Run workflow`) buduje testowy APK i publikuje go jako testowy GitHub Release, żeby można było pobrać `.apk` bezpośrednio na telefon.
