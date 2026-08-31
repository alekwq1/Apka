# FUJARA 1.0.0

Android app for local courier-offer profitability analysis.

## Google Play readiness
- applicationId / namespace: `pl.fujara.app`
- targetSdk / compileSdk: 36
- versionCode: 39
- versionName: 1.0.0
- signed AAB workflow: `.github/workflows/build-play-aab.yml`
- debug APK workflow: `.github/workflows/test-apk.yml`
- privacy page: `docs/privacy.html`
- reviewer demo: `Pokaż podgląd nakładki` / `Show calculation demo`
- AccessibilityService prominent disclosure before settings
- no `INTERNET` permission, no ads, no analytics in the current project

Start with `PLAY-CONSOLE-START-HERE.md`.


## 1.0.0 — release

- Finalny numer wersji aplikacji: `1.0.0`.
- Kosmetyczne poprawki ekranu Start, Analizy oraz alertów restauracji i odbiorców.
- `versionCode`: 39.

## 0.8.15

- Start odpowiada na pytanie „Jak mi dziś idzie?”: bieżący wynik, cel netto, ETA celu i prognoza końca zmiany.
- Czytelne wykresy z wartościami; przebieg dnia jest godzinowy i klikalny.
- Spójny język haptics dla SUPER / NA STYK / FUJARA oraz osobny haptic rekordu.
- Podsumowanie tygodnia z porównaniem do poprzedniego tygodnia, czasem, km, PLN/h i najlepszym/najsłabszym dniem.
- Automatyczny lokalny backup raz dziennie (do 14 kopii), cofnięcie do ostatniej kopii oraz pełny eksport/import JSON.
- Sekcja prywatności z informacją o lokalnych danych i usuwaniem całej historii.
- Usunięty zbędny szary odstęp nad dolną nawigacją; tryb jasny/ciemny/system pozostaje dostępny.
- Prawdziwe SDK reklamowe nie jest jeszcze dodane; projekt nadal nie ma permission `INTERNET`.

## 0.8.14

- poprawiona celebracja po „Potwierdź i policz” — nie znika już przy automatycznym odświeżeniu zapisanego wyniku,
- około 6,2 s etapowego reveal wyniku z wibracjami,
- kolejne metryki pojawiają się sekwencyjnie, a pełny wynik ma dodatkową chwilę ekspozycji,
- podczas animacji nie można przypadkowo zamknąć okna tapnięciem poza nim ani przyciskiem Back,
- po zakończeniu sekwencji aktywuje się przycisk „Pokaż pełne podsumowanie”.

## 0.8.13

- minimalistyczny UI inspirowany aplikacjami finansowymi: jasne tło, białe karty i ciemne karty wynikowe,
- ekran Start z dużym wynikiem ostatniego dnia oraz szybkimi metrykami,
- ciemny dolny pasek z ikonami Start / Dzień / Analiza / Ustawienia,
- Analiza dla 7 / 30 / 90 / 365 dni z wykresem realnie zapisanych wyników,
- podsumowanie dnia z dużym wynikiem po kosztach i wykresem PLN/h kolejnych zleceń,
- zachowana logika 0.8.12: snapshot dnia, osobne zlecenia, napiwki/przestój i szczegóły.

## 0.8.11

- Pyszne: szczegóły zlecenia przechodzącego przez północ (dwie sąsiednie daty) nie chowają już panelu FUJARA.
- Data szczegółów jest brana przede wszystkim z pola `Zlecenie przyjęte`.
- Zlecenie przyjęte po północy może zostać przypisane do poprzedniego dnia rozliczeniowego, jeżeli jego ID znajduje się na zeskanowanej liście tego dnia w Pyszne.
- Już zapisane wpisy z błędną datą są automatycznie naprawiane na podstawie zapamiętanych ID dnia.

## Privacy page
The current support/privacy contact is `aleksanue@gmail.com`. Publish `/docs` using GitHub Pages before submitting the store listing.


