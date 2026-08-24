# FUJARA 0.8.4

Android app for local courier-offer profitability analysis.

## Google Play readiness
- applicationId / namespace: `pl.fujara.app`
- targetSdk / compileSdk: 36
- versionCode: 21
- versionName: 0.8.4
- signed AAB workflow: `.github/workflows/build-play-aab.yml`
- debug APK workflow: `.github/workflows/test-apk.yml`
- privacy page: `docs/privacy.html`
- reviewer demo: `Pokaż podgląd nakładki` / `Show calculation demo`
- AccessibilityService prominent disclosure before settings
- no `INTERNET` permission, no ads, no analytics in the current project

Start with `PLAY-CONSOLE-START-HERE.md`.

## Privacy page
The current support/privacy contact is `aleksanue@gmail.com`. Publish `/docs` using GitHub Pages before submitting the store listing.


## 0.8.3
Na ekranie szczegolow Pyszne kwota jest pobierana z pola `Suma przychodow`, a nie z pierwszej widocznej pozycji finansowej.


## 0.8.4
- Pyszne: przycisk `ZAPISZ DANE` na ekranie szczegolow zakonczonego zlecenia.
- Lokalny log dostaw z blokada duplikatow (hash numeru zlecenia, a przy braku numeru fingerprint czasu/kwoty/dystansu).
- Podsumowanie dnia: kontrola liczby zlecen i kwoty, km, czas, PLN/h, PLN/km, SUPER/NA STYK/FUJARA i ranking restauracji.
- Animowane podsumowanie i udostepnianie wyniku przez system Android.
- Bez permission `INTERNET`; wspolny scoreboard online nie jest jeszcze wlaczony.
