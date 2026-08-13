# FUJARA 0.7.7

- Nowe domyślne zakresy opłacalności zgodne z testem użytkownika:
  - PLN/km: nieopłacalna `< 2.00`, na granicy `2.00–3.00`, opłacalna `>= 3.00`.
  - PLN/h: nieopłacalna `< 30`, na granicy `30–50`, opłacalna `>= 50`.
- Zakres samych suwaków pozostaje:
  - PLN/km: `0–10`.
  - PLN/h: `0–100`.
- Dodana bezpieczna migracja: istniejące instalacje dostaną nowe wartości tylko wtedy, gdy nadal używały kompletu starych ustawień domyślnych. Własne progi użytkownika nie są nadpisywane.
- Wersja: `versionCode 15`, `versionName 0.7.7`.
