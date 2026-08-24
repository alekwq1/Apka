# FUJARA 0.8.7 — kontrola kompletności dnia

## Pyszne / lista dnia
- Zbieranie numerów zleceń `#XXXXXX` z przewijanej listy `Historia przychodów`.
- Łączenie numerów z kontrolą konkretnego dnia bez mieszania dat.
- Pasek `Kompletność dnia` oraz podpowiedź, które numery trzeba jeszcze otworzyć i zapisać.
- Jeżeli nie odczytano wszystkich numerów, aplikacja pokazuje licznik rozpoznanych ID i prosi o przewinięcie listy do końca.
- Przycisk nakładki pokazuje numer: `+ ZAPISZ #XXXXXX` / `✓ ZAPISANE #XXXXXX`.

## Kalkulacja
- Naprawiono PLN/h dla podsumowań dłuższych niż 6 godzin. Wcześniej agregat dnia wpadał w limit bezpieczeństwa przeznaczony dla pojedynczej oferty i zwracał brak czasu / 0 zł/h.
- Agregaty dnia i restauracji liczą teraz realny czas sumaryczny bez tego limitu.

## Czytelność restauracji
- Domyślnie widoczne są TOP 3 i 3 najsłabsze restauracje w kompaktowych wierszach.
- Pełna lista jest zwinięta i dostępna pod `Pokaż wszystkie`.
- W wierszu pozostają najważniejsze dane: liczba zleceń, PLN/h, PLN/km i status.

## Wersja
- versionName: 0.8.7
- versionCode: 24
