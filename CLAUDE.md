# ascolais/kaiin

## Project Overview

This is a Clojure project using deps.edn for dependency management.

## Technology Stack

- **Clojure** with deps.edn
- **clj-reload** for namespace reloading during development
- **Portal** for data inspection (tap> integration)
- **Cognitect test-runner** for running tests

## Development Setup

### Starting the REPL

```bash
clj -M:dev
```

This starts a REPL with development dependencies loaded.

### Development Workflow

1. Start REPL with `clj -M:dev`
2. Load dev namespace: `(dev)`
3. Start the system: `(start)`
4. Make changes to source files
5. Reload: `(reload)`

The `dev` namespace provides:
- `(start)` - Start the development system
- `(stop)` - Stop the system
- `(reload)` - Reload changed namespaces via clj-reload
- `(restart)` - Stop, reload, and start

### Portal

Portal opens automatically when the dev namespace loads. Any `(tap> data)` calls will appear in the Portal UI.

## Project Structure

```
src/clj/          # Clojure source files
dev/src/clj/      # Development-only source (user.clj, dev.clj)
test/src/clj/     # Test files
resources/        # Resource files
```

## REPL Evaluation

Use the clojure-eval skill to evaluate code via nREPL.

### Starting an nREPL Server

To start a REPL with nREPL support (required for clojure-eval):

```bash
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev -m nrepl.cmdline --port 7888
```

This starts an nREPL server on port 7888 with all dev dependencies loaded.

### Connecting and Evaluating

```bash
clj-nrepl-eval --discover-ports          # Find running REPLs
clj-nrepl-eval -p 7888 "(+ 1 2 3)"       # Evaluate expression
```

**Important:** All REPL evaluation should take place in the `dev` namespace. After connecting, switch to the dev namespace:

```bash
clj-nrepl-eval -p 7888 "(dev)"
```

To reload code after making changes, use clj-reload:

```bash
clj-nrepl-eval -p 7888 "(reload)"
```

## Running Tests

```bash
clj -X:test
```

Or from the REPL (in the dev namespace):

```clojure
(reload)  ; Reload changed namespaces first
(require '[clojure.test :refer [run-tests]])
(run-tests 'ascolais.kaiin-test)
```

## Adding Dependencies

When adding new dependencies in a REPL-connected environment:

1. **Add to the running REPL first** using `clojure.repl.deps/add-lib`:
   ```clojure
   (clojure.repl.deps/add-lib 'metosin/malli {:mvn/version "0.16.4"})
   ```
   Note: The library name must be quoted.

2. **Confirm the dependency works** by requiring and testing it in the REPL.

3. **Only then add to deps.edn** once confirmed working.

This ensures dependencies are immediately available without restarting the REPL.

## Code Style

- Follow standard Clojure conventions
- Use `cljfmt` formatting (applied automatically via hooks)
- Prefer pure functions where possible
- Use `tap>` for debugging output (appears in Portal)

### Namespaced Keywords

Clojure has two syntaxes for namespaced keywords:

**Single colon (`:`)** - Explicit namespace, works anywhere:
```clojure
:my.app.config/timeout    ; Fully qualified namespace
:ui/visible               ; Arbitrary namespace (doesn't need to exist)
:db/id                    ; Common convention for domain markers
```

**Double colon (`::`)** - Auto-resolved namespace:
```clojure
;; In namespace my.app.core:
::key                     ; Expands to :my.app.core/key

;; With required aliases:
(require '[my.app.db :as db])
::db/query                ; Expands to :my.app.db/query
```

**When to use which:**
- Use `:` with explicit namespace when the keyword meaning is independent of the current file
- Use `::` when the keyword is specific to the current namespace
- Use `::alias/key` to reference keywords from required namespaces without typing the full name
- Prefer `:` for spec keys, component IDs, and data that crosses namespace boundaries

## Sandestin Effect System

Kaiin generates HTTP routes for sandestin registries. Understanding sandestin's effect/action model is critical.

### Effects vs Actions

Sandestin has two types of dispatchable handlers:

**Effects** - Perform side effects directly
```clojure
{::s/effects
 {:my/effect
  {::s/handler (fn [ctx sys & args]
                 ;; ctx contains :dispatch, :sse (if in SSE context), etc.
                 ;; sys is the sandestin system
                 ;; Perform side effects here
                 ;; Return value is NOT dispatched
                 )}}}
```

**Actions** - Return effects to be dispatched
```clojure
{::s/actions
 {:my/action
  {::s/handler (fn [state & args]
                 ;; state is result of system->state (for twk: {:signals ...})
                 ;; NO access to ctx or sys
                 ;; Return value IS dispatched as effects
                 [[::twk/patch-elements [:div "hello"]]])}}}
```

### Key Differences

| Aspect | Effects | Actions |
|--------|---------|---------|
| Handler signature | `(fn [ctx sys & args])` | `(fn [state & args])` |
| Has dispatch access | Yes (via ctx) | No |
| Has SSE access | Yes (via ctx) | No |
| Return value | Ignored | Dispatched as effects |
| Use case | Complex logic, direct side effects | Pure transformations, broadcast targets |

### Kaiin and Actions

Kaiin-generated routes that broadcast to connections should use **actions**:

```clojure
{::s/actions
 {:lobby/send-message
  {::s/handler (fn [_state username message]
                 [[::twk/patch-elements (views/message-bubble username message)]])

   ;; Kaiin metadata
   ::kaiin/path "/message"
   ::kaiin/target [:* [:lobby :*]]
   ::kaiin/dispatch [:lobby/send-message
                     [::kaiin/signal :username]
                     [::kaiin/signal :message]]}}}
```

When sfere broadcasts `[:lobby/send-message "brian" "hello"]` to each connection:
1. Sandestin sees it's an action
2. Calls the handler → returns `[[::twk/patch-elements ...]]`
3. Dispatches those effects to the connection's SSE

**Rule:** If kaiin generates the route and broadcasts to connections, use `::s/actions`.

## Git Commits

Use conventional commits format:

```
<type>: <description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Examples:
- `feat: add user authentication`
- `fix: resolve nil pointer in data parser`
- `refactor: simplify database connection logic`
