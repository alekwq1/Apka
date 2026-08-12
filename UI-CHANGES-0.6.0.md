# FUJARA 0.6.0 - UI refresh

Zmiany obejmuja tylko prezentacje i konfiguracje. Parser ofert oraz logika kalkulacji pozostaly bez zmian.

## Co zmieniono
- ciemny, prosty interfejs FUJARA zamiast jasnego Material look
- ekran glowny z jednym wyraznym statusem analizy
- onboarding w 3 krokach z paskiem postepu
- ustawienia uporzadkowane kolejno: 01 Koszt przejazdu, 02 Twoje minima, 03 Nakladka, 04 Jezyk
- osobny wybor zakresu ustawien Global / Uber / Wolt / Glovo / Bolt / Pyszne
- brak ustawienia czasu wyswietlania: nakladka zyje tak dlugo jak wykrywana oferta
- odswiezona nakladka w prawym gornym rogu, mniejsza i czytelniejsza
- fujara jako rosnacy wskaznik: czerwony -> zolty -> zielony
- nowa ikona aplikacji w tym samym jezyku wizualnym
- wersja podniesiona do versionCode 7 / versionName 0.6.0

## Build przez GitHub
Repo ma gotowe workflowy w `.github/workflows/`.

1. Wgraj zmienione pliki do repozytorium.
2. Push na `main` lub `master` uruchomi workflow testowego APK.
3. W Actions mozesz tez recznie uruchomic `Build Google Play AAB`.
4. Produkcyjny AAB wymaga skonfigurowanych secretow podpisu opisanych w istniejacej instrukcji projektu.
