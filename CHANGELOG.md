# Changelog

## Unreleased

## 0.1.1 — 2026-05-27

- Fixed: tracked maps now implement `IEditableCollection`, so `transient`
  works. Transient operations operate on the underlying data and return
  untracked maps.
- Fixed: tracked maps now implement `MapEquivalence`, `IHashEq`, and
  `java.util.Map`, so `(= plain tracked)`, `(hash tracked)`, and standard
  map operations (`merge`, `update`, `select-keys`, `reduce-kv`) behave
  consistently with regular Clojure maps.
- Fixed: `java.util.Map.get` on a tracked map now records the access and
  wraps nested values, matching `clojure.lang.ILookup` semantics.
- Fixed: `Object.equals` and `Object.hashCode` now delegate to the
  underlying map's implementations, so tracked maps satisfy the Java
  `Map` contract — `.equals` is symmetric with plain maps and `.hashCode`
  matches Java `HashMap` expectations.

## 0.1.0 — 2026-05-26

- Initial standalone Glimpse library.
- Added tracked map access summaries with separate accessed, nil, and missing
  path reporting.
- Added REPL/dev instrumentation helpers.
- Added optional Datomic pull-pattern comparison helpers.
