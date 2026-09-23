# Preparing a public NaIgre release

This is a manual GitHub Releases / Obtainium workflow. None of the Gradle tasks
below commit, tag, upload, install an APK or reset an existing installation.

## Identity

The single version source is `app/build.gradle.kts`:

- `versionName`: `YY.MM.PATCH`, e.g. `26.09.0`. Pad the month to two digits.
- `PATCH`: start at `0` each month and increment for every published release,
  including features and fixes. Use the release month, not the build machine's clock.
- `versionCode`: a separate increasing integer; **never reset it** with the month/year.
- GitHub tag: `v26.09.0`; APK: `NaIgre-26.09.0.apk`.
- Leave historical tags/changelog entries intact. CalVer is not strict SemVer;
  do not use a SemVer validator that rejects the leading zero in the month.

| Variant | Application ID | Launcher label | Signing |
| --- | --- | --- | --- |
| Public release | `wund0r.naigre.reader` | NaIgre | Permanent private release key |
| Development | `wund0r.naigre.reader.debug` | NaIgre Debug | Local Android debug key |
| Device verification | `wund0r.naigre.reader.verification` | NaIgre Verification | Local Android debug key |

All retain the source namespace `wund0r.naigre.reader`. Do not change the public
application ID or casually regenerate its key after distribution.

## Create a signing key once

Linux/JDK 17+ example; pick a **new directory outside the repository**:

```sh
./gradlew :app:createReleaseKey -PnaigreKeyDirectory="$HOME/.local/share/naigre/signing"
```

This creates a 3072-bit RSA PKCS12 key valid for 30 years, with alias `naigre`
and public certificate subject `CN=NaIgre`. A cryptographically random password is
saved in `release.properties`, not printed or passed as a command-line argument.
The directory has mode `700`; the key and credentials have mode `600`.
The task refuses an existing directory. Do not delete or replace a real key to rerun it.

Select the configuration for release commands:

```sh
export NAIGRE_SIGNING_PROPERTIES="$HOME/.local/share/naigre/signing/release.properties"
./gradlew :app:checkReleaseKey
```

For an existing key, create a private Java properties file outside the repository
containing `storeFile` (absolute path), `storeType` (`PKCS12` or `JKS`),
`storePassword`, `keyAlias` and `keyPassword`, then point the environment variable
at it. Respect Java properties escaping; the key-generation task handles this automatically.

**Back up both the keystore and its credentials to an independent, secure location**
(for example encrypted offline storage). The generated credentials file contains
the password, so possession of both files gives signing authority. Permissions on
this laptop are not a backup. Check the backup can be read and record the signing
certificate's SHA-256 fingerprint from `release-info.txt`. Never publish either
private file, paste its contents into an issue, or include it in build logs/scans.
If restored to another location, update `storeFile` in the private configuration.

