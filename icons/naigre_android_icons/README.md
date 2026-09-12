# NaIgre Reader — Android launcher icon candidates

Each candidate folder is drop-in structured around Android launcher resources.

Candidates
1. 01_search_books — stacked books + search field
2. 02_naigre_mark — NaIgre book/star wordmark
3. 03_dragon_books — dragon + open rules book + D6

Files
- play_store_512.png: 512×512 full-square Google Play listing asset
- master_1024.png: large preview/master
- res/drawable-*/ic_launcher_foreground.png: adaptive foreground, 108dp by density
- res/drawable-*/ic_launcher_monochrome.png: themed-icon layer
- res/mipmap-*/ic_launcher.png: legacy launcher icons
- res/mipmap-*/ic_launcher_round.png: legacy round launcher icons
- res/mipmap-anydpi-v26/*.xml: adaptive launcher definitions
- res/values/colors.xml: icon background
- AndroidManifest_snippet.xml: launcher references

Install
1. Pick ONE candidate.
2. Copy that candidate's `res/` contents into `app/src/main/res/`.
3. Merge `colors.xml` if your project already has one instead of overwriting it.
4. In AndroidManifest.xml, make sure <application> uses:
   android:icon="@mipmap/ic_launcher"
   android:roundIcon="@mipmap/ic_launcher_round"
5. Rebuild/reinstall the app. Some launchers cache icons; uninstalling the old build can
   be the quickest way to see a launcher-icon change during development.

Notes
- The adaptive foreground artwork is deliberately kept inside ~60% of the 108dp layer,
  corresponding closely to Android's 66dp safe zone.
- Monochrome resources are included for themed icons.
- Google Play's 512×512 asset is full-square; do not add your own outer rounded-corner mask.
