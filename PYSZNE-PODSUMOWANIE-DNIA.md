# Pyszne - podsumowanie dnia (FUJARA 0.8.7)

## Przeplyw uzytkownika
1. W Pyszne wejdz w historie i otworz szczegoly zakonczonego lub anulowanego zlecenia.
2. FUJARA laczy OCR z tekstem Accessibility. Gdy odczyta komplet danych, pokazuje `ZAPISZ DANE`.
3. Zapisuje lokalnie date, restauracje, kwote, dystans i czas. Anulowane zlecenie moze miec 0 zl, ale nadal liczy czas/dystans.
4. Ten sam numer/fingerprint nie tworzy duplikatu. Jezeli starszy zapis ma slaba nazwe restauracji, ponowne otwarcie moze go poprawic lepszym odczytem.
5. Otworz w Pyszne `Podsumowanie dnia`. Kontrola zostanie zapisana tylko, gdy FUJARA widzi jawnie jedna date oraz count+kwote z gornej karty tego samego dnia.
6. W FUJARA wejdz `Pyszne -> Podsumowanie dnia`. Pola kontrolne sa tylko do odczytu - nie da sie ich recznie nadpisac.
7. `Potwierdz i policz` jest aktywne dopiero po odczycie kontroli. Przy zgodnosci uruchamia sie animacja: km/czas -> PLN/h i PLN/km -> restauracje.

## Ochrona przed zlym dniem
- brak fallbacku do dzisiejszej daty dla kontroli Pyszne,
- przy wiecej niz jednej jawnej dacie parser odrzuca odczyt,
- liczba zlecen i kwota sa pobierane z okna zaczynajacego sie przy tej samej dacie,
- kontrolne dane z 0.8.4 sa resetowane przez nowy magazyn `day_references_v2`.

## Nazwy restauracji
Parser odrzuca jako nazwe: same kwoty, dystans, czas, identyfikator zlecenia, date i etykiety formularza. Potrafi polaczyc dwie linie dlugiej nazwy/adresu. Tekst Accessibility ma pierwszenstwo przed OCR, co pomaga przy zaslonietym ekranie.

## Anulowane zlecenia
Zlecenie z markerem `Anulowane` / `Zlecenie anulowane` jest traktowane jako zakonczone i moze zostac zapisane. W podsumowaniu jest liczone do liczby zaakceptowanych zlecen, a jego 0 zl, czas i dystans wplywaja na realna stawke dnia.

## Udostepnianie
Wynik tekstowy ma teraz czytelne sekcje: przychod/zlecenia/dystans/czas, stawki po kosztach, SUPER/STYK/FUJARA oraz najlepsza/najslabsza restauracja. Nadal korzysta z Android Share Sheet i nie wysyla danych na backend.


## 0.8.6 — finał dnia

Po poprawnej kontroli dnia aplikacja pokazuje osobną animację wyniku. Warto sprawdzić na urządzeniu trzy warianty: SUPER, NA STYK oraz FUJARA. Animacja kończy się przyciskiem „Pokaż pełne podsumowanie”, po czym karty restauracji odsłaniają się kolejno.


## 0.8.7 — kompletność zleceń i krótszy wynik

- Na ekranie `Historia przychodów` FUJARA zapamiętuje nagłówek dnia (data, liczba zleceń, kwota), a podczas przewijania zbiera numery `#XXXXXX`.
- W `Pyszne -> dzień` pojawia się pasek kompletności i lista numerów, których jeszcze nie zapisano. Jeżeli nie odczytano wszystkich numerów, aplikacja prosi o przewinięcie listy dnia do końca.
- Na szczegółach zlecenia przycisk pokazuje `+ ZAPISZ #NUMER` albo `✓ ZAPISANE #NUMER`.
- PLN/h dla całego dnia jest liczone bez limitu 6 godzin. Limit pozostaje tylko zabezpieczeniem dla pojedynczej oferty.
- Lista restauracji jest skrócona do TOP 3 i DO POPRAWY 3. Pełną listę można rozwinąć przyciskiem.
