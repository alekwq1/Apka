# FUJARA 0.8.5 — Pyszne podsumowanie dnia

Poprawki po testach na realnym ekranie Pyszne:

- czytelniejszy tekst udostępnianego wyniku (sekcje, emoji, puste linie, najlepsza/najsłabsza restauracja),
- bardziej czytelne karty restauracji w podsumowaniu,
- mocniejszy parser nazwy restauracji: odrzuca kwoty, dystans, czas i identyfikatory jako nazwę; łączy zawinięte nazwy/adresy,
- możliwość ponownego zapisania istniejącego zlecenia, jeśli nowy odczyt ma lepszą nazwę restauracji,
- obsługa zleceń anulowanych jako zakończonych wpisów z historią (0 zł może zostać zapisane),
- kontrola dnia jest tylko do odczytu — brak ręcznej edycji count/kwoty,
- kontrola dnia wymaga jawnej, jednej daty oraz count+kwoty odczytanych razem z górnej karty tego dnia,
- migracja kontrolnych odczytów do `day_references_v2`, żeby nie używać błędnych zapisów z 0.8.4,
- OCR Pyszne wspierany tekstem z Accessibility — większa szansa na pojawienie się `ZAPISZ DANE`,
- automatyczne odświeżanie ekranu podsumowania po powrocie z Pyszne,
- animowane liczenie po `Potwierdź i policz` (km/czas -> zł/h/zł/km -> restauracje),
- podsumowanie pokazuje liczbę anulowanych zleceń.
