# FUJARA - budowanie APK przez GitHub

Ta wersja projektu ma gotowy workflow GitHub Actions. Po jednorazowym wrzuceniu kodu do repozytorium możesz budować APK w przeglądarce, bez Android Studio.

## 1. Utwórz repozytorium

1. Zaloguj się do GitHub.
2. Utwórz nowe repozytorium, np. `delivery-assistant`.
3. Może być **Private**.
4. Nie dodawaj automatycznie README ani `.gitignore`, bo projekt już je ma.

## 2. Wgraj projekt

Wgraj **zawartość tego folderu**, czyli między innymi:

- `.github/`
- `app/`
- `build.gradle.kts`
- `settings.gradle.kts`

Ważne: plik `.github/workflows/build-apk.yml` musi znaleźć się dokładnie w tej ścieżce w repozytorium.

## 3. Uruchom budowanie

1. Otwórz repozytorium na GitHub.
2. Wejdź w zakładkę **Actions**.
3. Po lewej wybierz **Build APK**.
4. Kliknij **Run workflow**.
5. Kliknij zielony **Run workflow**.
6. Poczekaj, aż zadanie będzie miało zielony znacznik.

Workflow instaluje JDK 17, Android SDK 36 i Gradle 8.13, uruchamia testy i buduje `debug APK`.

## 4. Pobierz APK bezpośrednio na telefon

Przy ręcznym uruchomieniu `Run workflow` workflow tworzy testowe **Release**.

1. Wejdź na stronę główną repozytorium.
2. Otwórz **Releases**.
3. Wybierz najnowszy wpis `FUJARA - test #...`.
4. W `Assets` pobierz plik `delivery-assistant-test-....apk`.
5. Otwórz APK na telefonie i zezwól przeglądarce/menedżerowi plików na instalowanie aplikacji z tego źródła, jeśli Android o to poprosi.

Możesz też pobrać build z **Actions -> konkretny run -> Artifacts**, ale GitHub zwykle pakuje artifact do ZIP. Release jest wygodniejszy na telefonie, bo zawiera bezpośrednio `.apk`.

## 5. Kolejne wersje

Każdy ręczny `Run workflow` tworzy nowy testowy APK. Ta paczka nie zawiera stałego klucza testowego. Domyślny klucz debug może różnić się między maszynami/uruchomieniami CI, więc taki APK nie zawsze zaktualizuje aplikację już obecną na telefonie.

Aktualizację z zachowaniem danych podpisz tym samym kluczem co zainstalowaną wersję (albo użyj dotychczasowego kanału Google Play). Nie zastępuj istniejącego klucza. Przed aktualizacją wyeksportuj kopię danych z ustawień FUJARY. Jeśli Android odrzuci aktualizację z powodu podpisu, nie odinstalowuj aplikacji — najpierw sprawdź klucz podpisu.

## Gdy GitHub pokaże błąd

Otwórz nieudany run w **Actions**, wejdź w czerwony krok i skopiuj jego końcówkę albo zrób screenshot. Na tej podstawie można poprawić workflow lub kod.
