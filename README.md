# Trackching

A personal finance tracker. It tracks and gives reports on your spending, income, accounts,
credit and IOUs by the day and by event. This will be available as a mobile application for Android.
A web app version is planned to be developed in the future for iPhones.

## About this project
Trackching is a side project of mine which I built with the purpose of learning and practicing Kotlin and Android mobile development.
I run it like a small team project, with planned stories, PR reviews and CI.

## Project layout

| Folder | What lives there |
| --- | --- |
| `shared/` | Everything both apps share: screens (Compose Multiplatform), data and money logic. |
| `androidApp/` | The small Android shell: starts the app and shows the shared `AppShell()` screen. |
| `.github/workflows/` | CI: builds the app and runs all tests on every push and pull request. |

Built with Kotlin Multiplatform, Compose Multiplatform and (soon) SQLDelight.

## Two apps on one phone

| App | Application ID | Use it for | Install it with |
| --- | --- | --- | --- |
| **Trackching** | `app.trackching` | Your real money. Updates keep your data. | `./gradlew :androidApp:installRelease` on your own computer |
| **Trackching Dev** | `app.trackching.dev` | Testing. Uninstall it whenever you like. | ▶ Run in Android Studio, or the `trackching-dev-apk` from CI |

Use only the **Trackching** you build yourself for real data. Until real release signing (P4-4), it's
signed with your computer's debug key, and CI makes a new debug key on every run, so CI's
`trackching-release-apk` can't update the app on your phone.

### Changing a table

1. Change the `CREATE TABLE` in the `.sq` file.
2. Add the next migration in `shared/src/commonMain/sqldelight/migrations/` (`1.sqm` upgrades version 1 to 2).
3. Run `./gradlew :shared:generateCommonMainTrackchingDatabaseSchema` to save the new version's `.db`.
4. `./gradlew build` checks that the migrations and the `.sq` files agree.

## Build it yourself

Needs JDK 21 and the Android SDK (Android Studio installs both).

```sh
./gradlew build                      # build everything and run all tests
./gradlew :androidApp:installDebug   # install Trackching Dev on a connected phone
```

## How work is done

Work happens on a branch and comes in by pull request. CI must be green before a merge.
