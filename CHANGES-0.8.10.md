# FUJARA 0.8.10 — synchronizacja dnia Pyszne i poprawne ID

## Dzień powstaje od razu
- Odczyt ekranu `Historia przychodów` z datą, liczbą ofert i sumą tworzy dzień w FUJARZE nawet przy 0 zapisanych dostawach.
- Najświeżej odczytany dzień jest automatycznie wybierany w ekranie Pyszne · dzień.

## Kwota dnia
- Usunięto wybieranie pierwszej kwoty po dacie.
- Kwota jest akceptowana tylko przy polu `Przychody` albo `Suma przychodów`.
- Liczba zleceń jest kotwiczona przy `Podsumowanie dnia` / górnej karcie.
- Jeżeli dwa niezależne miejsca ekranu podają różne wartości, odczyt jest odrzucany i czeka na kolejny skan.
- Nowy magazyn `day_references_v3` nie dziedziczy błędnych kontroli z poprzednich wersji.

## Numery zleceń
- Na szczegółach zlecenia tekst Accessibility jest nadrzędnym źródłem numeru ID, OCR jest fallbackiem.
- Dwa wpisy z różnymi jawnymi ID nie mogą być uznane za ten sam zapis przez fingerprint.
- Na liście dnia ID z Accessibility są zapisywane od razu; ID tylko z OCR musi wystąpić minimum dwa razy.
- Przy ostatniej dostawie dnia aplikacja potrafi naprawić pojedynczy błędny numer z listy, jeżeli count i brakująca kwota jednoznacznie potwierdzają zapis.
- Po powrocie z listy ostatnio pokazane ID jest chwilowo blokowane, aby stary ekran nie mignął po wejściu w kolejne zlecenie.

## Wersja
- versionName: 0.8.10
- versionCode: 27
