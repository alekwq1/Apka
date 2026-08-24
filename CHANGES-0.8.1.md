# FUJARA 0.8.1

- Parser Uber i Wolt ma dodatkowy fallback oparty na ukladzie karty, kwocie, czasie i dystansie zamiast wymagania konkretnych slow PL/EN.
- Pyszne: poprawione uzupelnianie godzin `Odbierz/Dostarcz` bez wymagania, aby Accessibility zwrocilo tez kwote i dystans.
- Pyszne: nowy odczyt ekranu `Szczegoly zlecenia` / historii. Aplikacja czyta sume przychodow, rzeczywisty czas aktywnosci (z sekundami) i szacowana odleglosc, po czym pokazuje PLN/h i PLN/km.
- Dla historii Pyszne zapas czasu z ustawien nie jest doliczany, bo ekran pokazuje juz rzeczywisty czas aktywnosci.
- Czarne listy: nazwa bez adresu jest dopasowywana scisle, wiec `McDonald's` nie pasuje automatycznie do kazdej filii.
- Czarne listy: mozna dodac opcjonalny adres, aby rozroznic lokale o tej samej nazwie.
- Czarne listy: nowe UI z przyciskiem `+ Dodaj` i `- Usun` zamiast wielowierszowego pola tekstowego.
- Zachowana kompatybilnosc ze starymi wpisami czarnej listy z 0.8.0.
- Dodane testy regresyjne na podstawie screenow z zamknietych testow.
- Wersja: `versionCode 18`, `versionName 0.8.1`.
