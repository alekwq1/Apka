# FUJARA 0.6.0

Android app for local courier-offer profitability analysis.

## Google Play readiness
- applicationId / namespace: `pl.fujara.app`
- targetSdk / compileSdk: 36
- versionCode: 7
- versionName: 0.6.0
- signed AAB workflow: `.github/workflows/build-play-aab.yml`
- debug APK workflow: `.github/workflows/test-apk.yml`
- privacy page: `docs/privacy.html`
- reviewer demo: `Pokaż podgląd nakładki` / `Show calculation demo`
- AccessibilityService prominent disclosure before settings
- no `INTERNET` permission, no ads, no analytics in the current project

Start with `PLAY-CONSOLE-START-HERE.md`.

## Important
Before Google Play review, replace `WPISZ_TUTAJ_EMAIL_DEWELOPERA` in `docs/privacy.html` with the real developer support/privacy email and publish `/docs` using GitHub Pages.
