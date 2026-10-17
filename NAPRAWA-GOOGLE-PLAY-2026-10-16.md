# FUJARA 1.0.2 — odpowiedź na odrzucenie Google Play z 16.10.2026

## Dwa powody odrzucenia
1. **Accessibility API policy: Missing description in Play Listing** — dotychczasowy pełny opis nie mówił wprost o `AccessibilityService`.
2. **Privacy Policy: Invalid privacy policy (link is indirect)** — wpisany do Play Console URL nie otwiera bezpośrednio treści polityki albo kieruje do strony wymagającej dodatkowego kliknięcia.

## Co poprawiono w paczce
- Pełny opis sklepu: `GOOGLE-PLAY-OPIS-DO-WKLEJENIA.txt` — 2295 znaków, gotowy do wklejenia w **Opis pełny**.
- Strona polityki: `docs/privacy.html` — uzupełnione informacje o pełnoekranowym, tymczasowym OCR, danych lokalnych Pyszne i zasadach usuwania.
- `docs/index.html` teraz zawiera **pełny tekst tej samej polityki**, więc nawet wejście na główny URL GitHub Pages nie wymaga drugiego kliknięcia.
- Ekran zgody w aplikacji: doprecyzowano odczyt całego ekranu, przechowywanie danych i sposób wyłączenia usługi; przycisk mówi wprost o wyrażeniu zgody.
- Usługa: dodano kontrolę zapisanej zgody także wtedy, gdy ktoś włączy AccessibilityService bezpośrednio w ustawieniach Androida. Samo włączenie usługi nie jest zgodą na przetwarzanie.
- Wersja projektu: `versionName=1.0.2`, `versionCode=41`. Package ID bez zmian: `pl.fujara.app`.

## Wymagana akcja 1 — opublikuj stronę i sprawdź URL
1. Wypchnij poprawiony projekt na repo `https://github.com/alekwq1/Apka`, do gałęzi publikowanej przez GitHub Pages. Nie wysyłaj katalogu `.git`, kluczy podpisu ani plików z hasłami; archiwum poprawionej wersji ich nie zawiera.
2. Wejdź w GitHub → repo `Apka` → **Settings → Pages**. Ustaw `Deploy from a branch`, branch `main`, folder `/docs` i kliknij Save, jeśli nie jest włączone.
3. Otwórz w **prywatnym oknie przeglądarki**, bez logowania: `https://alekwq1.github.io/Apka/privacy.html`. Od razu powinien się wyświetlić nagłówek **Polityka prywatności** i cały dokument. Jeśli pokazuje README, stronę główną, błąd 404, repozytorium albo wymaga kliknięcia — NIE zgłaszaj jeszcze poprawki.
4. W Play Console → **Policy and programs → App content → Privacy policy** wklej **dokładnie**: `https://alekwq1.github.io/Apka/privacy.html`.
5. URL powinien być publiczny, działać bez logowania, otwierać treść HTML bez pośrednich stron i bez PDF. W aplikacji jest ten sam bezpośredni URL.

**Uwaga:** Samo umieszczenie pliku w repo nie publikuje go jako strony. Nie udało się potwierdzić publicznej dostępności URL przy przygotowaniu paczki; po wypchnięciu sprawdź go ręcznie. Google ocenia realnie podany URL, a nie lokalny plik.

## Wymagana akcja 2 — zaktualizuj opis w Play Console
1. Play Console → **Grow users → Store presence → Main store listing** (nazwy sekcji mogą się nieznacznie różnić).
2. W **Full description / Pełny opis** zamień cały tekst na zawartość `GOOGLE-PLAY-OPIS-DO-WKLEJENIA.txt`.
3. W **Short description / Krótki opis** możesz użyć: `Sprawdź opłacalność zlecenia kurierskiego przed jego przyjęciem.`
4. Zapisz zmiany. Szczególnie ważne są fragmenty o `AccessibilityService`, zakresie odczytu, tymczasowych screenshotach, celu i braku automatycznego przyjmowania zleceń.

## Wymagana akcja 3 — kod / AAB
Ponieważ w paczce zmieniono również komunikat w aplikacji i zabezpieczenie usługi, należy przygotować nowy AAB **jeśli publikujesz ten poprawiony kod**. W repo można użyć `.github/workflows/build-play-aab.yml`, po wcześniejszym ustawieniu czterech sekretów podpisu. Zachowaj oryginalny klucz upload, aby aktualizacja mogła zastąpić poprzednią wersję.

Aby naprawić **tylko dwa wskazane przez Google błędy**, czasem wystarczy zaktualizować listing i publiczną stronę polityki bez nowego AAB. Jeśli jednak wysyłasz kod 1.0.2 — przed produkcją przetestuj onboarding i funkcję Accessibility na prawdziwym Androidzie. W tym środowisku nie zbudowano ani nie przetestowano AAB; ostateczna akceptacja Google nie jest gwarantowana.

## Wymagana akcja 4 — zgłoszenie do Google
1. Zweryfikuj pole Privacy policy i Main store listing.
2. Sprawdź deklarację **AccessibilityService**: FUJARA nie jest narzędziem dla osób z niepełnosprawnościami (`isAccessibilityTool=false`).
3. Jeśli deklaracja lub ekran zgody uległy zmianie, nagraj aktualny film: start aplikacji → pełny ekran disclosure → świadoma zgoda → ręczne włączenie usługi w Androidzie → demonstracja nakładki.
4. Play Console → **Publishing overview → Send for review** (lub odpowiednik w aktualnym interfejsie).
5. Zaczekaj na wynik Google. Dopiero opublikowanie URL i wysłanie zmian do oceny rozpoczyna ponowną weryfikację.

## Prywatność i bezpieczeństwo
- Deklaracja Data safety musi dotyczyć faktycznie wypuszczonego buildu. Aktualna paczka nie zawiera permission `INTERNET` w manifest i nie ma reklam/analityki własnej, ale trzeba zweryfikować wszystkie zależności przed publikacją.
- Screenshot z tła może zawierać cudze dane — w opisie i disclosure nie wolno pisać, że aplikacja ma techniczną możliwość odczytu *wyłącznie* tekstu oferty.
- Aplikacja przechowuje surowe numery zleceń w lokalnych zapisach Pyszne w niektórych przypadkach; nie pisz w deklaracji „tylko hash”.
