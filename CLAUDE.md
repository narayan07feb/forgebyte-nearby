# forgebyte-nearby (session: nearby)

Read docs/PLAN.md first; it holds the owner's decisions, the API shape, the order of work and the test plan.

- Kotlin 2.2.20, Ktor 3.1.0 (same as uno and satti). Android target first, iOS in week 3.
- Public repo pulled through JitPack: never commit secrets, keys, tester data or analytics ids.
- No GitHub Actions. Tests run locally with JDK 21 (~/Library/Java/JavaVirtualMachines/jbr-21.0.5).
- Never `./gradlew --stop`; use `--max-workers=2`.
- Task branches and draft PRs; no AI or Co-Authored trailers; ceo or the owner merges.
- Phone (shared Lava): only through studio/.phone-lock and studio/tools/testers/guard.sh; never an emulator without the owner's yes.
- Report to ceo with `herdr agent prompt ceo "From nearby: ..."` when a step is done or blocked.
