# FUJARA 1.1.0 — asystent decyzji

## Co nowego

### 1. Statystyki decyzji („Analiza → Decyzje”)
- Każda pokazana karta oferty trafia do lokalnego zbioru (`offer_events.jsonl`) razem z oceną FUJARY (kwota, km, czas, PLN/h, PLN/km, status DOBRA / NA STYK / SŁABA, restauracja, adresy jeśli widoczne).
- Decyzja jest rozpoznawana automatycznie:
  - kliknięcie **Akceptuj / Zaakceptuj zlecenie / Jestem zainteresowany** → *przyjęta*,
  - kliknięcie **Odrzuć / Decline** → *odrzucona*,
  - pojawienie się ekranu aktywnej dostawy („Odbierz na…”, „Nawiguj”) zaraz po zniknięciu oferty → *przyjęta*,
  - zniknięcie bez kliknięcia (np. Stuart po 10 s) → *wygasła*.
- Ekran pokazuje: % przyjętych, liczby ✓ / ✗ / wygasłe, PLN/h i PLN/km przyjętych vs odrzuconych, ile DOBRYCH odrzuciłeś (i ile zł to było), ile SŁABYCH przyjąłeś, medianę czasu decyzji, rozkład godzinowy, dzień po dniu, restauracje i listę ostatnich ofert. Filtr per platforma.
- W panelu nakładki pojawia się licznik dnia `✓N ✗M` (można wyłączyć).
- Usługa dostępności nasłuchuje teraz także `typeViewClicked` (wyłącznie w aplikacjach kurierskich) – bez tego nie da się rozpoznać kliknięcia.

### 2. Pyszne – automatyczny zapis
- Otwarcie szczegółów zakończonego zlecenia w historii Pyszne zapisuje je od razu (ten sam magazyn i deduplikacja co przycisk ZAPISZ; przycisk nadal działa i pokazuje `✓ ZAPISANE`).
- Pyszne.pl / Scoober nie udostępnia publicznego API dla kurierów (sprawdzone), więc odczyt ekranu przez Accessibility/OCR pozostaje jedyną bezpieczną drogą; auto-zapis usuwa ręczne klikanie.

### 3. Wolt – zapisywanie wyników („Analiza → Wolt”)
- Dzień Wolt budowany z dwóch źródeł: przyjętych ofert (kwota, km, czas z karty w chwili akceptacji; nazwa lokalu z „Dostawa od”, paczki „3 dostaw(y) od”) oraz ekranu **Finanse → Zarobki brutto**, który FUJARA zapisuje automatycznie po otwarciu (snapshoty z czasem, przyrost dnia, różnica wobec szacunku z ofert = napiwki/bonusy).
- Widok dnia z nawigacją po datach, lista przyjętych dostaw, ostatnie 7 dni.

### 4. „Czy brać?” w trakcie zlecenia
- FUJARA pamięta aktywne zlecenie (po akceptacji albo z ekranu „Odbierz na HH:MM”).
- Gdy w trakcie pojawia się nowa oferta, panel pokazuje `W TRAKCIE: <lokal> → HH:MM` oraz ocenę:
  - **Zdążysz** / **Na styk** / **Kolizja** – porównanie przewidywanego końca bieżącego zlecenia (+ dojazd) z godziną „Odbierz na” nowej oferty (Pyszne) albo z pozostałym czasem bieżącej dostawy (Wolt/Stuart).
- Opcjonalny moduł **Trasa** (Ustawienia → Asystent): lokalizacja + internet. Pokazuje km/min z Twojej pozycji do odbioru, dojazd po zakończeniu bieżącego zlecenia i odcinek do klienta. Geokodowanie: systemowy Geocoder → OpenStreetMap Nominatim. Trasa: OSRM (bez ruchu, +25% zapasu miejskiego) albo **TomTom z ruchem drogowym** po wpisaniu darmowego klucza. Profil auto / skuter / rower, podpowiedź miasta.

### 5. Odrzucanie głosem
- Hasło (domyślnie „odrzuć”) nasłuchiwane **tylko gdy widoczna jest karta oferty**. Po usłyszeniu FUJARA pyta (TTS + pasek w panelu) „Na pewno odrzucić? Powiedz: odrzucam”; potwierdzenie = słowo potwierdzające lub „tak”; „nie”/cisza anuluje.
- Po potwierdzeniu klika „Odrzuć” w Wolt / Pyszne przez Accessibility (`canPerformGestures` jako fallback), obsługuje ewentualny drugi dialog potwierdzenia, zapisuje decyzję jako odrzuconą (źródło: głos).
- Wymaga zgody na mikrofon; rozpoznawanie przez systemowy silnik mowy (preferowany tryb offline). Uwaga: niektóre telefony wydają krótki dźwięk przy starcie nasłuchu.

### 6. Panel – koniec kolizji z „Odrzuć” w Wolt
- Pozycja panelu jest zapamiętywana osobno dla każdej aplikacji; w Wolt domyślnie 118 dp od góry (pod przyciskiem „Odrzuć”), Stuart 112 dp.
- Panel można **przeciągnąć** palcem za uchwyt „−” (góra/dół, a także na lewą stronę ekranu). Suwak dla Wolt i reset pozycji w Ustawienia → Asystent.
- Nowe wiersze panelu: `W TRAKCIE`, ocena „zdążysz / kolizja”, trasa, stan nasłuchu głosu, licznik decyzji.

### 7. Dane
- Nowe magazyny wchodzą do backupu JSON (`formatVersion: 2`), do „Usuń historię” i mają osobny przycisk usuwania w Ustawienia → Asystent.

## Uprawnienia
- `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` – tylko moduł Trasa (domyślnie wyłączony).
- `RECORD_AUDIO` – tylko odrzucanie głosem (domyślnie wyłączone).
- Polityka prywatności (`docs/privacy.html`) została uzupełniona.

## Testy
- Nowe testy JVM: `DecisionTrackerTest`, `WoltAndActiveJobParserTest`, `VoiceKeywordMatcherTest` (22 przypadki) – fixture’y to transkrypcje przekazanych zrzutów ekranu Wolt / Pyszne / Stuart.
- Pełny build Gradle + testy uruchamia workflow `Build APK`.

## Znane ograniczenia / do sprawdzenia na telefonie
- Rozpoznanie kliknięcia zależy od tego, czy aplikacja kurierska wysyła `TYPE_VIEW_CLICKED` z tekstem przycisku; jeśli nie, działa fallback „ekran dostawy po ofercie” i timeout → wygasła.
- Wolt nie pokazuje adresów na karcie oferty – trasa liczona jest do lokalu po nazwie (geokodowanie nazwy w podanym mieście); Pyszne ma pełne adresy.
- Publiczny OSRM nie ma danych o ruchu; do realnego ruchu potrzebny jest klucz TomTom (darmowy limit dzienny).
- Rozpoznawanie mowy offline wymaga pobranego pakietu języka polskiego w ustawieniach Google/Samsung.

versionName: 1.1.0
versionCode: 42
