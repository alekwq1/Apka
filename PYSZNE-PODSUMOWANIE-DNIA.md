# Pyszne - podsumowanie dnia (FUJARA 0.8.11)

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
- liczba zlecen jest kotwiczona przy `Podsumowanie dnia`, a kwota przy `Przychody` / `Suma przychodow`,
- kwoty pojedynczych restauracji nie sa kandydatami na sume dnia,
- kontrolne dane ze starszych wersji sa resetowane przez magazyn `day_references_v3`.

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


## 0.8.8 — komplet bez fałszywych braków + haptics

- Jeżeli zapisano komplet zleceń i suma zgadza się z kontrolą Pyszne, lista brakujących ID jest ukrywana.
- Starsze wpisy bez ID nie powodują już komunikatu „Do zapisania”, gdy kompletność wynosi 100%.
- Gdy liczba się zgadza, ale suma nie, aplikacja każe sprawdzić kwoty zamiast dodawać zlecenia.
- Animacja podsumowania ma zsynchronizowane impulsy wibracji oraz mocniejszy finał zależny od statusu dnia.

## 0.8.9 — stabilizacja szczegółów

Na ekranie pojedynczego zlecenia panel FUJARA jest celowo opóźniony do chwili, gdy nowe dane są spójne. Po przejściu między zleceniami stary panel znika od razu. OCR i Accessibility nie są już bezwarunkowo łączone na tym ekranie: jeśli podają różne numery zleceń, panel pozostaje ukryty do następnego poprawnego odczytu.


## 0.8.10 — dzień od nagłówka + pewniejsze ID

- Samo otwarcie `Historia przychodów -> dzień` tworzy pozycję dnia w FUJARZE; nie trzeba najpierw zapisywać pierwszej dostawy.
- Po świeżym odczycie FUJARA wybiera właśnie ten dzień, także gdy wcześniej były zapisane inne daty.
- `Suma przychodów` i górna karta `Przychody` są jedynymi źródłami kwoty kontrolnej. Przykład: przy dniu 621,95 zł pierwsza dostawa 31,20 zł nie może zostać zapisana jako kwota dnia.
- Numer zlecenia na szczegółach jest preferowany z Accessibility. Fingerprint nie oznacza duplikatu, jeśli dwa wpisy mają różne jawne numery ID.
- Numery z przewijanej listy są preferowane z Accessibility. OCR jest fallbackiem i wymaga powtórnego potwierdzenia tego samego numeru.
- Jeżeli przy stanie 9/10 lista miała jeden błędny numer OCR, a ostatnio zapisana dostawa dokładnie domyka brakującą kwotę, lista numerów jest automatycznie naprawiana.

## 0.8.11 — zlecenia przez północ

- Szczegóły z dwiema sąsiednimi datami (przyjęte przed północą, zakończone po północy) są poprawnym ekranem i nie chowają już panelu FUJARA.
- Data ze szczegółów jest kotwiczona przy `Zlecenie przyjęte`.
- Gdy numer zlecenia przyjętego po północy znajduje się na liście dnia poprzedniego dnia, FUJARA przypisuje zapis do tego dnia rozliczeniowego.
- Starsze wpisy z takim przesunięciem daty są automatycznie naprawiane na podstawie zapamiętanych numerów z listy dnia.
