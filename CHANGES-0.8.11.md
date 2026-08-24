# FUJARA 0.8.11 - Pyszne po polnocy

## Naprawione

- Szczegoly zlecenia, ktore zaczelo sie przed polnoca i skonczylo po polnocy, moga miec dwie sasiednie daty. Taki ekran nie jest juz odrzucany, wiec panel i przycisk zapisu pojawiaja sie normalnie.
- Data wpisu ze szczegolow jest brana przede wszystkim z pola `Zlecenie przyjete`.
- Jezeli Pyszne pokazuje numer zlecenia na liscie dnia poprzedniego dnia, FUJARA traktuje ten dzien jako dzien rozliczeniowy. Dotyczy to m.in. zlecen przyjetych chwile po polnocy.
- Po ponownym odczytaniu listy dnia FUJARA automatycznie naprawia juz zapisane wpisy, ktore starsza wersja przypisala do nastepnej daty kalendarzowej.

## Przyklad z reprodukcji

- `#MP63FY`: przyjete 21.08 o 23:34, dostarczone 22.08 o 00:08 - panel byl ukrywany przez warunek jednej daty.
- `#MHPYBR`: przyjete 22.08 o 00:09, ale widoczne na liscie dnia 21.08 - zapis byl oznaczony jako wykonany, lecz nie zwiekszal kompletnosci dnia 21.08.

## Wersja

- versionName: 0.8.11
- versionCode: 28
