# Merged into every app that uses nearby, so a game's R8 release build needs no copy (ceo, 2026-11-07).
# Ktor's IntelliJ debugger check references JVM-only classes that Android does not have.
-dontwarn java.lang.management.**
