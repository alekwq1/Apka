# FUJARA 0.8.4

## Pyszne - historia i podsumowanie dnia
- Na ekranie `Szczegoly zlecenia` Pyszne nakladka pokazuje przycisk `ZAPISZ DANE`.
- Zapis obejmuje tylko dane potrzebne do statystyk: date, restauracje/punkt odbioru, kwote, dystans i czas aktywnosci.
- Pelny OCR, screenshot i adres klienta nie sa zapisywane. Surowy numer zlecenia nie jest przechowywany - aplikacja zapisuje jego hash do blokowania duplikatow.
- Jezeli numer zlecenia jest zasloniety przez nakladke/OCR go nie odczyta, fallback do deduplikacji korzysta z daty, czasu przyjecia, restauracji, kwoty, dystansu i czasu aktywnosci.
- Dodano ekran `Podsumowanie dnia` z potwierdzeniem liczby zlecen i kwoty wg Pyszne. Po otwarciu dziennego podsumowania w Pyszne wartosci kontrolne sa lokalnie odczytywane i uzupelniane automatycznie; mozna je poprawic recznie. Rozbieznosc jest pokazywana przed analiza.
- Podsumowanie liczy laczny dystans, czas, wynik po kosztach, PLN/h i PLN/km.
- Dodano klasyfikacje SUPER / NA STYK / FUJARA oraz ranking restauracji.
- Dodano animowane liczby i rosnacy znak FUJARA.
- Dodano udostepnianie tekstowego wyniku przez Android Share Sheet.

## Prywatnosc
- Historia Pyszne jest zapisywana tylko lokalnie i tylko po nacisnieciu `ZAPISZ DANE`.
- Brak permission `INTERNET`; brak automatycznej wysylki logow; Android backup jest wylaczony dla buildu.
- Wspolny scoreboard online wymaga osobnego backendu i osobnej zgody uzytkownika - nie jest czescia 0.8.4.
