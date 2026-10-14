# FUJARA 1.0.1 — naprawa podsumowania Pyszne

## Przyczyna
Na przekazanym ekranie z 13.10.2026 widnieje „10 przyjętych zleceń”. Parser 1.0.0 rozpoznawał „10 zleceń” i „10 offers accepted”, ale nie dodatkowe słowo przed „zleceń”. Dlatego pomimo 10 zapisów i 290,94 zł nie powstawała kontrola dnia, a przycisk pozostawał nieaktywny. Lista pokazuje też numery bez #, podczas gdy stary parser listy wymagał #.

## Zmiany
- Rozpoznawanie nowych i starszych polskich/angielskich etykiet liczby zleceń, także rozdzielonych na kilka węzłów tekstu.
- Rozpoznawanie numerów bez #, również samych liter (CPPXDD, CBYWRH), na osobnej linii przy kwocie. Zachowana obsługa numerów z # i deduplikacja.
- Obsługa twardych/wąskich spacji, osobnej linii waluty oraz kwot z separatorem tysięcy.
- Kwota musi znajdować się przy etykiecie przychodu. Parser nie przechodzi przez następną sekcję do kwoty restauracji.
- Sprzeczne liczby/kwoty, różne daty i podsumowanie tygodnia są odrzucane. Nie ma zgadywania daty ani ręcznego obchodzenia kontroli.
- Powrót na ekran tygodnia zeruje kontekst zbierania numerów poprzedniego dnia.
- Identyczne odczyty nagłówka/listy nie powodują kolejnych zapisów JSON i przeliczania przypisania zleceń do dni.
- Czytelniejsza instrukcja w aplikacji i pola napiwku/przestoju na pełną szerokość.
- Wersja 1.0.1, versionCode 40; ten sam applicationId i format danych.
- Poprawiona instrukcja podpisywania APK: archiwum nie zawiera stałego klucza debug.

## Aktualizacja kodu
1. Wyeksportuj kopię danych w ustawieniach obecnej FUJARY.
2. Rozpakuj ZIP. Skopiuj zawartość folderu FUJARA-1.0.1 do swojego projektu (nie twórz dodatkowego poziomu folderów).
3. Zachowaj swój folder .git, local.properties oraz dotychczasowy klucz i konfigurację podpisu.
4. W Visual Studio Code sprawdź zmiany i wykonaj commit/push do istniejącego repozytorium FUJARY.
5. Dotychczasowy workflow `Build APK` uruchamia `testDebugUnitTest assembleDebug`. Alternatywnie użyj Android Studio z JDK 17 i SDK 36.
6. Zainstaluj jako aktualizację podpisaną tym samym kluczem co obecna aplikacja. Nie odinstalowuj obecnej wersji w celu obejścia konfliktu podpisu.

## Sprawdzenie na telefonie
1. Sprawdź w ustawieniach Androida, czy usługa dostępności FUJARY jest włączona.
2. W Pyszne: Historia przychodów → tydzień → 13 października 2026.
3. Przewiń na górę: data, 290,94 zł, „10 przyjętych zleceń”. Zatrzymaj ekran na 2–3 sekundy.
4. Wróć do FUJARY → Dzień → 13 października. Kontrola powinna pokazać 10 i 290,94 zł.
5. Przy 10 zapisach o sumie 290,94 zł wybierz „Potwierdź i policz”.
6. Opcjonalnie przewiń listę Pyszne, aby uzupełnić identyfikatory. Nie zapisuj ponownie wszystkich dostaw tylko z powodu tej aktualizacji.

## Weryfikacja i ograniczenia
Uruchomiono testy JVM parserów, obliczeń i stabilizatora panelu, w tym 18 nowych testów regresyjnych. Fixture nowego ekranu to ręczna transkrypcja tekstu przekazanego zdjęcia, nie rzeczywisty zrzut drzewa Accessibility/OCR.

Środowisko nie posiada Android SDK/Gradle. Rdzeń Kotlin skompilowano kompilatorem 2.3.21 i przetestowano przez JUnit 4.13.2; enumy ustawień zostały wyodrębnione bez zmian z AppPrefs.kt, Android API służyło tylko jako classpath. Nie uruchomiono testów UI, testu AnalysisCalendarTest ani pełnego budowania APK. Wynik testów rdzenia znajduje się w TEST-RESULTS.txt. Pełne testy Gradle i budowa APK pozostają w dołączonym workflow.

Odczyt na konkretnym telefonie wymaga sprawdzenia po instalacji: widoczny tekst na zdjęciu nie gwarantuje, że Pyszne udostępnia identyczną treść usłudze dostępności lub OCR. Jeśli kontrola nadal nie pojawi się, potrzebny będzie wynik tej próby na urządzeniu.
