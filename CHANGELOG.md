# 1.0.0 (2026-03-27)


### Bug Fixes

* add gradle.properties with android.useAndroidX=true ([2e8473f](https://github.com/Carsten-Leue/ssh-fs-provider/commit/2e8473f8cdca435ce6c919d805ed533d0b2a0ba3))
* **build:** add java.util.Properties import to fix Kotlin DSL compile error ([83a2063](https://github.com/Carsten-Leue/ssh-fs-provider/commit/83a20635203023749246dcb43d395e9a09d50a9d))
* bump minSdk to 28 to satisfy security-crypto:1.1.0-alpha06 requirements ([c8a675b](https://github.com/Carsten-Leue/ssh-fs-provider/commit/c8a675bc13f2a9f6df639a78ddf3ec2bc981d353))
* **ci:** add Gradle wrapper to ensure consistent Gradle 8.6 across runs ([e911e8a](https://github.com/Carsten-Leue/ssh-fs-provider/commit/e911e8aa8a7b4955e287aceefa67cb4b6a9d5ca5))
* **ci:** capture semantic-release output for diagnostic annotations ([4b5c8f9](https://github.com/Carsten-Leue/ssh-fs-provider/commit/4b5c8f9ce584876bf1fdee22d6f40ecafe0d5614))
* **ci:** remove @semantic-release/git to test if git push causes failure ([1c1f52e](https://github.com/Carsten-Leue/ssh-fs-provider/commit/1c1f52e0beaa861a4cdc79e241e5954a74a69734))
* **ci:** remove npm cache from setup-node (package-lock.json is gitignored) ([568ba6b](https://github.com/Carsten-Leue/ssh-fs-provider/commit/568ba6bb4a2c03a05e60218406cf9dd20abf46be))
* **ci:** test @semantic-release/github without APK asset ([5516a2a](https://github.com/Carsten-Leue/ssh-fs-provider/commit/5516a2adfa593f748ac4209c310c989b2b9caf51))
* **ci:** use npm install instead of npm clean-install in release job ([b92b145](https://github.com/Carsten-Leue/ssh-fs-provider/commit/b92b145220b84de19f1797d89b9d9f7ef1f3c703))
* downgrade security-crypto to stable 1.0.0, restore full release config ([a1fe970](https://github.com/Carsten-Leue/ssh-fs-provider/commit/a1fe97063bee5f43dcd565ebdd6c0045fb16abc0))
* remove double-dash sequences from XML comments in strings.xml ([ffd64a8](https://github.com/Carsten-Leue/ssh-fs-provider/commit/ffd64a8fe20fc5ad8acc7af396fdf20ba6694ece))
* replace invalid domain=cache with domain=root in data_extraction_rules.xml ([4b6a021](https://github.com/Carsten-Leue/ssh-fs-provider/commit/4b6a021e6bdb464e71aecd9019c12bd6c685e5bd))


### Features

* **ci:** add semantic-release with APK release artifacts ([1ba5f35](https://github.com/Carsten-Leue/ssh-fs-provider/commit/1ba5f352b0e5a68420e64486ad4034c301828d59))


### BREAKING CHANGES

* **ci:** → major bump (1.0.0 → 2.0.0)
  chore:, docs:, style:, refactor:, perf:, test: → no release

Changes:
- package.json: semantic-release + plugins
  (@semantic-release/changelog, exec, git, github)
- .releaserc.json: plugin pipeline — analyzeCommits →
  releaseNotes → changelog → exec (build APK) → git (commit
  CHANGELOG.md) → github (create release + upload APK asset)
- scripts/prepare-release.sh: derives versionCode from semver
  (major*10000 + minor*100 + patch), writes version.properties,
  runs gradle assembleRelease, renames APK to
  ssh-fs-provider-<version>.apk
- app/build.gradle.kts: reads version.properties for versionCode /
  versionName (falls back to 1 / 0.0.0-dev locally); adds
  signingConfigs.release populated from SIGNING_KEY_PATH env var
  set by CI; signingConfig = null when key absent → unsigned APK
- .github/workflows/build.yml: split into two jobs:
    ci     — always: builds debug APK, uploads as artifact
    release — main only: npm install → decode signing key →
              semantic-release (prepare + publish + git commit)
- .gitignore: exclude version.properties and node_modules/

https://claude.ai/code/session_01CMWKPhJLe9EHz1NnBosga4
