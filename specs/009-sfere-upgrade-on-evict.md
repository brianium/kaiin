# 009: Sfere Upgrade and On-Evict for User Departure

**Status:** Complete
**Priority:** High
**Dependencies:** 006-lobby-demo, 007-action-handlers-for-broadcast

## Summary

Upgrade sfere from v0.3.0 to v0.4.0 and switch the demo from atom store to caffeine store with `:on-evict` callback. This enables automatic "user left" messages when connections expire (user closes tab, network loss) without requiring explicit leave actions.

## Motivation

Currently the demo only handles explicit leave via `POST /leave`. When a user closes their browser tab:
1. The SSE connection closes
2. The connection is purged from the store
3. Other users never know the person left

With sfere v0.4.0's unified `:on-evict` callback and caffeine's TTL-based expiration, we can detect departures and broadcast notifications automatically.

## Changes Required

### 1. deps.edn

Upgrade sfere dependency:

```clojure
;; Before
io.github.brianium/sfere {:git/tag "v0.3.0" :git/sha "586bc38"}

;; After
io.github.brianium/sfere {:git/tag "v0.4.0" :git/sha "89a63e9"}
```

### 2. demo/app.clj

Switch from atom store to caffeine store with on-evict callback.

**Challenge:** The on-evict callback needs access to `dispatch`, but `dispatch` needs the `store`. This creates a circular dependency.

**Solution:** Use an atom to capture dispatch after creation (pattern from sfere README):

```clojure
(ns demo.app
  (:require [reitit.ring :as rr]
            [ascolais.sandestin :as s]
            [ascolais.twk :as twk]
            [ascolais.sfere :as sfere]
            [ascolais.kaiin :as kaiin]
            [demo.registry :refer [lobby-registry]]
            [demo.handlers :as handlers]
            [demo.views :as views]
            [demo.system :as system]
            [org.httpkit.server :as hk]
            [starfederation.datastar.clojure.adapter.http-kit :as ds-hk]))

;; Capture dispatch for use in on-evict callback
(def ^:private *dispatch (atom nil))

(defn- on-evict
  "Handle connection eviction by broadcasting departure to remaining users.

   Cause values:
   - :expired - TTL expiration (caffeine)
   - :explicit - Explicit removal via purge (SSE close)
   - :replaced - Key was reassigned
   - :size - Evicted due to size limit
   - :collected - GC collected"
  [key _conn cause]
  (when (and @*dispatch (#{:expired :explicit} cause))
    (let [[_scope [_category username]] key]
      (@*dispatch {} {}
        [;; Broadcast departure message
         [::sfere/broadcast {:pattern [:* [:lobby :*]]}
          [::twk/patch-elements (views/participant-left username)
           {twk/selector "#messages" twk/patch-mode twk/pm-append}]]
         ;; Remove from participant list
         [::sfere/broadcast {:pattern [:* [:lobby :*]]}
          [::twk/patch-elements ""
           {twk/selector (str "#participant-" username)
            twk/patch-mode twk/pm-remove}]]]))))

(defn- create-store
  "Create caffeine store with TTL and eviction callback."
  []
  (sfere/store {:type :caffeine
                :duration-ms 30000        ;; 30 second timeout
                :expiry-mode :sliding     ;; Reset timer on access
                :on-evict on-evict}))

(defn create-dispatch
  "Create sandestin dispatch with all registries."
  [store]
  (s/create-dispatch
   [(twk/registry)
    (sfere/registry store)
    lobby-registry]))

;; ... rest unchanged ...

(defn start-system
  "Start the demo system on the given port."
  ([] (start-system 3000))
  ([port]
   (let [store (create-store)
         dispatch (create-dispatch store)
         _ (reset! *dispatch dispatch)  ;; Capture for on-evict
         handler (create-app store dispatch)
         server (hk/run-server handler {:port port})]
     ;; ...
     )))
```

### 3. demo/registry.clj - Simplify :lobby/leave

With on-evict handling departure broadcasts, the explicit leave action can be simplified to just close the connection. However, keeping the explicit broadcast provides immediate feedback (vs waiting for eviction).

**Option A: Keep both** (recommended)
- Explicit `/leave` broadcasts immediately
- On-evict catches tab closes, network loss, etc.
- No code change needed - they complement each other

**Option B: Simplify to just close**
```clojure
:lobby/leave
{::s/description "Leave the lobby - close connection, on-evict handles broadcast"
 ::s/schema [:tuple [:= :lobby/leave] :string]
 ::s/handler (fn [_state username]
               (let [user-key [::sfere/default-scope [:lobby username]]]
                 [[::sfere/with-connection user-key
                   [::twk/close-sse]]]))
 ;; ... kaiin metadata unchanged ...
 }
```

**Recommendation:** Option A - keep both. Explicit leave gives immediate feedback. On-evict is the safety net for unexpected disconnections.

### 4. Deduplication Concern

With Option A, an explicit leave followed by connection close could trigger both:
1. The explicit broadcast from `:lobby/leave`
2. The on-evict callback with cause `:explicit`

**Solution:** Track which users have explicitly left:

```clojure
(def ^:private *left-users (atom #{}))

(defn- on-evict [key _conn cause]
  (when (and @*dispatch (#{:expired :explicit} cause))
    (let [[_scope [_category username]] key]
      (when-not (contains? @*left-users username)
        ;; Only broadcast if not already explicitly left
        (@*dispatch {} {} [...]))
      ;; Clean up tracking (handles both explicit and expired)
      (swap! *left-users disj username))))

;; In :lobby/leave action - mark as explicitly left
:lobby/leave
{::s/handler (fn [_state username]
               (swap! *left-users conj username)
               ;; ... existing logic ...
               )}
```

**Alternative:** The simpler approach is to just let duplicates happen - the UI operations are idempotent (appending a "left" message twice is harmless, removing a non-existent element is a no-op). The complexity of tracking may not be worth it.

## Implementation Order

1. Update deps.edn with sfere v0.4.0
2. Add `*dispatch` atom and `on-evict` function to demo/app.clj
3. Change `start-system` to use caffeine store and capture dispatch
4. Test explicit leave still works
5. Test tab close triggers on-evict broadcast

## Testing

1. **Explicit leave:** Click "Leave Lobby" - others see departure immediately
2. **Tab close:** Close browser tab - others see departure after ~30 seconds (or when SSE close is detected)
3. **Network loss:** Disable network - same as tab close behavior
4. **Multiple users:** Verify broadcasts exclude the departing user

## Open Questions

1. Should we track explicit leaves to prevent duplicate broadcasts?
   - Leaning no - UI operations are idempotent

2. What TTL is appropriate for the demo?
   - 30 seconds is good for demo purposes
   - Production would likely be longer (5-10 minutes)

## Related Specs

- [006-lobby-demo](./006-lobby-demo.md) - Original demo spec
- [007-action-handlers-for-broadcast](./007-action-handlers-for-broadcast.md) - Action model understanding
