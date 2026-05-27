# Glimpse

Glimpse is a small Clojure diagnostics library for tracking which map paths your
code actually reads.

Wrap a map with `glimpse/track`, pass it to normal Clojure code, then summarize
the recorded accesses. Glimpse is intended for REPL and development diagnostics:
finding over-fetched data, spotting missing keys, and checking which parts of a
large data structure a code path really uses.

## Installation

Glimpse has not been published yet. While developing from a local checkout:

```clojure
{:deps {glimpse/glimpse {:local/root "/path/to/glimpse"}}}
```

The release coordinate will be added before the first published version.

## Basic Usage

```clojure
(require '[glimpse :as glimpse])

(let [tracked (glimpse/track {:user/name "Alice"
                              :user/email nil
                              :user/address {:address/city "Berlin"}})]
  (:user/name tracked)
  (:user/email tracked)
  (:user/missing tracked)
  (-> tracked :user/address :address/city)
  (glimpse/summarize tracked))
```

`summarize` returns the paths that were accessed, plus separate counts for
present `nil` values and missing keys:

```clojure
{:accessed-paths #{[:user/name]
                   [:user/email]
                   [:user/missing]
                   [:user/address]
                   [:user/address :address/city]}
 :nil-paths #{[:user/email]}
 :missing-paths #{[:user/missing]}
 :counts {:access {[:user/name] 1
                   [:user/email] 1
                   [:user/missing] 1
                   [:user/address] 1
                   [:user/address :address/city] 1}
          :nil {[:user/email] 1}
          :missing {[:user/missing] 1}}}
```

## What Gets Tracked

Lookup, `get`, `find`, and realized map iteration are tracked. `contains?` is
treated as a presence check and is not tracked as an access.

Lazy sequences are tracked as consumers realize them. If you want diagnostics for
every item in a lazy result, force realization with `doall`, `vec`, or the
`:realize` option to `track-call`.

Transient operations on a tracked map (`transient` / `assoc!` / `dissoc!` /
`persistent!`) operate on the underlying data and return an untracked map.
Tracking ends when you go transient.

Tracked maps implement enough `java.util.Map` behavior for compatibility.
`java.util.Map.get` is treated as a tracked read, but Java collection views such
as `entrySet`, `keySet`, and `values` are compatibility-oriented and should not
be used when you need diagnostics.

```clojure
(glimpse/track-call
 (fn [tracked-data]
   (map :user/name (:users tracked-data)))
 {:users [{:user/name "Alice"}
          {:user/name "Bob"}]}
 :realize doall)
```

`with-instrumented-var-root` can temporarily instrument a var that returns maps:

```clojure
(glimpse/with-instrumented-var-root
 #'load-users
 #(doseq [user (load-users)]
    (:user/name user)
    (:user/email user)))
```

This mutates a var root while the diagnostic function runs. It is intended for
REPL/dev investigation, not concurrent production request handling.

## Caller Locations

Pass `:with-locations? true` to record where accesses came from:

```clojure
(let [tracked (glimpse/track {:user/name "Alice"} :with-locations? true)]
  (:user/name tracked)
  (glimpse/paths-with-locations tracked))
```

Location tracking captures stack traces on access, so it is useful for focused
debugging but should be treated as expensive.

## Datomic Helpers

Optional Datomic pull-pattern helpers live in `glimpse.datomic`:

```clojure
(require '[glimpse.datomic :as glimpse.datomic])

(def pulled-paths
  (glimpse.datomic/pull-pattern->paths
   [:user/name
    :user/email
    {:user/address [:address/city]}]))

(glimpse.datomic/compare-to-pattern
 #{[:user/name] [:user/phone]}
 pulled-paths)
```

## Development

```sh
bb test
bb splint
bb lint:clojure-lsp
bb check
bb nrepl
```

`src/glimpse.clj` is the dependency-free core API. Optional integrations should
stay in separate namespaces.

## Releases

After editing `CHANGELOG.md` with notes under `## Unreleased`:

```sh
bb release 0.1.0
```

The `release` task validates the version, branch (`main`), clean tree, that the
tag doesn't already exist, and that the CHANGELOG has an `## Unreleased`
heading; runs `bb check`; bumps `CHANGELOG.md`; commits; tags `vX.Y.Z`; pushes
the tag; and deploys.

Clojars credentials are read from a 1Password Login item titled
`Clojars deploy — io.github.cyrik/glimpse` (Private vault, personal account)
via the 1Password CLI. To override, set `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` in the environment — the task respects them and skips
1Password.

Version comes from the git tag at `HEAD`; `bb deploy` refuses to publish
SNAPSHOT versions unless `ALLOW_SNAPSHOT=1` is set.
