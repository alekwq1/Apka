# Pyszne - podsumowanie dnia (FUJARA 0.8.4)

## Przeplyw uzytkownika
1. W Pyszne wejdz w historie i otworz szczegoly zakonczonego zlecenia.
2. Na nakladce FUJARA pojawi sie `ZAPISZ DANE` tylko dla ekranu historii zakonczonego zlecenia.
3. Po zapisie przycisk zmieni sie na `ZAPISANE`. Ponowne otwarcie tego samego zlecenia nie tworzy duplikatu.
4. Powtorz dla pozostalych dostaw z danego dnia.
5. Otworz w Pyszne `Podsumowanie dnia`. FUJARA lokalnie odczyta date, liczbe zlecen i laczna kwote kontrolna.
6. W FUJARA wybierz `Pyszne -> Podsumowanie dnia` i nacisnij `Potwierdz i policz`.
7. Wynik jest pokazywany dopiero, gdy liczba zlecen i kwota zgadzaja sie z zapisanym logiem.

## Co jest liczone
- liczba zapisanych zlecen,
- przychod brutto,
- laczny dystans,
- laczny czas aktywnosci,
- wynik po ustawionych kosztach pojazdu i opcjonalnym ZUS,
- PLN/h i PLN/km,
- SUPER / NA STYK / FUJARA dla zlecen i restauracji,
- najlepsza i najslabsza restauracja wg wyniku godzinowego.

## Zaslonieta czesc ekranu
Nakladka FUJARA jest maskowana przed OCR. Przycisk `ZAPISZ DANE` jest umieszczony w obrebie istniejacego panelu, wiec nie zaslania dodatkowego fragmentu Pyszne. Parser szczegolow korzysta m.in. z dolnego pola `Suma przychodow`, dlatego nie zalezy od widocznosci duzej kwoty na gorze ekranu.

## Blokada duplikatow
Aplikacja nie przechowuje surowego numeru zlecenia. Uzywa jego lokalnego hasha oraz drugiego fingerprintu z daty/czasu/restauracji/kwoty/dystansu/czasu aktywnosci. Dzieki temu ponowny zapis tego samego zlecenia jest blokowany rowniez wtedy, gdy OCR odczyta numer nieco inaczej.

## Udostepnianie i scoreboard
0.8.4 potrafi wygenerowac wynik z opcjonalnym nickiem i otworzyc systemowy Android Share Sheet. Wspolny scoreboard online nie jest jeszcze wysylany na serwer, poniewaz projekt nie ma skonfigurowanego backendu ani permission `INTERNET`. Do prawdziwego rankingu miedzy uzytkownikami trzeba podlaczyc np. Firebase/Supabase/wlasne API i dodac zgode na wysylanie danych.
