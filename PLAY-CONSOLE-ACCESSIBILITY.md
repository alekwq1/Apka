# Google Play — AccessibilityService / prominent disclosure

FUJARA nie jest narzędziem dostępności dla osób z niepełnosprawnościami. AccessibilityService jest używany do wykrywania widocznych kart ofert kurierskich i wykonania lokalnego OCR, aby odczytać kwotę, dystans i czas potrzebne do obliczenia opłacalności.

## Co użytkownik widzi przed włączeniem usługi
Aplikacja pokazuje osobny ekran zgody przed otwarciem ustawień Dostępności. Ekran wyjaśnia:
- jakie dane z widocznej oferty są odczytywane,
- że przy pływającej karcie zrzut może obejmować tło ekranu,
- że dane spoza oferty nie są używane ani zapisywane,
- że obliczenia odbywają się lokalnie,
- że aplikacja nie klika i nie akceptuje zleceń,
- że użytkownik musi samodzielnie wyrazić zgodę i włączyć usługę w ustawieniach Androida.

## Do formularza deklaracji
Cel użycia: analiza widocznej karty oferty i pokazanie użytkownikowi lokalnie obliczonej opłacalności przed jego własną decyzją o przyjęciu lub odrzuceniu zlecenia.

Dostępne dane: tekst widoczny na karcie oferty, w szczególności kwota, dystans, czas i planowana godzina odbioru/dostawy, jeśli jest pokazana.

Udostępnianie danych: brak. Dane nie są wysyłane na serwer i nie są używane do reklam lub profilowania.
