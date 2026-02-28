# Migration Documentation System

This directory contains machine-readable migration guides for Reaktiv API changes.
Each file documents every breaking change and every significant new addition between
two versions in a format designed to be executed mechanically by an LLM or a human
without ambiguity.

---

## File Naming

| File | Purpose |
|------|---------|
| `v{old}-to-v{new}.md` | Released migration guide between two tagged versions |
| `pending.md` | Accumulates changes during active development |

**On release:** rename `pending.md` → `v{old}-to-v{new}.md`, then reset `pending.md`
to the template header only.

---

## Two Entry Types

Migration files contain two sequential sections, each with its own ID sequence:

### Breaking Changes — `BC-NN`

Entries where existing code must change: compilation errors, removed APIs, changed
signatures, or silent behavioural differences that will cause bugs.

```
### [BC-NN] Short Title

**Type:** Breaking | Deprecation-removal | Behavioural

**Grep:** `exact string to search in user's .kt files`
**File glob:** `**/*.kt`

**Before:**
```kotlin
// exact pattern that must change
```

**After:**
```kotlin
// exact replacement
```

**Notes:** Caveats, false-positive matches to skip, manual-review steps.

---
```

**Grep semantics for BC entries:** matches the *old* pattern in the user's codebase.
If the grep returns any results, those locations must be updated using the
Before → After transformation.

### Additions — `AD-NN`

Entries documenting new APIs, new types, and replacements for removed deprecated
code. These entries teach the LLM what is now available so it can use the new
APIs correctly when writing or modifying code.

```
### [AD-NN] Short Title

**Type:** Addition | Replaces-deprecated

**Grep:** `new API pattern to check adoption`
**File glob:** `**/*.kt`

**Replaces:** (only for Replaces-deprecated) brief description of the old API

**Example:**
```kotlin
// self-contained example showing how to use the new API
```

**Notes:** When to use, caveats, related entries.

---
```

**Grep semantics for AD entries:** matches the *new* API in the user's codebase.
Zero matches means the user has not yet adopted this API. The LLM should prefer
using the new API in any code it writes or modifies.

---

## Field Rules

### Shared fields
- **ID** — Two independent sequences per migration file: `BC-01, BC-02, …` and
  `AD-01, AD-02, …`
- **File glob** — Restrict search scope. Usually `**/*.kt`; narrow further for
  platform-specific files.
- **Notes** — Required when there are false-positive grep matches, multi-step
  transforms, or when manual review is needed.

### BC-specific fields
- **Type**
  - `Breaking` — callers will not compile without change
  - `Deprecation-removal` — was deprecated in previous version, now removed
  - `Behavioural` — compiles but runtime semantics changed; bugs will appear
- **Grep** — Matches the *old* pattern only. Must not match the replacement.
- **Before / After** — Minimal, self-contained Kotlin snippets. Use `...` for
  surrounding boilerplate that does not change.

### AD-specific fields
- **Type**
  - `Addition` — purely new API with no old predecessor
  - `Replaces-deprecated` — new API that supersedes a removed or deprecated one
- **Grep** — Matches the *new* API. Zero matches = not yet adopted.
- **Replaces** — (Replaces-deprecated only) one-line description of the old API
  being superseded.
- **Example** — Self-contained Kotlin snippet demonstrating typical usage.

---

## Ordering Constraint

BC entries must be ordered so that applying BC-01, BC-02, … in sequence never
breaks a later entry. If two changes conflict, split them and note the dependency.

AD entries have no ordering requirement but should be grouped thematically.

---

## How to Add an Entry (for Claude / contributors)

When you introduce an API change during a session:

- **Breaking change** → immediately append a `BC-NN` entry to `migrations/pending.md`
- **New significant API** → immediately append an `AD-NN` entry
- **Removed deprecated API with a new replacement** → append both a `BC-NN`
  (the removal) and an `AD-NN` (the replacement), cross-referencing each other

Do not batch at the end of the session — append as each change is made.

Use the next available ID in each sequence (inspect the last BC-NN and AD-NN in
`pending.md` to find the current highest numbers).

---

## How to Apply a Migration (LLM workflow)

### Step 1 — Apply breaking changes

For each BC entry, in order:
1. Run the **Grep** against the target project.
2. If matches exist, apply the **Before → After** transformation to each match.
3. Verify no remaining matches of the old pattern remain.

### Step 2 — Learn new additions

For each AD entry:
1. Run the **Grep** to check current adoption level.
2. Read the **Example** to understand the API.
3. When writing or modifying code in the target project, prefer the new API over
   any older pattern it replaces.

### Step 3 — Verify

Run the project's test suite to confirm correctness after all transformations.
