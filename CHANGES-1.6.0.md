# FUJARA 1.6.0 – trasy zamiast zakładanych godzin

## Co poprawiono
1. **Wolt – automatyczny swipe po adresy.** Rozwinięcie karty było blokowane, bo Wolt nie wystawia przycisku „Akceptuj” w drzewie dostępności. Teraz warunkiem jest świeży odczyt tej samej oferty (OCR, < 2,5 s), swipe jest dłuższy (450 ms) i ponawiany środkiem ekranu, jeśli gest się nie wykona.
2. **Pyszne – koniec zlecenia bez czasu z ekranu.** Godziny „Dostarcz na …” nie są już używane do oceny. Zostało: etap (→🍴 / →🏠) + policzona trasa z Twojej lokalizacji („zostało X km · ~Y min, koniec ~HH:MM”).
3. **Wykrywanie dostarczenia.** Zlecenie kończy się, gdy:
   - klikniesz „Dostarcz zamówienie / Zamówienie dostarczone” (nie jest już blokowane po ostatniej ofercie – blokujemy tylko „Odrzuć dostawę” i „Rozpocznij odbiór”, które mogą należeć do dialogu nowej oferty),
   - Pyszne pokaże „Szukamy dla Ciebie zleceń” (tryb bez aktywnych zleceń),
   - GPS: byłeś ≥ 30 s przy adresie dostawy (≤ 130 m) i odjechałeś (> 450 m).
4. **Mapka w trakcie zlecenia.** Ekran aktywnego zlecenia pokazuje mapkę: Ty → odbiór → dostawa. Po odbiorze (przycisk albo GPS: byłeś przy restauracji i odjechałeś) odbiór znika, zostaje sama dostawa.
5. **Wolt bez adresu restauracji.** Gdy Wolt pokazuje tylko nazwę lokalu, a adres dostawy jest – nazwa jest geokodowana w promieniu 12 km od Ciebie i trasa się liczy (wcześniej brak „adresu odbioru” blokował ocenę).
6. **Ocena oferty na trasach.** Nowa oferta jest wplatana w trasę bieżących zleceń (najlepsze miejsce), liczona przez TomTom/OSRM z geometrią. Werdykt z objazdu: PO DRODZE (≤ 1,5 km), MAŁY OBJAZD (≤ 4 km), W PRZECIWNĄ STRONĘ. Na mapce: **zielona linia = bieżąca trasa**, pomarańczowa (szersza, pod spodem) = trasa z nową ofertą. Geokodowanie sprawdza kod pocztowy.
7. **PLN/h na żywo.** Czas pracy: Start/Pauza, a jeśli nie użyto – od pierwszej do ostatniej aktywności dnia; przerwy > 25 min bez ofert nie są liczone. Ekran „Dzień” ma kartę „RAZEM DZIEŃ · Pyszne + Wolt” z PLN/h dla wybranego dnia.

Dodatkowo: filtr śmieci z drzewa dostępności jako nazwy lokalu („button”, „copyIcon”…), deduplikacja adresów Pyszne po ulicy+numerze.

## Testy
Nowe testy JVM (`RoutePlanTest`, `ActivityWorkTimeTest`): dekodowanie polyline, klucz adresu, filtr UI-śmieci, postęp zlecenia z GPS (odbiór → dostawa → koniec), planer tras (po drodze / w przeciwną stronę / brak zleceń), ekran bezczynności Pyszne, czas pracy z przerwami. Lokalnie: 52/52 OK.

## Ograniczenia
- Wykrycie odbioru/dostawy z GPS wymaga, by adres dało się zgeokodować; przy złym punkcie zostają przyciski i ekran „Szukamy…”.
- Bez klucza TomTom trasy są z OSRM (bez korków) albo szacunku (prosta × 1,3).
- Czas pracy z aktywności liczy się od pierwszej oferty – dojazd do strefy przed pierwszą ofertą nie wchodzi; dokładniej jest ze Start/Pauza.
