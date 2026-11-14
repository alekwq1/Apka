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

## 1.2.1 (ten sam dzień)
- **Bezpiecznik głosu w Wolt**: jeśli po „Odrzuć” pojawi się dialog „Na pewno chcesz odrzucić tę dostawę? Stracisz zarobki…” (to jest zrzucenie JUŻ PRZYJĘTEJ dostawy, nie nowej oferty), FUJARA go nie potwierdza – klika „Rozpocznij odbiór”/Wstecz i pokazuje ostrzeżenie w panelu.
- **Decyzje przy aktywnym zleceniu tej samej platformy**: ekran „w trakcie” starego zlecenia (np. Wolt „Odbiór za 7 min” po wygaśnięciu nowej oferty Wolt) nie jest już liczony jako „przyjęta”; akceptacja po ekranie tylko gdy pokazuje lokal z oferty lub inny niż poprzednie zlecenie.
- **Start / Pauza / Stop w powiadomieniu** FUJARY (przyciski + czas pracy dziś) – bez otwierania aplikacji w trasie.

## 1.3.0 – poprawki po testach (lista z załącznika)

### 1. Wolt – głos „odrzuć” akceptował zamówienie — PRZYCZYNA i naprawa
Dwie przyczyny, obie w kodzie klikania:
- Napis „Odrzuć” w Wolt nie jest sam klikalny; FUJARA szukała klikalnego przodka do 6 poziomów w górę i trafiała na **całą kartę oferty**, a tap w jej środek lądował w pobliżu „Akceptuj”. Teraz przodek jest używany tylko, gdy jest niewiele większy od napisu; inaczej tap idzie dokładnie w środek napisu „Odrzuć”.
- W 1.2.1 dodałem „bezpiecznik”, który dialog „Na pewno chcesz odrzucić tę dostawę? Stracisz zarobki…” traktował jako zrzucanie już przyjętej dostawy i klikał „Rozpocznij odbiór” — a ten przycisk w Wolt oznacza **akceptację**. Bezpiecznik usunięty. Dialog jest potwierdzany wyłącznie przyciskiem zaczynającym się od „Odrzuć” („Odrzuć dostawę”); etykiety „Rozpocznij…/Akceptuj/Odbierz” są na stałej liście zakazanych i nigdy nie są klikane w procesie odrzucania. Dodatkowo wzorzec etykiety potwierdzenia nie działał dla „Odrzuć” z polskim „ć” (granica słowa `\b`) — poprawione.
- „Odrzuć” jest szukane od najwyższego okna (karta oferty leży nad ekranem aktywnej dostawy).

### 2. Wolt – zamykanie pozostałego okna po odrzuceniu
Po odrzuceniu (0,7 s / 1,5 s potwierdzenie dialogu, 1,9 s i 3,5 s kontrola): jeśli nie widać już „Akceptuj”, ale został arkusz z „Spodziewany zarobek / Dostawa od / Odległość”, FUJARA wykonuje gest w górę. Gest nigdy nie jest wykonywany, gdy przycisk akceptacji jest widoczny. Ustawienie: Asystent → „Po odrzuceniu głosem zamknij pozostały arkusz gestem”.

### 3. Wolt – automatyczne odczytanie adresów
Po pojawieniu się oferty Wolt bez adresów FUJARA po 0,6 s przesuwa arkusz w górę (gest zaczyna się w strefie tekstu „Spodziewany zarobek”, nie na przycisku), ponownie skanuje i odczytuje adres odbioru i dostawy (etykiety Odbiór/Dostawa albo kolejność linii adresowych). Adresy dopisywane są do **tej samej** oferty (ta sama sygnatura), trasa i mapa liczą się jeszcze raz z punktem dostawy. Jednorazowo na ofertę. Ustawienie: „Wolt: sam rozwiń kartę oferty”.

### 4. Mapa – widmowe aktywne zlecenie — PRZYCZYNA i naprawa
Na zrzutach „W TRAKCIE: Pyszne.pl Dostawa do klienta → 21:45” to była **sama oglądana oferta**: częściowy odczyt karty Pyszne bez przycisku „Zaakceptuj” (OCR/animacja) miał „Nawiguj” + terminy, więc klasyfikator uznawał ją za ekran aktywnego zlecenia i tworzył zlecenie z „restauracją” = napis „Dostawa do klienta”. Naprawa:
- karta z OBOMA terminami („Odbierz na” i „Dostarcz na”) albo z „X Odrzuć” + markerami oferty Pyszne jest zawsze kartą oferty (ekran aktywny Pyszne pokazuje tylko jeden termin);
- ekran o tej samej sygnaturze co aktualnie widoczna oferta nigdy nie jest aktywnym zleceniem;
- parser aktywnego ekranu Pyszne pomija etykiety („Dostawa do klienta”, kwoty, km);
- zakończenie: „Dostarcz zamówienie / Zamówienie dostarczone” usuwa zlecenie, „Odrzuć dostawę” usuwa bez zaliczenia; adresy z ekranów uzupełniają tylko brakujące pola własnego zlecenia (brak mieszania między zleceniami); aktywne zlecenia są zapisywane trwale i przywracane po restarcie (wygasają po 35 min bez sygnału).

### 5. Wolt w statystykach + potwierdzenie zapisu
- Każda dostawa Wolt zakończona kliknięciem „Zamówienie dostarczone” jest zapisywana jako wpis `live` (lokal, kwota i km z karty oferty, czas realizacji) z potwierdzeniem: wibracja + komunikat „✓ Zapisano Wolt: … · 27 min”.
- Wpisy `live` łączą się z oficjalnymi z ekranu „Zarobki brutto” (ta sama data i kwota) — bez duplikatów; kwota/dystans z Wolt, czas z pomiaru. Anulowane („Odrzuć dostawę”) nie są zapisywane.
- Nowa karta **WOLT** na ekranie Dzień (pod nawigacją dat) i na Start — razem z Pyszne, z oznaczeniem źródła (oficjalnie / pomiar / szacunek). Godzinówka w Analiza → Decyzje bierze oficjalne sumy Wolt, a gdy ich brak — wpisy live/szacunek.

### 6. Czas realizacji Wolt
Start = akceptacja (kliknięcie „Akceptuj”/„Rozpocznij odbiór” lub ekran aktywnej dostawy po ofercie), odbiór = „Zakończ/Rozpocznij odbiór”, koniec = „Zamówienie dostarczone”. Znaczniki są w trwałym magazynie `fujara_active_jobs`, więc restart usługi nie zeruje pomiaru.

### Testy (JVM, 37 przypadków)
Nowe: częściowa karta Pyszne ≠ aktywne zlecenie, adresy z rozwiniętej karty Wolt, łączenie wpisu live z oficjalnym, przyciski etapów (anulowanie ≠ dostarczone), decyzje przy aktywnym zleceniu tej samej platformy.

### Ograniczenia
- Układ rozwiniętej karty Wolt znam tylko z opisu — parser jest tolerancyjny (etykiety albo kolejność adresów). Jeśli Wolt pokaże adresy inaczej, potrzebny będzie zrzut rozwiniętej karty.
- Gesty (rozwinięcie, zamknięcie) działają tylko gdy odpowiednie teksty są widoczne dla usługi dostępności; na ekranach czysto graficznych nie zadziałają.
- Testy scenariuszy na telefonie (akceptacja/odrzucenie przyciskiem i głosem, zamykanie okna) mogę zweryfikować tylko pośrednio — proszę o zrzuty z panelu po jeździe.
