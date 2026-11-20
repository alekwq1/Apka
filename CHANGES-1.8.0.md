# FUJARA 1.8.0 – po testach z 18–20.11

## Panel i głos
- **Mapka usunięta** z panelu i z ustawień (wyłączona też u obecnych użytkowników). Wyłączony też automatyczny swipe po adresy Wolt/Pyszne – psuł odczyt ofert Wolt.
- **„zwiń”** – od razu zwija cały panel (kwota, zł/h, zł/km). **„rozwiń”** – przywraca go.
- **„przybliż” / „oddal”** – mały krok (~1,5× zamiast ~5×); powtórz komendę, żeby iść dalej. W Pyszne gest jest wyżej (27% wysokości ekranu), żeby nie trafiał w kartę oferty; w Wolt 38%.
- **Niezawodność nasłuchu**: pusty wynik rozpoznawania zostawiał mikrofon „martwy” (stan nasłuchu bez rozpoznawacza) – teraz restart. Dodany strażnik: jeśli rozpoznawacz milczy 12 s, po odrzuceniu (4 s) albo po błędzie (20 s) – nasłuch startuje ponownie.

## Wolt liczony jak Pyszne
- Wolt = **suma zapisanych zleceń**: zapis po realizacji (kwota z przyjętej oferty, bez wpisów 0,00 zł) i potwierdzenie po otwarciu dnia w Wolt → Zarobki. Suma dnia z Wolt służy tylko do kontroli.
- Po otwarciu dnia w Wolt pojawia się komunikat, np. „✓ Wolt 18.11: zapisano 3 zleceń · 112,28 zł ✓ zgodne z Wolt” (albo „⚠ Wolt pokazuje …”).
- Naprawiony parser dnia Wolt: nagłówek z datą i sumą dnia, pasek statusu i okienko nawigacji („Kieruj się…”) nie są już liczone jako zlecenia (to dawało 6 dostaw zamiast 3 i podwójną kwotę). Z odczytu drzewa i OCR wybierany jest ten, który zgadza się z sumą dnia. Stare śmieci są odfiltrowane przy liczeniu.
- Wpis „po realizacji” łączy się z oficjalnym po kwocie (±1 zł) i czasie, nie tylko po nazwie.

## Podsumowanie dnia
Karta „PODSUMOWANIE DNIA” (Start i Dzień): Pyszne X zł (n), Wolt Y zł (n), **Razem**, **czas pracy z timera** i **zł/h = razem ÷ czas timera** (brutto, pod spodem netto). Bez timera zł/h się nie liczy – karta to mówi i podaje orientacyjny czas aktywności.

## Timer
Po otwarciu Pyszne / Wolt, gdy timer stoi: okienko „⏱ Zaczynasz zmianę? Włączyć timer pracy?” [▶ Start] [Nie]. Na pauzie – „Wznowić?”. Nie pyta częściej niż raz na godzinę; znika po 30 s i przy nowej ofercie. Wyłączane w Ustawieniach → Asystent.

## Testy
Lokalnie 62/62, nowe: `WoltParserSanityTest` (nagłówek/pasek statusu/nawigacja, zlecenie = suma dnia, łączenie live↔oficjalne po kwocie), zaktualizowany `DayAuditTest` (Wolt = suma zleceń, zł/h tylko z timera).