Future updates must use this key. Losing it is not something Obtainium can repair.
See [Android app signing](https://developer.android.com/studio/publish/app-signing).

## Build and verify a release candidate

Set the Android SDK location as described in the README, then:

```sh
./gradlew :app:preparePublicRelease
```

The task validates credentials, runs the existing verification JVM tests and
release lint, builds the release APK, and verifies:

- APK signatures using Android's `apksigner`.
- Actual APK signer matches the configured release certificate, not Android Debug.
- Correct public application ID, CalVer version, build code and launcher label.
- The APK is not debuggable.
- APK ZIP alignment, including 16 KB alignment of uncompressed native libraries.
  This is a packaging check, not a substitute for running on a 16 KB-page device.

Output is under `build/public-release/26.09.0/` for the current candidate:

- `NaIgre-26.09.0.apk` — the one universal installable APK.
- `NaIgre-26.09.0.apk.sha256` — APK checksum.
- `release-info.txt` — identity, signer fingerprint, checksum, Git revision and dirty-tree status.
- `LICENSE`, `NOTICE` and `licenses/` — license texts/notices, also bundled in the APK.

These are local candidates, not published releases. Rebuilding an unpublished
candidate can replace these files; **never replace an already published version's
APK with different bytes**. Bump the version/code and publish a new release instead.
The signing-certificate fingerprint identifies the key; the APK checksum identifies
one particular build. A checksum alone is not proof of authenticity.

Without `NAIGRE_SIGNING_PROPERTIES`, debug and verification builds work normally.
`assembleRelease` can still produce an **unsigned** APK for contributor build checks;
`preparePublicRelease` refuses missing credentials. Never upload an unsigned artifact,
`app-debug.apk`, or the verification APK. Minification/resource shrinking remain disabled.

## Acceptance before publishing

- [ ] Complete the current [signed-device checklist](TESTING.md). Test the actual release build.
- [ ] Exercise a real higher-version update signed with the same key. Do not uninstall between versions.
- [ ] Confirm public/debug/verification packages and labels are independent.
- [ ] Verify the independent signing-material backup. Compare its certificate fingerprint.
- [ ] Review the files going public: no keys, passwords, private PDFs, campaigns or unlicensed screenshots.
- [ ] Review resolved runtime dependencies: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
- [ ] Prepare corresponding source and license notices, as described below.
- [ ] Update README status, CHANGELOG release notes and any known limitations truthfully.
- [ ] Commit the intended source changes, rebuild, and check `Uncommitted changes: false` in `release-info.txt`.
- [ ] Confirm the tested code is the revision to be tagged. Any code change after testing needs revalidation.
- [ ] Check [Android developer-verification requirements](https://developer.android.com/developer-verification)
      for the intended distribution audience; Obtainium does not bypass platform installation rules.

### Source and license handoff

NaIgre is AGPL-3.0-or-later and includes MuPDF. Keep `LICENSE`, `NOTICE`, the
matching application source and build instructions accessible alongside the APK.
Review the exact dependency versions in `NOTICE` against Gradle's resolved graph.

The repository keeps text notices in `licenses/` and exact upstream source links
in `NOTICE`. We do not vendor source archives or download them during builds.
On the release page, link the matching NaIgre source tag and its `NOTICE` file so
recipients have clear directions to dependency sources. Check that those sources
remain accessible; linking to another server does not remove that responsibility.

For MuPDF **1.28.0**, the Android build is pinned at
`e2190034d124004e335167f53b9b7aed52c370f4` in `mupdf-android-fitz`; it references
native revision `205b8cf43551279d1215e88fe2845c5d595bade9`. The Android project
must be checked out recursively to include native dependencies, Java bindings
and build scripts. A top-level GitHub ZIP or Java sources JAR alone is incomplete.
The other runtime source links and their licenses are listed in `NOTICE`.

The APK preparation task bundles the notices and checks the binary; it does
**not** certify license compliance. Checking source access alongside the final
tagged release remains a manual publication step.

## Publish only after explicit approval

1. Create an annotated `vYY.MM.PATCH` tag at the verified clean source revision and push it.
2. Create a GitHub release for that tag, marked **Prerelease** during beta.
3. Attach the verified universal APK, checksum and reviewed source/license materials.
   Include the signing-certificate fingerprint in the release notes.
4. Describe the changes and known limitations; link installation and Obtainium instructions.
5. Check the download links, add the repository in Obtainium with **Include prereleases**
   enabled, and verify installed-version detection. Keep tag/APK version formatting aligned.
6. Retain old releases. Test actual Obtainium update delivery on the next release.

Do not publish credentials or raw logs/diagnostics containing private source names.
There is no automatic GitHub publishing pipeline or in-app updater.

## One-time transition for the three prototype testers

Old debug builds occupied `wund0r.naigre.reader`. The new release certificate cannot
directly update those builds. After the user agrees, uninstall the old prototype,
install the public APK and re-add documents. This intentionally loses app-owned
library metadata, tags, visits, tabs and settings; original files remain untouched.
No migration machinery is provided. Ordinary subsequent release updates should
retain app data. Never use uninstall as the standard update procedure.
