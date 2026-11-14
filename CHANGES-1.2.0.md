# FUJARA 1.2.0 — mapa, Wolt na serio, czas pracy

Poprawki po testach 1.1.0 w trasie (14.11) + nowe funkcje.

## Odrzucanie głosem
- **Bez potwierdzenia**: hasło (domyślnie „odrzuć”) od razu klika Odrzuć. Pytanie „Na pewno?” jest opcją w Ustawienia → Asystent (domyślnie wyłączone).
- **Radio**: systemowy rozpoznawacz mowy Androida przejmuje mikrofon i audio focus, więc odtwarzacz/radio jest pauzowane na czas nasłuchu – nie da się tego wyłączyć z poziomu aplikacji (tak musi być). Nasłuch działa tylko gdy widać kartę oferty, więc przerwa jest krótka. Ciągły nasłuch w tle bez przerywania muzyki wymagałby własnego silnika słów-kluczy offline – to osobna, większa funkcja (opisane w ustawieniach).

## Mapa i „po drodze” (to działało źle w 1.1)
- Zamiast małego tekstu z minutami: **duży werdykt** `PO DRODZE (+0,8 km)` / `MAŁY OBJAZD (+2,9 km)` / `W PRZECIWNĄ STRONĘ (+7,4 km)` oraz **mini-mapa** (OSM) w panelu: niebieski = Ty, pomarańczowe **A/B** = odbiór/dostawa bieżących zleceń, zielone **1/2** = odbiór/dostawa nowej oferty, linie tras.
- Objazd liczony jest geometrycznie: najlepsze wplecenie nowego odcinka (odbiór→dostawa) w pozostałe punkty **wszystkich aktywnych zleceń** – Pyszne i Wolt jednocześnie (jedziesz po Pyszne → patrzysz na Wolt, i odwrotnie).
- Dla Pyszne czas nie jest decydujący – nota czasowa pozostaje tylko jako informacja; werdykt przestrzenny jest na górze.
- Adresy bieżącego zlecenia są teraz czytane z ekranów aktywnego zlecenia: Pyszne (`Odbierz na` + lokal + adres + `Zamówienie #` + adres klienta, `Dostarcz na` + adres), Wolt (`Odbiór za N min`, `Dostawa za N min`, `Odbiór z` + lokal + adres, `Dostawa do` + adres, szczegóły zamówienia).
- Etapy: kliknięcie `Zakończ odbiór` / `Rozpocznij odbiór` → jadę do klienta; `Dostarcz zamówienie` / `Zamówienie dostarczone` / `Odrzuć dostawę` → zlecenie zakończone (koniec „zombie” W TRAKCIE sprzed godziny). Stare zlecenia wygasają po 35 min bez sygnału.
- Naprawa: oferta Pyszne nie porównuje się już sama ze sobą (karta z licznikiem `0:53` nie jest „aktywnym zleceniem”); nazwa lokalu Pyszne nie jest już mylona z adresem, gdy Pyszne podaje adres przed nazwą.
- Naprawa: karta Pyszne czasem opisywana jako „Uber” (OCR bez „Zaakceptuj”) – dodatkowe markery Pyszne (`Dostawa do klienta`, `Odbiór:`).
- Panel w Pyszne domyślnie niżej (112 dp), bo zasłaniał „X Odrzuć”.

## Wolt – prawdziwe kwoty
- `Zarobki brutto → Podsumowanie dzienne`: sumy dni (`14.11.2026  26,78 zł`) zapisywane jako oficjalne.
- Ekran dnia: pojedyncze zlecenia (lokal, godzina, `w tym dystans: 8.22 km`, kwota, `(dostawy: 2)`).
- Analiza → Wolt: karta „Oficjalnie z Wolt” (suma dnia, zlecenia z zł/km, różnica vs szacunek z ofert), wykres 7 dni z oficjalnych sum.

## Czas pracy i godzinówka
- Ekran Start: karta **Start / Pauza / Stop** z licznikiem czasu pracy dziś.
- Analiza → Decyzje: karta **Godzinówka** – czas pracy w okresie, przychód brutto (Pyszne z historii + Wolt oficjalnie lub szacunek + inne), realne PLN/h, ofert/h, przyjętych/h.

## Raport odrzuconych
- Karta „Raport odrzuconych”: liczba, suma kwot, śr. PLN/h, dzień po dniu z godzinami.
- Lista ofert z filtrem Wszystkie / Przyjęte / Odrzucone i **Eksport CSV** (udostępnianie).

## Dane
- Backup JSON `formatVersion: 3` (dochodzą zlecenia Wolt, sumy dni, odcinki pracy).

versionName: 1.2.0
versionCode: 43
