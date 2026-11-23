# Contributing to NotiAlarm

Thanks for your interest in improving 通知闹钟 / NotifyClock.

## Before you contribute

The Project is **source-available**, not OSI open source. It is licensed
under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use
requires a separate written license from the Owner; see
[COMMERCIAL.md](COMMERCIAL.md).

Because the Owner may offer commercial licenses, every contributor must
accept the [Contributor License Agreement](CLA.md) before a pull request
can be merged.

## How to accept the CLA

Open a pull request and include this exact sentence in a comment:

> I have read the CLA.md and I agree to its terms for my contributions to this project.

Then check the CLA box in the pull request template. If you contribute on
behalf of a company, state the company name in the same comment and
confirm that you are authorized to bind it.

## Development

1. Use JDK 17 and Android SDK with compileSdk 35.
2. Build and test:

   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```

3. Keep changes focused. Do not include `local.properties`, signing keys,
   API keys, tokens, `settings.xml`, or personal data.
4. Add or update unit tests for behavior changes.
5. Update `README.md` when user-visible behavior changes.
6. Use English or Chinese in code comments; keep user-facing text in
   `values/strings.xml` and `values-en/strings.xml`.

## Pull request checklist

- [ ] I have read and accept [CLA.md](CLA.md).
- [ ] I have not included API keys, credentials, or personal data.
- [ ] Tests pass locally.
- [ ] User-facing strings are localized in both Chinese and English.
- [ ] The change is compatible with the PolyForm Noncommercial license
      and the Owner's ability to offer commercial licenses.

## Reporting issues

Please include:

- App version (versionCode / versionName).
- Android version and device model.
- Steps to reproduce.
- Whether notification access and exact-alarm permissions are granted.
- Relevant logs with secrets and personal content removed.

Do not post full notification text, chat content, API keys, or
`shared_prefs/settings.xml` in public issues.

## License of contributions

By submitting a Contribution, You agree that it is licensed to the Owner
under the [CLA](CLA.md) and may be distributed as part of the Project
under the PolyForm Noncommercial License or a commercial license.
