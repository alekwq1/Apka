# FUJARA 0.7.0 — poprawki po testach terenowych

## Najważniejsze zmiany

- Automatyczny wybór języka telefonu przy pierwszym uruchomieniu; szybka zmiana języka na ekranie prywatności i w ustawieniach.
- Motyw systemowy, jasny i ciemny.
- Ustawienia zapisują się automatycznie i pokazują krótki komunikat potwierdzający.
- Progi opłacalności mają suwaki zakresu: czerwone poniżej, żółte w zakresie, zielone powyżej.
- Osobne progi dla każdej platformy nadal są opcjonalne.
- Wybór sposobu decyzji: PLN/h, PLN/km albo tryb mieszany.
- Regulacja wielkości liczb i krycia nakładki; domyślnie widoczne są PLN/h oraz PLN/km.
- Opcja zaokrąglania zarobków do pełnych PLN jest domyślnie aktywna.
- Dodana platforma Stuart i obsługa dystansu podawanego w milach.
- Wolt: parser wiąże wynik z Wolt i preferuje „Spodziewany zarobek”, a nie kwotę reszty.
- Bolt Food: parser preferuje dolny wiersz podsumowania całej trasy, a nie czas do restauracji.
- Pyszne.pl: historia zleceń i rozmowy w komunikatorach nie są traktowane jako aktywna oferta.
- OCR jest uruchamiany z kontekstem platformy wynikającym najpierw z aplikacji/pakietu, dzięki czemu ogólny tekst nie podszywa się pod Ubera.
- Usunięte zostały dublujące kafelki „Telefon / Gotowy” i „Zasady / Koszty i minima” z ekranu głównego oraz informacja „Bez timera” z ustawień.
- Polityka prywatności została napisana od nowa i ma adres kontaktowy `aleksanue@gmail.com`.

## Testy regresji

Dodano przypadki testowe odpowiadające zrzutom z Wolt, Bolt Food, Stuart, Pyszne.pl oraz fałszywym wykryciom tekstu. Projekt źródłowy nie zawiera Gradle Wrappera, więc w tym środowisku nie można wykonać pełnego builda Androida bez doinstalowania Gradle/dependencji. Czyste klasy parsera i kalkulatora są sprawdzane osobno kompilatorem Kotlin.
