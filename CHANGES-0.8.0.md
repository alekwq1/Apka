# FUJARA 0.8.0

- Rozszerzony odczyt ofert w obu jezykach uzywanych w aplikacjach kurierskich:
  - Stuart: polskie `Szacowane zarobki`, kilometry i zakres czasu,
  - Wolt: polskie `Spodziewany/Szacowany zarobek` oraz angielskie `Expected earnings`,
  - Uber: polskie `Dostawa` / `Lacznie ... min (... km)` oraz karty `km · stops`,
  - Pyszne.pl: polski i angielski (`Pick up`, `Delivery`, `Accept offer`).
- Naprawione mylenie `min` z jednostka mil w parserze Stuart.
- Dodany zapas czasu 0-120 min: globalny albo osobny dla kazdej platformy; jest doliczany przed obliczeniem PLN/h.
- Nakladke mozna chwilowo zwinac przyciskiem `-` i przywrocic malym przyciskiem `FUJARA` bez utraty biezacej oferty.
- Dodane stale powiadomienie statusu Androida informujace, czy odczyt ofert jest aktywny. Android 13+ poprosi o zgode na powiadomienia.
- Dodane dwie lokalne czarne listy: restauracje i odbiorcy. Trafienie jest zaznaczane czerwonym ostrzezeniem na nakladce.
- Dodany opcjonalny procent ZUS (0-100%). Po wlaczeniu stawki PLN/h i PLN/km sa liczone po ZUS i koszcie pojazdu, a panel pokazuje kwote `Po ZUS`.
- Uporzadkowane ustawienia: sekcje sa opisane wedlug realnej pracy kuriera, a ustawienia per-aplikacja obejmuja tez zapas czasu.
- Dodane testy parserow dla polskich/angielskich kart oraz testy zapasu czasu i ZUS.
- Wersja: `versionCode 17`, `versionName 0.8.0`.
