# Pyszne - podsumowanie dnia (FUJARA 0.8.5)

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
