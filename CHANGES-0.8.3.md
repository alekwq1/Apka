# FUJARA 0.8.3

- Poprawiony ekran `Pyszne.pl -> Szczegoly zlecenia`.
- Kwota calego zlecenia jest teraz pobierana wylacznie z pola `Suma przychodow` (lub odpowiednika `Total earnings/income/revenue`).
- `Stawka bazowa`, `Przyznany napiwek`, `Dodatkowe korzysci` i `Inne` nie moga juz zastapic prawidlowej kwoty po pojawieniu sie nakladki.
- Naprawiony przypadek z testu terenowego: `Suma przychodow 25,28 zl`, `Stawka bazowa 19,28 zl`, `Napiwek 6,00 zl` -> FUJARA zachowuje `25,28 zl`.
- Dziala zarowno gdy etykieta i kwota sa w jednej linii, jak i gdy OCR zwroci je w osobnych liniach.
- Dodane 2 testy regresyjne dla bledu z kwota pod nakladka; lacznie 51 testow parsera, kalkulatora i czarnych list przechodzi.
- Wersja: `versionCode 20`, `versionName 0.8.3`.
