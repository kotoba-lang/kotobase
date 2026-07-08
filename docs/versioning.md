# kotobase (`IStore`) Compatibility Policy

`kotobase` ships one compatibility surface: the `kotobase.store/IStore`
protocol (`-put`/`-get`/`-list`/`-append`/`-read`) and the shared contract it
must satisfy (`test/kotobase/contract.cljc`). There is no separate profile
version number the way `kotoba-lang/kotoba-lang` tracks `:kotoba.lang/
profile-version` -- this repo's version is its package/library semver.

## Compatibility Rules

- **Patch-compatible**: documentation, internal implementation changes
  (`LocalStore`/`KotobaseStore` internals), and new tests that don't change
  `IStore`'s method set or `contract.cljc`'s assertions.
- **Minor-compatible**: adding a new `IStore` method, or a new backend
  implementation (a third `deftype` alongside `LocalStore`/`KotobaseStore`),
  as long as existing methods and their contract assertions are unchanged.
- **Breaking (major)**: changing an existing `IStore` method's arity,
  argument order, or documented behavior; changing `contract.cljc`'s
  assertions in a way that an existing conforming backend would newly fail;
  removing a method.
- **The contract is the compatibility surface, not the backend.** A backend
  (`LocalStore`, `KotobaseStore`, or any future one) is conforming iff it
  passes `test/kotobase/contract.cljc:verify` unchanged. Backend-internal
  representation (the shape of `LocalStore`'s atom, `KotobaseStore`'s XRPC
  wire format) may change freely without a compatibility bump as long as the
  contract still passes.

## External Implementations

An implementation conforms when it passes `contract/verify` (document-space
put/get/list with last-writer-wins, and append-only streams with a strictly
increasing `:seq` cursor and `read`-since semantics) without special-casing
the test. See `docs/coverage.edn` for current maturity evidence per stage,
including an open M5 gap: no confirmed external consumer names this repo by
package identity today (see `docs/coverage.edn`'s M5 note on the
`kotobase-clj` naming ambiguity).
