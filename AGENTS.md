# Glimpse Agent Notes

## Project

Glimpse is a small standalone Clojure diagnostics library for tracking which map
paths application code actually reads. Treat the public API as release-facing:
names, docstrings, return shapes, and examples should be changed deliberately.

## Namespace Shape

- Keep `src/glimpse.clj` as the main public API.
- Keep `src/glimpse.clj` dependency-free unless a dependency is clearly needed for
  core tracking behavior.
- Put optional integrations in separate namespaces, such as
  `src/glimpse/datomic.clj`.
- Prefer exposing the common REPL/dev workflow from `glimpse` itself. Avoid
  namespace separation that makes the initial library annoying to use.

## Coding Style

- Prefer small, direct functions over framework or abstraction machinery.
- Trust internal code and Clojure/JVM guarantees. Validate only at real system
  boundaries.
- Let unexpected errors surface; do not wrap code in broad defensive
  `try`/`catch`.
- Keep comments sparse. Public docstrings should be concise and accurate; inline
  comments should explain why, not restate what the code says.
- Preserve the current distinction between present `nil` keys and missing keys.
- Preserve lazy behavior: lazy sequences should only record accesses that the
  consumer realizes.

## Tests

- Use Babashka tasks for repeatable checks:
  - `bb format`
  - `bb format:check`
  - `bb test`
  - `bb splint`
  - `bb lint:clojure-lsp`
  - `bb check`
- `bb check` includes `bb format:check`; run `bb format` when formatting fails.
- Tests live under `test/` and use `clojure.test` with matcher-combinators.
- Prefer nested `testing` blocks in the shape `given` / `when` / `then`.
- Prefer `(is (match? expected actual))` over raw equality assertions.
- After editing Clojure source, reload the changed namespace before relying on
  REPL/runtime behavior.

## Tooling

- Default to `bb` for scripts that should be kept or revisited.
- Use `bb nrepl` for local REPL work; it starts nREPL with test deps and CIDER
  middleware.
- Generated local files are intentionally ignored, including `.cpcache/`, `.lsp/`,
  `.clj-kondo` generated imports/cache files, `.DS_Store`, and
  `.vscode/virtualTab.json`.
- Do not commit generated tool caches.

## Release Hygiene

- Keep dependencies minimal and obvious.
- Keep the library backward compatible by default. Useful breaking changes should
  be proposed explicitly, and the user should make the decision to break
  compatibility.
- Keep rich comments useful as REPL examples, but avoid turning them into a second
  README.
- When changing return maps, helper names, or summary keys, update tests,
  docstrings, and rich comments in the same pass.