## 0.8.3
Na ekranie szczegolow Pyszne kwota jest pobierana z pola `Suma przychodow`, a nie z pierwszej widocznej pozycji finansowej.


## 0.8.4
- Pyszne: przycisk `ZAPISZ DANE` na ekranie szczegolow zakonczonego zlecenia.
- Lokalny log dostaw z blokada duplikatow (hash numeru zlecenia, a przy braku numeru fingerprint czasu/kwoty/dystansu).
- Podsumowanie dnia: kontrola liczby zlecen i kwoty, km, czas, PLN/h, PLN/km, SUPER/NA STYK/FUJARA i ranking restauracji.
- Animowane podsumowanie i udostepnianie wyniku przez system Android.
- Bez permission `INTERNET`; wspolny scoreboard online nie jest jeszcze wlaczony.



## 0.8.10

- Podsumowanie dnia Pyszne tworzy dzień w FUJARZE od razu po odczycie nagłówka — nawet przy 0 zapisanych dostawach.
- Kwota kontrolna jest pobierana wyłącznie z zakotwiczonych pól `Przychody` / `Suma przychodów`; kwota pierwszej restauracji nie może już zostać sumą dnia.
- ID zlecenia z Accessibility ma pierwszeństwo przed OCR; różne jawne ID nie są już uznawane za duplikat tylko dlatego, że mają podobny fingerprint.
- Numery z listy dnia pochodzą przede wszystkim z Accessibility; OCR musi potwierdzić numer co najmniej dwa razy.
- Lista braków potrafi samoczynnie naprawić pojedynczy błędnie zeskanowany numer po zapisaniu ostatniej dostawy.
- Magazyn kontroli dnia został podbity do `day_references_v3`, aby nie dziedziczyć błędnych kwot/ID z poprzednich wersji.

## 0.8.8

- poprawiona kontrola kompletności: gdy liczba zleceń i kwota zgadzają się z Pyszne, aplikacja nie pokazuje fałszywej listy „Do zapisania”, nawet jeśli część starszych logów nie ma przypiętych ID,
- przy zgodnej liczbie, ale różnej kwocie pojawia się osobne ostrzeżenie zamiast sugerowania kolejnych zapisów,
- podsumowanie dnia ma wieloetapową wibrację zsynchronizowaną z animacją; finał różni się dla SUPER / NA STYK / FUJARA.

## 0.8.7

- Podsumowanie Pyszne zbiera numery zlecen z przewijanej listy dnia i pokazuje, ktore pozycje sa jeszcze do zapisania.
- Przycisk na szczegolach pokazuje numer zlecenia oraz wyrazny stan `ZAPISANE`.
- Naprawiono PLN/h dla dlugiego dnia (> 6 h); agregat dnia nie jest juz traktowany jak pojedyncza oferta.
- Restauracje sa domyslnie pokazane jako krotkie TOP 3 / DO POPRAWY 3; pelna lista jest zwijana.

## 0.8.6

- Dodano pełny „FUJARA moment” po podsumowaniu dnia: animowany finał, liczniki PLN/h i PLN/km, konfetti/iskry, statusowe efekty SUPER/STYK/FUJARA oraz level-up.
- Karty restauracji pojawiają się kolejno po zamknięciu animacji.

## 0.8.5
- Pyszne: zapis rowniez zakonczonych zlecen anulowanych.
- Stabilniejszy przycisk `ZAPISZ DANE`: OCR + Accessibility.
- Poprawione rozpoznawanie nazw restauracji; kwota/dystans/czas nie sa traktowane jako nazwa.
- Kontrola dnia jest tylko do odczytu i wymaga jednoczesnego odczytu daty, count i kwoty z tego samego dnia.
- Bledne kontrolne odczyty 0.8.4 nie sa dziedziczone (`day_references_v2`).
- Czytelniejsze karty restauracji, animacja liczenia i format udostepnianego wyniku.
