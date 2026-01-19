# 003: Token Replacement System

**Status:** Active
**Priority:** High
**Dependencies:** 002-registry-metadata

## Summary

Define how `[::kaiin/signal ...]` and `[::kaiin/path-param ...]` tokens are replaced with actual values at request time.

## Overview

Tokens in `::kaiin/dispatch` and `::kaiin/target` are placeholders that get replaced with values extracted from the HTTP request. This happens at handler execution time, not router creation time.

## Token Resolution Context

At request time, the handler has access to:

```clojure
{:signals     {...}       ;; Parsed Datastar signals from request body
 :path-params {:room-id "general", :user-id "123"}}  ;; Reitit path parameters
```

## Token Types and Resolution

### Signal Token: `[::kaiin/signal path]`

Extracts values from Datastar signals.

**Single keyword path:**
```clojure
;; Token
[::kaiin/signal :message]

;; Resolution
(:message signals)
;; With signals = {:message "hello"}
;; Result: "hello"
```

**Vector path (get-in semantics):**
```clojure
;; Token
[::kaiin/signal [:user :name]]

;; Resolution
(get-in signals [:user :name])
;; With signals = {:user {:name "Alice"}}
;; Result: "Alice"
```

### Path Param Token: `[::kaiin/path-param param-name]`

Extracts values from reitit path parameters.

```clojure
;; Token
[::kaiin/path-param :room-id]

;; Resolution
(:room-id path-params)
;; With path-params = {:room-id "general"}
;; Result: "general"
```

## Replacement Algorithm

```clojure
(defn token? [x]
  (and (vector? x)
       (#{::kaiin/signal ::kaiin/path-param} (first x))))

(defn resolve-token [token {:keys [signals path-params]}]
  (let [[type path] token]
    (case type
      ::kaiin/signal (if (vector? path)
                       (get-in signals path)
                       (get signals path))
      ::kaiin/path-param (get path-params path))))

(defn replace-tokens [form context]
  (walk/postwalk
    (fn [x]
      (if (token? x)
        (resolve-token x context)
        x))
    form))
```

## Example: Full Token Replacement

Given metadata:
```clojure
{::kaiin/dispatch [:chat/send-message
                   [::kaiin/path-param :room-id]
                   [::kaiin/signal :message]]
 ::kaiin/target [:* [:chat [::kaiin/path-param :room-id]]]}
```

And request context:
```clojure
{:signals {:message "Hello, world!" :username "alice"}
 :path-params {:room-id "general"}}
```

After replacement:
```clojure
{:dispatch [:chat/send-message "general" "Hello, world!"]
 :target [:* [:chat "general"]]}
```

## Nested Token Replacement

Tokens can appear at any depth in the data structure:

```clojure
;; Deeply nested token
::kaiin/target [::sfere/default-scope
                [:room [::kaiin/path-param :room-id]
                       [::kaiin/signal [:session :id]]]]

;; After replacement with path-params={:room-id "lobby"}
;; and signals={:session {:id "sess-123"}}
[::sfere/default-scope [:room "lobby" "sess-123"]]
```

## Error Handling

### Missing Signal Value

When a signal token references a path not present in signals:

**Options:**
1. Return `nil` (let downstream handle it)
2. Throw an exception with clear error message
3. Use a default value if provided

**Recommendation:** Option 2 - fail fast with a clear error:

```clojure
(when (nil? resolved)
  (throw (ex-info "Missing signal value"
                  {:token token
                   :available-signals (keys signals)})))
```

### Missing Path Parameter

Path parameters should always be present if the route matched. If missing, this indicates a configuration error:

```clojure
(when (nil? resolved)
  (throw (ex-info "Missing path parameter"
                  {:param path
                   :path-params path-params})))
```

## Validation at Router Creation

At router creation time, we can validate:

1. **Signal tokens match schema:** Every `[::kaiin/signal path]` should be extractable from the `::kaiin/signals` schema
2. **Path params match route:** Every `[::kaiin/path-param name]` should correspond to a parameter in `::kaiin/path`

```clojure
(defn validate-tokens [kaiin-metadata]
  (let [{:keys [::kaiin/path ::kaiin/signals ::kaiin/dispatch ::kaiin/target]} kaiin-metadata
        route-params (extract-params path)  ;; #{:room-id :user-id}
        signal-keys (extract-map-keys signals)  ;; #{:message :username}
        all-tokens (find-tokens [dispatch target])]
    (doseq [[type path :as token] all-tokens]
      (case type
        ::kaiin/signal
        (when-not (valid-signal-path? signal-keys path)
          (throw (ex-info "Signal token not in schema" {:token token})))

        ::kaiin/path-param
        (when-not (contains? route-params path)
          (throw (ex-info "Path param not in route" {:token token :route-params route-params})))))))
```

## Open Questions

1. ~~**Default Values:**~~ **RESOLVED** - No defaults for v1.

2. ~~**Type Coercion:**~~ **RESOLVED** - No coercion. Pass values as-is.

3. ~~**Computed Tokens:**~~ **RESOLVED** - No computed tokens for v1. Handle in effect handler if needed.

4. ~~**Validation Strictness:**~~ **RESOLVED** - Strict. Fail fast at router creation time.

## Related Specs

- [002-registry-metadata](./002-registry-metadata.md) - Token syntax definition
- [004-handler-generation](./004-handler-generation.md) - Where token replacement happens
