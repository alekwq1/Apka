# FUJARA — propozycje odpowiedzi w Play Console

Stan dla wersji 0.5.1. Odpowiedzi muszą zawsze odpowiadać faktycznemu buildowi.

## Ads
**Does your app contain ads?** → `No`

## App access
FUJARA nie ma logowania, subskrypcji ani konta użytkownika. Do działania na żywo użytkownik sam włącza AccessibilityService w ustawieniach Androida.

Jeżeli formularz pyta o ograniczony dostęp wymagający loginu, wybierz odpowiedź wskazującą, że funkcje aplikacji nie są ograniczone przez dane logowania.

W informacjach dla recenzenta dodaj instrukcję z `APP-REVIEW-INSTRUCTIONS.md`.

## Target audience
Rekomendacja: wybierz tylko grupę **18 and over**. Aplikacja jest narzędziem pracy dla kurierów, nie jest projektowana dla dzieci.

## News apps
`No`.

## Government apps
`No`.

## Financial features
FUJARA nie świadczy usług finansowych, nie obsługuje płatności, kredytów, inwestycji ani portfeli. To kalkulator opłacalności zlecenia. Jeżeli pojawi się pytanie o funkcje finansowe, wybierz brak takich funkcji.

## Data safety
Dla obecnego kodu:
- brak permission `INTERNET`,
- brak reklam,
- brak analytics/Firebase,
- brak konta,
- OCR i kalkulacja działają lokalnie.

W związku z tym dane oferty nie są transmitowane poza urządzenie przez FUJARA. W formularzu Data safety odpowiedzi o `collection` i `sharing` powinny odzwierciedlać brak przesyłania danych poza urządzenie.

UWAGA: AccessibilityService nadal jest dostępem do wrażliwego API i ma osobną deklarację niezależnie od Data safety.

## Privacy policy
URL po włączeniu GitHub Pages:
`https://alekwq1.github.io/Apka/privacy.html`

Przed publikacją koniecznie uzupełnij w polityce prawdziwy e-mail dewelopera.

## Content rating
Typ aplikacji: narzędzie / kalkulator dla kurierów. Odpowiadaj zgodnie z prawdą: brak przemocy, seksu, hazardu, narkotyków i zakupów losowych.

## AccessibilityService declaration
- Is the app an accessibility tool? → `No`.
- Cel: rozpoznanie widocznej karty oferty kurierskiej i lokalne obliczenie opłacalności przed samodzielną decyzją użytkownika.
- Dane: widoczny tekst oferty — kwota, dystans, czas, planowana godzina odbioru/dostawy, jeśli jest pokazana.
- Udostępnianie danych: brak.
- Automatyzacja: aplikacja nie klika, nie akceptuje i nie odrzuca zleceń.
- Zgoda: przed otwarciem systemowych ustawień FUJARA pokazuje osobny ekran disclosure i wymaga naciśnięcia `Rozumiem`.

## Accessibility video
Nagraj około 30–60 sekund:
1. świeże uruchomienie FUJARA,
2. pokaż cały ekran wyjaśniający AccessibilityService,
3. naciśnij `Rozumiem`,
4. `Otwórz ustawienia`,
5. pokaż ręczne włączenie FUJARA w Accessibility,
6. wróć do aplikacji,
7. pokaż ekran główny i `Pokaż demo obliczeń`.

Nie pokazuj w filmie prywatnych powiadomień ani danych innych osób.
