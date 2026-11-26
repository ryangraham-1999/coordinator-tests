# Coordinator (test copy)

Coordinator is an offline Android app for recording damage found on cars and sorting each job onto a contractor's list.

This repo is a test copy of the app's code. It exists so the phone (emulator) tests and the debug test build can run on GitHub. Each commit here is a snapshot copied from the main repo, so please don't open pull requests here: changes made here are overwritten by the next snapshot.

## What runs here
- `device-tests` runs the app's instrumented tests on an API 34 emulator and reports the result as the check run `smj-device` on the commit.
- `test-build` builds the debug APK ("Coordinator (debug)") and attaches it to the run as `coordinator-debug-apk`. It is signed with a throwaway test key, installs beside the real app and never touches it.

Both run on every push to any branch, and can be started by hand from the Actions tab.

## Build and check
- `scripts/check` runs every phase: env, build, seed, manifest, deps, unit, device, docs. On Windows use `scripts\check.cmd`.
- `scripts/check --fast` skips the device phase.
- `scripts/selftest` proves the check can fail.
- `scripts/make-key` makes the release key, once, on the owner's PC only. The release key is never in this repo.

## The seed file
`app/src/main/assets/seed/lists.json` holds the starting tag lists, used only on a fresh install.
