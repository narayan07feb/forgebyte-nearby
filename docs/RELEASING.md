# Releasing

A release is a git tag. JitPack builds the tag and serves it as
`com.github.narayan07feb.forgebyte-nearby:<module>:<tag>`. There is no store app and no `versionCode` in this repo;
`version` in `gradle.properties` stays a snapshot, and the tag is the version.

## Version number (owner rule, 2026-11-27)

Tags are `vMAJOR.MINOR.PATCH`:

| Number | Goes up when | Examples |
|---|---|---|
| MAJOR | A breaking change: an app must change its code to upgrade, or old and new builds cannot play together | a public API removed or renamed, `PROTOCOL_VERSION` or `CRYPTO_VERSION` raised, a wire message changed so older peers refuse or misread it |
| MINOR | A feature release with no breaking change | a new public API, a new optional field or TXT key that older peers ignore, a new module |
| PATCH | Fixes only: no new public API, no new feature | a bug fix, a test, a doc, an R8 rule |

- Raising MAJOR sets MINOR and PATCH to 0; raising MINOR sets PATCH to 0.
- While MAJOR is 0, a breaking change still raises MAJOR (to 1.0.0); it does not hide in MINOR.
- Write the reason for the number in the release PR body and in the tag message, for example "MINOR: new
  `NearbyHost.pause()`; wire unchanged (`PROTOCOL_VERSION` same)".
- Tags up to v0.3.10 were cut before this rule; several added features under a PATCH number. They stay as they are.
  The rule applies from the release after v0.3.10, which is at least v0.4.0 if it adds any feature.

How to check: diff the release against the last tag.

```
git diff <last tag>..<release commit> -- nearby/src/commonMain nearby/src/androidMain
```

- Any `public` line removed or changed: MAJOR.
- `PROTOCOL_VERSION` or `CRYPTO_VERSION` changed, or a serialized message type changed: MAJOR.
- Only `public` lines added, or a new module: MINOR.
- Neither: PATCH.

## Steps

1. Put all open PRs for the release into one integration branch off `main`, solve the conflicts keeping both sides,
   and open one PR. Run `studio/tools/ci-local.sh nearby <branch>` on it; it must end with exit 0 and 0 failures.
2. A PR left out needs a written reason in the PR body (an owner or ceo decision).
3. ceo or the owner squash-merges that one PR. `main` takes PRs only; never push to it.
4. Close the PRs the integration PR contains.
5. Pick the number with the rule above, and write the reason in the PR body.
6. Tag the merge commit with an annotated tag. Use the message format of the earlier tags (`git show v0.3.9`): a
   subject line, one section per PR, then `Wire:`, `Tests:` and `Phone:` lines. Write UNVERIFIED for any device
   check that has not passed. Check every API name in the message against the code first.
7. Push only the tag: `git push origin <tag>`.
8. Request the POM once to start the JitPack build, then wait until
   `https://jitpack.io/api/builds/com.github.narayan07feb/forgebyte-nearby/<tag>` reports `"status" : "ok"` and lists
   the modules.
9. Check that the Android AAR ships the consumer R8 rule (`proguard.txt` inside `nearby-android`).
10. Add the tag to the Done table in `docs/ROADMAP.md` (docs PR).
11. Tell each app the dependency line: `nearby = "<tag>"` in `gradle/libs.versions.toml`.

The public repo holds no secrets, keys, tester data, analytics ids or app names; check the tag message and PR body
for them too.
