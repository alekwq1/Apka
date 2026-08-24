# FUJARA 0.8.2

- Poprawiony odczyt polskiej karty Ubera `Dostawa / Łącznie ... min (... km)`.
- Parser Ubera toleruje sytuacje, gdy ML Kit OCR rozbije jedna wizualna linie na kilka linii tekstu.
- Parser Ubera toleruje inna kolejnosc blokow OCR: kwota moze zostac zwrocona po czasie/dystansie mimo ze na ekranie jest nad nimi.
- Przy wyborze kwoty w poblizu podsumowania preferowana jest kwota z jawna waluta PLN/zl.
- Rozpoznawanie platformy Uber z OCR jest odporniejsze na brak slowa `Dostawa`, jesli widoczny jest charakterystyczny uklad `Łącznie/total + min + km` oraz `Akceptuj/Confirm`.
- Dodane testy regresyjne dla polskiego Ubera z przestawionymi blokami OCR i podzielona linia.
- Wszystkie 49 testow parsera, kalkulatora i czarnych list przechodza poprawnie.
- Wersja: `versionCode 19`, `versionName 0.8.2`.
