# FUJARA 0.7.2

## Ikona aplikacji

- Launcher używa teraz tej samej grafiki FUJARA co karta aplikacji w Google Play: czarne, zaokrąglone tło z kolorowym wskaźnikiem.
- Zmieniono `android:icon` i `android:roundIcon` na nowy zasób `ic_fujara_store.png`.
- Grafika jest przechowywana w `mipmap-nodpi`, dzięki czemu Android nie wybiera starej wektorowej ikony launchera.
- Stary `ic_fujara_app.xml` pozostaje w projekcie jako zasób graficzny aplikacji i nie jest używany jako ikona launchera.

## Wersja

- `versionCode 10`
- `versionName 0.7.2`

## Uwaga przy testach

Po aktualizacji Samsung/One UI może przez krótki czas pokazywać ikonę z pamięci podręcznej launchera. Przy świeżej instalacji powinna pojawić się nowa grafika.
