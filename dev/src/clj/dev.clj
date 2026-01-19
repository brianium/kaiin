(ns dev
  (:require [clojure.pprint :refer [pprint]]
            [clj-reload.core :as reload]
            [portal.api :as p]
            [ascolais.kaiin :as kaiin]
            [demo.app :as demo]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Portal Setup (reload-safe)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce portal (p/open))
(defonce _setup-tap (add-tap #'p/submit))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def config
  "System configuration."
  {:environment "development"})

(defn start
  "Start the development system with lobby demo."
  ([]
   (start config))
  ([c]
   (tap> {:event :system/start :config c})
   (demo/start-system (get c :port 3000))
   :started))

(defn stop
  "Stop the development system."
  []
  (tap> {:event :system/stop})
  (demo/stop-system)
  :stopped)

(defn suspend
  "Suspend the system before namespace reload."
  []
  (tap> {:event :system/suspend})
  (demo/stop-system))

(defn resume
  "Resume the system after namespace reload."
  [c]
  (tap> {:event :system/resume :config c})
  (demo/start-system (get c :port 3000)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reloading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reload
  "Reload changed namespaces.

  This is the preferred way to reload during development. Consider binding
  this to a keyboard shortcut in your editor (e.g., C-c r in Emacs)."
  []
  (reload/reload))

(defn restart
  "Full restart: stop, reload, and start.
   Note: reload triggers after-ns-reload hook which resumes the system."
  []
  (stop)
  (reload))

;; clj-reload hooks
(defn before-ns-unload []
  (suspend))

(defn after-ns-reload []
  (resume config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Development Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-config
  "Display current configuration."
  []
  (pprint config))
