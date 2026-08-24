# FUJARA 0.8.9 — stabilny odczyt Pyszne

## Najważniejsza poprawka

- Nakładka na ekranie szczegółów Pyszne nie pojawia się już na pierwszym, przejściowym odczycie.
- Po wejściu w kolejne zlecenie FUJARA najpierw chowa panel i czeka, aż nowe dane będą stabilne.
- To samo ID zlecenia musi zostać potwierdzone w kolejnych odczytach, zanim panel zostanie pokazany.
- Pierwszy poprawny odczyt uruchamia szybszy skan potwierdzający, więc opóźnienie jest krótkie.

## Ochrona przed starymi/mieszanymi danymi

- OCR i Accessibility na szczegółach zlecenia są analizowane osobno, a nie sklejane w jeden tekst.
- Jeżeli OCR widzi jeszcze poprzednie zlecenie, a Accessibility już nowe (różne ID), FUJARA uznaje ekran za przejściowy i nic nie pokazuje.
- Kandydat szczegółów musi mieć jawny numer zlecenia i jedną spójną datę.
- Niepełny ekran (brak ID, daty, kwoty, dystansu lub czasu potrzebnego parserowi) pozostawia nakładkę schowaną i jest ponownie skanowany.

## Nawigacja

- Po kliknięciu/przejściu z widocznych szczegółów panel jest chowany natychmiast, zanim Pyszne zdąży podmienić zawartość.
- Powrót do Historii przychodów / Podsumowania dnia ponownie uzbraja stabilizator przed następnym zleceniem.

## Wersja

- versionName: 0.8.9
- versionCode: 26
