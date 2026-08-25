# FUJARA 0.8.14

## Celebracja wyniku dnia

Po testach wydłużono i ustabilizowano moment po kliknięciu **„Potwierdź i policz”**.

- Naprawiono błąd, przez który zapis snapshotu i automatyczne odświeżenie danych potrafiły niemal natychmiast zamknąć okno wyniku.
- Zamiast krótkiego prostego dialogu używana jest pełna sekwencja `DayCelebrationDialog`.
- Reveal trwa około **6,2 sekundy** zanim użytkownik może przejść dalej.
- Wynik jest odsłaniany etapami: nagłówek → PLN/h i PLN/km → dystans i czas → klasy zleceń → finalny komunikat.
- Każdemu głównemu etapowi towarzyszy haptyka dopasowana do wyniku dnia.
- Pełny wynik pozostaje przez dodatkowe **1,6 s**, aby można było „posmakować” momentu.
- Podczas sekwencji tapnięcie poza oknem oraz Back nie zamykają celebracji.
- Po zakończeniu aktywuje się przycisk **„Pokaż pełne podsumowanie”**.

## Wersja

- `versionName = 0.8.14`
- `versionCode = 31`
