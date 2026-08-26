# FUJARA 0.8.17 — poprawki po testach Google Play

## Analiza
- „Tydzień” oznacza teraz pełny tydzień kalendarzowy od poniedziałku do niedzieli, a nie ruchome ostatnie 7 dni.
- karta „Zysk po kosztach” pokazuje czytelniej przychód, odjęte koszty/ZUS i wynik końcowy.
- dni w analizie są klikalne i otwierają bezpośrednio podsumowanie wybranego dnia.
- „Wynik w czasie” ma prostsze etykiety, wybór słupka oraz dokładną datę/kwotę po dotknięciu.

## Dzień i zakończenie zmiany
- przebieg dnia jest pokazany jako czytelna lista godzin z PLN/h, liczbą zleceń i wynikiem po kosztach.
- w ekranie Dzień można zmieniać dni gestem poziomym; na krawędziach historii gest prowadzi do Start/Analiza.
- sekcje „Zapisy” i „Kontrola” zwijają się po poprawnym zatwierdzeniu i automatycznie rozwijają po zmianie danych.
- „Podsumuj / zakończ dzień” prowadzi do podsumowania; dodatkowa kontrola Pyszne jest wymagana tylko przy braku/niezgodności danych.
- komunikat błędu wskazuje ścieżkę „Pyszne → Zarobki → Podsumowanie dnia” i pokazuje różnicę zleceń/kwoty.
- celebracja po przeliczeniu dnia została uproszczona do profesjonalnej karty wyniku i rekordu.

## Napiwki, cel i ustawienia
- napiwek gotówkowy można dodać już w trakcie zmiany; zapisuje się natychmiast lokalnie i trafia do backupu.
- osiągnięcie celu dnia uruchamia krótką celebrację wizualną i haptic.
- ustawienia są podzielone na: Ogólne, Opłacalność, Listy i Dane.
- nazwa „Haptics” została zastąpiona zrozumiałym opisem „Wibracje przy ocenie zlecenia”.

## Nawigacja i obliczenia
- główne ekrany Start / Dzień / Analiza / Ustawienia obsługują nawigację poziomym gestem; Dzień rezerwuje gest najpierw do przewijania dat.
- kolejność obliczeń ZUS → koszty kilometrów pozostaje zachowana i jest zabezpieczona testem także dla podsumowania dnia.

## Wersja
- versionName: 0.8.17
- versionCode: 34
