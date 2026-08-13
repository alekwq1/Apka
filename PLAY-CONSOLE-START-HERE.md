# FUJARA 0.7.2 — start w Google Play od zera

Ten projekt jest przygotowany do pierwszego wydania jako `pl.fujara.app` i targetuje Android 16 / API 36.

## 0. Najpierw: nie publikuj prywatnego klucza
Pliki `*.jks`, `*.keystore`, `*.base64.txt` i `upload-key*.txt` są ignorowane przez `.gitignore`.

Klucz upload jest potrzebny tylko do podpisywania pliku AAB wysyłanego do Google Play. Właściwy klucz podpisujący aplikację może przechowywać Google Play App Signing.

## 1. Wgraj wersję 0.7.2 do GitHuba
Po aktualizacji repo powinny istnieć workflowy:
- `.github/workflows/test-apk.yml`
- `.github/workflows/build-play-aab.yml`

## 2. Włącz GitHub Pages dla polityki prywatności
W repozytorium GitHub:
1. `Settings` → `Pages`.
2. `Build and deployment` → `Deploy from a branch`.
3. Branch: `main` (albo `master`).
4. Folder: `/docs`.
5. `Save`.

Po wdrożeniu polityka powinna być dostępna pod:
`https://alekwq1.github.io/Apka/privacy.html`

WAŻNE: przed wysłaniem aplikacji do recenzji sprawdź, czy w `docs/privacy.html` nadal widnieje aktualny adres kontaktowy `aleksanue@gmail.com`.

## 3. Dodaj 4 GitHub Actions Secrets
Repo → `Settings` → `Secrets and variables` → `Actions` → `New repository secret`.

Dodaj:
1. `FUJARA_UPLOAD_KEYSTORE_BASE64` — cała zawartość pliku `FUJARA-upload-key.base64.txt`.
2. `FUJARA_UPLOAD_STORE_PASSWORD` — wartość z prywatnego pliku z danymi klucza.
3. `FUJARA_UPLOAD_KEY_ALIAS` — `fujara-upload`.
4. `FUJARA_UPLOAD_KEY_PASSWORD` — wartość z prywatnego pliku z danymi klucza.

Nie wklejaj tych wartości do kodu ani Issues.

## 4. Zbuduj podpisany AAB
GitHub → `Actions` → `Build Google Play AAB` → `Run workflow`.

Po zielonym buildzie:
1. Otwórz run.
2. Na dole w `Artifacts` pobierz `FUJARA-play-aab-...`.
3. Rozpakuj ZIP z artifactu.
4. W środku będzie `app-release.aab` — to plik do Google Play.

## 5. Utwórz aplikację w Play Console
Na ekranie startowym kliknij `Create app`.

Ustaw:
- App name: `FUJARA`
- Default language: `Polish – pl-PL`
- App or game: `App`
- Free or paid: `Free`

Zaakceptuj wymagane deklaracje i utwórz aplikację.

Package name zostanie powiązany przy pierwszym AAB. W tej wersji ma być `pl.fujara.app`.

## 6. Store listing
Wgraj/uzupełnij:
- nazwa: `FUJARA`
- krótki opis: `Sprawdź, ile zostaje po kosztach, zanim przyjmiesz zlecenie.`
- pełny opis: z `GOOGLE-PLAY-LISTING-PL.md`
- ikona Google Play: 512×512 PNG/JPG zgodna z marką FUJARA
- feature graphic: 1024×500
- min. 2 dobre screenshoty telefonu pokazujące aplikację i nakładkę
- kategoria: najlepiej `Tools` albo najbliższa dostępna kategoria narzędziowa
- kontakt: Twój prawdziwy adres e-mail
- privacy policy: `https://alekwq1.github.io/Apka/privacy.html`

## 7. App content — odpowiedzi
Skorzystaj z pliku `PLAY-CONSOLE-ANSWERS-PL.md`. Najważniejsze:
- Ads: `No`
- App access: brak logowania / specjalnego konta do wejścia do aplikacji
- Target audience: `18 and over`
- Data safety: aplikacja nie wysyła danych poza urządzenie — deklaracja ma odzwierciedlać faktyczny build
- AccessibilityService: `FUJARA nie jest accessibility tool` i trzeba wypełnić osobną deklarację

## 8. AccessibilityService — obowiązkowe
W formularzu Play Console opisz, że AccessibilityService służy do rozpoznania widocznej karty oferty kurierskiej i lokalnego obliczenia opłacalności. Nie zaznaczaj, że aplikacja jest narzędziem dla osób z niepełnosprawnościami.

Google wymaga także krótkiego filmu pokazującego:
1. uruchomienie FUJARA,
2. cały ekran disclosure,
3. naciśnięcie `Rozumiem`,
4. przejście do ustawień Dostępności,
5. ręczne włączenie FUJARA.

Film może być niepubliczny w YouTube, ale link musi być dostępny dla recenzenta.

## 9. Pierwszy upload — najlepiej Closed testing
`Test and release` → `Testing` → `Closed testing`.

Utwórz track, np. `beta`, i dodaj testerów. W nowym osobistym koncie Google Play wymagane jest co najmniej 12 testerów zapisanych nieprzerwanie przez 14 dni, zanim będzie można wystąpić o dostęp do produkcji.

W release dodaj `app-release.aab` i opis zmian, np.:
`Pierwsza wersja testowa FUJARA. Lokalna analiza opłacalności ofert kurierskich.`

## 10. Instrukcja dla recenzenta
W aplikacji 0.7.2 jest przycisk `Pokaż podgląd nakładki`. Dzięki temu recenzent może zobaczyć sposób prezentacji wyniku bez konta kuriera i bez oczekiwania na prawdziwą ofertę.

Tekst do pola review/app access znajdziesz w `APP-REVIEW-INSTRUCTIONS.md`.

## 11. Po 14 dniach
Gdy co najmniej 12 testerów będzie zapisanych przez 14 dni bez przerwy:
1. Play Console → Dashboard.
2. `Apply for production access`.
3. Odpowiedz na pytania o test, feedback i gotowość aplikacji.
4. Po uzyskaniu dostępu utwórz release produkcyjny.

## 12. Przy każdej kolejnej wersji
- zwiększ `versionCode` (7, 8, 9...)
- ustaw nowe `versionName`
- uruchom `Build Google Play AAB`
- wrzuć nowy AAB na odpowiedni track

Nie zmieniaj `applicationId = "pl.fujara.app"` po pierwszym powiązaniu aplikacji w Google Play.
