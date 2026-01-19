(ns demo.system
  "System state and lifecycle management for the demo.")

(def ^:dynamic *system*
  "Dynamic var holding the running system.
   Contains :store, :dispatch, and :server keys."
  nil)
