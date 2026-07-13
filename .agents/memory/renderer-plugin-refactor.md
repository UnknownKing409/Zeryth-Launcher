---
name: RendererPlugin interface refactor (upstream sync)
description: RendererPlugin's uniqueIdentifier/glName/eglName/env/dlopen became interface methods, not constructor properties — breaks call sites that weren't part of a merge conflict.
---

When upstream (ZalithLauncher2) refactored `RendererPlugin` to implement `RendererInterface`
(methods like `getUniqueIdentifier()`, `getDlopenLibrary()`, `getRendererEnv()`) instead of the
old flat constructor properties (`uniqueIdentifier: String`, `glName`, `eglName`, `env`, `dlopen`),
files that used the old property-style access (`it.uniqueIdentifier`) but were NOT touched by
this same diff auto-merged cleanly with no conflict markers — yet still failed to compile
(`Cannot infer type for type parameter`, `Unresolved reference`).

**Why:** Git's line-based 3-way merge only flags a conflict when both sides touch the *same*
lines. A call site elsewhere in the file that references the old API surface can be
textually untouched by either side and merge silently, while still being semantically broken
by an auto-merged class change elsewhere.

**How to apply:** After resolving a Kotlin/Java merge with zero conflict markers, don't assume
"no markers = compiles." Grep for other usages of any class/interface that changed shape in the
merge (property → method, renamed/removed fields) across the whole repo, not just the files that
had conflicts. The only reliable verification here is a full CI build (no local Android SDK in
this repo).
