# FUJARA — AccessibilityService / Google Play declaration

FUJARA **nie jest** narzędziem dostępności dla osób z niepełnosprawnościami (`isAccessibilityTool=false`).

## Cel użycia AccessibilityService
Usługa jest używana do rozpoznania widocznej karty oferty z obsługiwanej aplikacji kurierskiej. FUJARA odczytuje informacje potrzebne do lokalnego obliczenia opłacalności i wyświetla wynik użytkownikowi przed jego własną decyzją.

## Dane dostępne dla funkcji
Z widocznej oferty mogą być odczytane:
- kwota,
- dystans,
- czas,
- planowana godzina odbioru/dostawy, jeśli jest pokazana,
- tekst potrzebny do rozpoznania platformy/oferty.

W czasie odczytu usługa może wykonać techniczny screenshot całego widocznego ekranu (także galerii z ofertą), który może obejmować tło, ale FUJARA nie wykorzystuje treści spoza rozpoznanej oferty do innych celów i nie zapisuje historii ekranu.

## Co aplikacja robi z danymi
- lokalnie rozpoznaje tekst,
- lokalnie oblicza wynik po kosztach, zł/km i zł/h,
- wyświetla wynik w nakładce.
- tylko po ręcznym naciśnięciu `ZAPISZ DANE` na ekranie historii Pyszne zapisuje lokalnie datę, restaurację/punkt odbioru, kwotę, dystans, czas aktywności oraz widoczny identyfikator zlecenia, jeśli jest dostępny; do deduplikacji przechowuje też hash/fingerprint.
- na ekranie `Podsumowanie dnia` Pyszne może lokalnie zapamiętać datę, liczbę zleceń, łączną kwotę i identyfikatory widoczne na liście do porównania kompletności lokalnego logu.

## Czego nie robi
- nie klika,
- nie przyjmuje i nie odrzuca zleceń,
- nie wysyła ofert ani lokalnej historii Pyszne na serwer,
- nie używa danych do reklam ani profilowania.

## Prominent disclosure
Przed przejściem do ustawień Accessibility użytkownik widzi osobny ekran opisujący AccessibilityService, zakres odczytu i cel. Musi nacisnąć `Rozumiem`. Dopiero potem aplikacja kieruje do systemowych ustawień Androida, gdzie użytkownik samodzielnie włącza usługę.

## Film do deklaracji
Film powinien pokazać pełny disclosure, zgodę użytkownika i ręczne włączenie usługi w ustawieniach Androida. Warto na końcu pokazać ekran główny i przycisk `Pokaż podgląd nakładki`.
