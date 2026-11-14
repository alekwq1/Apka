# FUJARA 1.4.0 — weryfikacja aktywnych zleceń, Wolt: wiele punktów, poprawny dzień

## Przyczyny i naprawy

### „W TRAKCIE: Wolt Wolt”, choć nic nie było zaakceptowane
**Przyczyna:** Wolt pokazuje na mapie dymek „Odbiór za 1 min” także przy **podglądzie nowej oferty**. Od 1.2 traktowałem ten dymek jako ekran aktywnego zlecenia, więc każda oferta Wolt tworzyła „aktywne” zlecenie bez nazwy (stąd „Wolt Wolt”), które potem psuło ocenę kolejnych ofert (Pyszne też pokazywało „W TRAKCIE: Wolt”).
**Naprawa (weryfikacja przed pokazaniem mapy i sugestii):**
- samodzielny przycisk „Odrzuć” (Wolt w rogu, Pyszne „X Odrzuć”) oraz dialog „Na pewno chcesz odrzucić…” = zawsze karta oferty;
- sam dymek „Odbiór/Dostawa za N min” nie wystarcza; aktywne zlecenie wymaga mocnego sygnału: „Odbierz/Dostarcz na HH:MM” + „Nawiguj”, „Odbiór z”, „Dostawa do”, „Zakończ odbiór”, „Dostarcz zamówienie”, „Zamówienie dostarczone”, „Problemy z dostawą”;
- zlecenie z ekranu jest **potwierdzone** dopiero po drugim odczycie po ≥ 3 s; z kliknięcia „Akceptuj” od razu;
- w panelu, na mapie i w ocenie „czy brać” są tylko potwierdzone zlecenia z nazwą lub adresem;
- przez 2,5 s po widocznej ofercie nie tworzymy zlecenia z ekranu (resztki karty);
- stare (niezweryfikowane) zapisy aktywnych zleceń są kasowane przy aktualizacji; w Ustawienia → Asystent jest przycisk „Wyczyść aktywne zlecenia”.

### Dzień Wolt: 243,28 zł zamiast 62,44 zł (14.11)
**Przyczyna:** w drzewie dostępności Wolt kwota wiersza „Podsumowanie dzienne” jest **przed** datą. Parser parował „data → następna kwota”, więc kwoty przesuwały się o jeden dzień. Do tego nazwa lokalu w dwóch liniach („SMASHYNSKI, SMASHYNSKI - smash / burgers”) powodowała zapis tego samego kursu kilka razy (stąd „4 dostawy”).
**Naprawa:** parser sprawdza oba układy i wybiera ten, którego suma zgadza się z „Zrealizowane zamówienia 625,10 zł”; jeśli nie da się potwierdzić — nic nie zapisuje. Nazwa lokalu łączona z wielu linii, klucz zlecenia = data + godzina + kwota (bez nazwy), stare duplikaty scalane przy odczycie. Błędne sumy dni z 1.3 są jednorazowo usuwane — wystarczy raz otworzyć Wolt → Zarobki brutto.

### Wolt: adresy, wiele odbiorów i dostaw, powrót do mapy
- Po pojawieniu się oferty FUJARA przesuwa arkusz w górę, czyta **„Ramy czasowe”** (wszystkie lokale i wszystkich klientów, np. paczka „2 dostaw(y) od”), zapamiętuje je dla tej oferty i **zwija arkusz z powrotem**, żeby była widoczna mapa Wolta.
- Mapa i ocena trasy czekają na adresy (do 4 s) zamiast geokodować samą nazwę lokalu (dawało złe punkty, np. „KFC Orlen Elbląska” pod Pruszczem). Punkty dalej niż 40 km od Ciebie/miasta są odrzucane.
- Mini-mapa: wszystkie nowe odbiory (1, 2…) i dostawy, trasa przez wszystkie punkty; ocena objazdu liczy całą paczkę względem aktywnych zleceń Pyszne i Wolt.
- Gesty startują po przeciwnej stronie ekranu niż panel FUJARY (żeby nie trafić w nakładkę).
- Czas „Szacowany” zapamiętany z pierwszego odczytu tej samej oferty — po rozwinięciu arkusza nie ma już „BRAK CZASU / PLN/h --”.

### Odrzucanie
- Arkusz, który zostaje na dole po odrzuceniu, jest teraz zsuwany **w dół** (także po ręcznym „Odrzuć” w Wolt). Nigdy przy widocznym „Akceptuj” ani przy dialogu „Na pewno…”.
- „Odrzuć dostawę” / „Rozpocznij odbiór” w dialogu odrzucania oferty nie zmieniają już stanu aktywnych zleceń.

### Domyślne ustawienia i uprawnienia
- Hasło głosowe „odrzuć”: **włączone** domyślnie. Trasy i „czy zdążę”: **włączone**. Miasto: **Gdańsk**.
- Nowy przełącznik „Pokazuj mini-mapę w panelu” (domyślnie włączony).
- Aplikacja przy starcie prosi o **mikrofon i lokalizację** (po oknie powiadomień; maks. 3 próby).

### Aktualizacje bez utraty danych
**Przyczyna „0,00 zł” na Starcie:** debug-APK z GitHub Actions był podpisywany za każdym razem nowym, losowym kluczem — aktualizacja wymagała odinstalowania, co kasowało historię Pyszne. Teraz build używa stałego klucza z repo (`signing/fujara-debug.p12`). **Jeszcze raz** trzeba odinstalować starą wersję (inny podpis) — przed tym zrób eksport kopii JSON (Ustawienia → Dane) i zaimportuj ją po instalacji. Od tej wersji kolejne aktualizacje instalują się na istniejącą.

## Testy
41 testów JVM (nowe: kwota przed datą w podsumowaniu dziennym, nazwa lokalu w dwóch liniach, „Ramy czasowe” z 1 i 3 punktami, dialog odrzucania i „X Odrzuć” jako oferta, niepotwierdzone i bezimienne zlecenia nie są pokazywane).

versionName: 1.4.0
versionCode: 46
