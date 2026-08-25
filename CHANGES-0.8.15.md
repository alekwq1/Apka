# FUJARA 0.8.15

## Dashboard „Jak mi dziś idzie?”
- ekran Start pokazuje wynik bieżącego dnia zamiast ostatniego zapisanego dnia,
- duży wynik po kosztach, PLN/h i PLN/km,
- cel dzienny netto z paskiem postępu,
- estymacja godziny osiągnięcia celu,
- prognoza wyniku na planowany koniec zmiany,
- cel i godzina końca zmiany są edytowalne w ustawieniach.

## Czytelniejsze wykresy
- wykres „Wynik w czasie” pokazuje liczby nad słupkami,
- słupki można dotykać, aby wyróżnić konkretny okres i jego wartość,
- przebieg dnia jest teraz wykresem godzinowym,
- nad każdą godziną widać PLN/h,
- dotknięcie godziny pokazuje zlecenia z tego przedziału,
- zlecenie z wykresu nadal otwiera pełne szczegóły.

## Haptics
- różne krótkie wzorce wibracji dla SUPER, NA STYK i FUJARA,
- blokada powtarzania haptics dla tej samej oferty,
- przełącznik haptics w ustawieniach,
- mocniejszy, osobny wzorzec dla rekordu dnia / PLN/h.

## Rekordy
- podczas „Potwierdź i policz” FUJARA sprawdza wcześniejsze zatwierdzone dni,
- nowy rekord PLN/h, wyniku dnia lub obu naraz dostaje specjalny komunikat i haptic w celebracji.

## Analiza tygodnia
- dodatkowa karta „Tydzień w skrócie”,
- różnica procentowa względem poprzedniego tygodnia,
- łączny czas, dystans i PLN/h,
- najlepszy i najsłabszy dzień,
- nadal jeden główny wykres bez ściany tekstu.

## Backup i prywatność
- automatyczny lokalny backup raz dziennie przy pierwszym uruchomieniu,
- przechowywanie do 14 lokalnych kopii,
- przywrócenie ostatniej kopii z ustawień,
- pełny eksport/import FUJARY do jednego pliku JSON,
- backup obejmuje ustawienia, historię Pyszne, wyniki dni, listy restauracji/odbiorców i zuchów z napiwkami,
- nowa sekcja prywatności jasno opisuje dane przechowywane lokalnie,
- możliwość usunięcia całej historii wraz z lokalnymi backupami bez kasowania ustawień i list.

## UI / dark mode / dolny pasek
- usunięto zbędne podwójne dolne wypełnienie nad nawigacją, które wyglądało jak szary pasek,
- istniejący tryb jasny/ciemny/system pozostaje dostępny i obejmuje nowy dashboard,
- nie dodano jeszcze prawdziwego SDK reklamowego: miejsce po szarym pasku nie jest sztucznie wypełniane placeholderem reklamy.

## Wersja
- versionName: 0.8.15
- versionCode: 32
