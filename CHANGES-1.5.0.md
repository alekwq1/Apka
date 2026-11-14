# FUJARA 1.5.0 — zawsze odbiór i dostawa, Wolt w sumie dnia, rzetelna ocena

## Przyczyny i naprawy

### Nie zawsze był odbiór i dostawa na mapie
- **Wolt:** OCR zrzutu ekranu maskuje obszar panelu FUJARY, a panel zasłaniał „Ramy czasowe” po rozwinięciu karty — adres klienta (np. „Paulina M. / Skarpowa 21”) był niewidoczny. Teraz na czas rozwinięcia panel jest chowany (max ~4 s), a tekst Wolta jest dodatkowo uzupełniany z drzewa dostępności. Po odczycie karta jest zwijana i panel wraca.
- **Pyszne:** etykieta „2 Odbiory: McDonald's Gdańsk, Chłopska” nie była rozpoznawana (oczekiwany był „Odbiór:”), więc brakowało punktu odbioru; czytana była tylko pierwsza „Dostawa do klienta”, a adres rozbity na dwie linie („…32,c,” / „80-365 Gdańsk”) był gubiony. Teraz: wiele odbiorów i wszystkie dostawy, adresy łączone z dwóch linii. Z „N przystanki” liczona jest oczekiwana liczba dostaw; gdy jest ich mniej, karta Pyszne jest rozwijana i zwijana tak jak Wolt (przełącznik w Ustawienia → Asystent).
- Najpełniejszy odczyt oferty jest zapamiętywany — po zwinięciu karty adresy nie znikają.

### Ocena „po drodze” pokazywana dopiero po weryfikacji
Werdykt i mini-mapa pojawiają się tylko, gdy jest komplet:
- **aktualna lokalizacja** (nie starsza niż 2 min; przy ofercie FUJARA prosi o świeżą pozycję i czeka na nią do 3 s; aktualizacje co 5 s / 15 m),
- **wszystkie odbiory i dostawy** nowej oferty odczytane i znalezione na mapie (≤ 40 km),
- **pozostałe punkty zleceń w trakcie** (Pyszne i Wolt) znalezione na mapie.

Brak czegokolwiek → zamiast mapy pasek „⏳ Sprawdzam przed oceną: brak adresu dostawy / Twojej lokalizacji / …”, ponawiany co 3 s. Gdy nie masz nic w trakcie, pasek mówi wprost: „Brak zlecenia w trakcie · cała trasa oferty ~X km” (zamiast mylącego „do odbioru 4,7 km” obok „7,2 km”).

### Wolt nie liczył się do sumy i godzinówki
- Karta „DZISIAJ · PO KOSZTACH” na Starcie = **Pyszne + Wolt**: kwota, liczba zleceń, km, netto po kosztach (Wolt liczony regułami Wolt), cel dnia i prognoza.
- Wolt bierze oficjalną sumę dnia z „Zarobki brutto”, a gdy jej brak — zlecenia zmierzone przez FUJARĘ albo przyjęte oferty.
- **PLN/h = netto ÷ czas pracy ze Start/Pauza/Stop** (całość, którą uruchamiasz na Starcie). Bez uruchomionego licznika — jak dotychczas, z czasów zleceń.
- Godzinówka w Analiza → Decyzje bierze Wolt z tego samego źródła.

### Odrzucenie głosem — dialog Wolt zostawał otwarty
Dialog „Na pewno chcesz odrzucić tę dostawę?” bywał niewidoczny w drzewie dostępności albo pojawiał się później niż po 0,7/1,5 s. Teraz FUJARA sprawdza go co 350 ms przez 4 s, a jeśli Wolt nie udostępnia przycisków — robi OCR ekranu i tapie w środek linii **dokładnie** „Odrzuć dostawę”. „Rozpocznij odbiór” (akceptacja) nigdy nie jest celem. Sam „Odrzuć” nie jest już traktowany jako przycisk dialogu (mógłby być przyciskiem kolejnej oferty).

## Testy
43 testy JVM (nowe: Pyszne „2 Odbiory” + 2 dostawy z adresem w dwóch liniach, kompletność karty; łączny dzień Pyszne + Wolt i PLN/h z czasu pracy).

versionName: 1.5.0
versionCode: 47
