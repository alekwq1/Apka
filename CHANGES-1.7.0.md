# FUJARA 1.7.0 – panel do jazdy, komendy głosowe, zweryfikowany dzień

## Panel podstawowy (domyślnie włączony)
- Panel pokazuje tylko: status (kolor), KWOTA / PO KOSZTACH, zł/h, zł/km.
- Mapa, werdykt trasy, teksty o zleceniach w trakcie, czasy, ZUS – dopiero po rozwinięciu przyciskiem **⋯** (obok „−”) albo głosem „rozwiń”. Po zniknięciu oferty panel wraca do widoku podstawowego.
- Ustawienia → Asystent: „Panel podstawowy: tylko kwota, zł/h i zł/km”. Wyłączenie przywraca pełny panel jak w 1.6.

## Komendy głosowe (Ustawienia → Odrzucanie głosem → „Komendy głosowe”)
Działają, gdy widać panel FUJARA – przy ofercie i przy zleceniu w trakcie:
- „zwiń” / „schowaj” – chowa szczegóły; drugi raz chowa cały panel (zostaje „FUJARA 👁”).
- „rozwiń” / „pokaż” – przywraca panel; drugi raz pokazuje szczegóły i mapę.
- „przybliż” / „oddal” – gest dwoma palcami na mapie Wolt / Pyszne (środek ekranu, nad dolnym arkuszem). Tylko gdy aplikacja kurierska jest na wierzchu.
- „odrzuć” działa jak dotąd, tylko przy ofercie.
Poprawka: rozpoznawanie słów ukraińskich/rosyjskich (wcześniej cyrylica była wycinana).

## Dzień – zweryfikowany bilans
Nowa karta „DZIEŃ · Pyszne + Wolt” (ekran Start i ekran Dzień), dotknij po szczegóły:
- **Pyszne**: kwota i liczba zleceń z podsumowania dnia Pyszne (oficjalne), gdy jest; brakujące km dopowiadane średnią z zapisanych. Ostrzeżenie, gdy zapisano mniej/więcej zleceń niż w podsumowaniu albo sumy się różnią.
- **Wolt**: oficjalna suma dnia > lista zleceń z Zarobków > pomiar FUJARY > przyjęte oferty. Ostrzeżenie przy rozjeździe sumy dnia i listy, zleceniach tylko z pomiaru, różnicy liczby przyjętych ofert.
- **Netto** = brutto − ZUS − km × koszt auta (dla obu platform tak samo, nigdy więcej niż brutto).
- **Czas pracy**: Start/Pauza; bez nich – z aktywności (oferty, zlecenia; przerwy > 25 min odpadają). Porównanie z czasem samych dostaw („w dostawach 3:10 (68%)”), ostrzeżenie gdy Start był wyłączony a jeździłeś, oraz przy podejrzanie wysokim zł/h.
- zł/h na ekranie Start (cel, prognoza) liczone z tego bilansu.

## Testy
Nowe: `DayAuditTest` (oficjalne sumy Pyszne, skalowanie km, netto ≤ brutto, zł/h z czasu pracy, rozjazd Wolt, czas z aktywności i zapomniany Start), `VoiceCommandTest` (PL, cyrylica, „odrzuć” nie jest komendą). Lokalnie 59/59.

## Ograniczenia
- Przy zleceniu w trakcie mikrofon słucha cały czas, gdy panel jest widoczny – na części telefonów to przycisza radio / wydaje dźwięk. Wtedy wyłącz „Komendy głosowe”.
- Gest przybliżania trafia w środek ekranu; jeśli Wolt ma tam wysunięty arkusz, najpierw go zsuń.
