(ns ascolais.kaiin
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]))

;; Token schemas
;; Tokens are placeholders in ::kaiin/dispatch and ::kaiin/target that get
;; replaced with actual values from signals or path parameters at runtime.

(def signal-token
  "Token that extracts a value from Datastar signals.
   - [::kaiin/signal :key] -> (:key signals)
   - [::kaiin/signal [:a :b]] -> (get-in signals [:a :b])"
  [:or
   [:tuple [:= ::signal] :keyword]
   [:tuple [:= ::signal] [:vector :keyword]]])

(def path-param-token
  "Token that extracts a value from reitit path parameters.
   [::kaiin/path-param :room-id] -> (:room-id path-params)"
  [:tuple [:= ::path-param] :keyword])

(def token
  "Any token type that can appear in dispatch or target vectors."
  [:or signal-token path-param-token])

;; Registry metadata schema

(def metadata-schema
  "Malli schema for kaiin metadata on effect registrations."
  [:map
   [::path :string]
   [::method {:optional true} :keyword]
   [::signals [:fn {:error/message "signals must be a :map schema"}
               #(and (vector? %)
                     (= :map (first %)))]]
   [::dispatch [:vector :any]]
   [::target [:vector :any]]])

;; Path parameter extraction

(defn extract-path-params
  "Extract parameter names from a reitit-style path.
   \"/chat/:room-id/message\" -> #{:room-id}
   \"/user/:user-id/profile/:section\" -> #{:user-id :section}"
  [path]
  (->> (re-seq #":([a-zA-Z][a-zA-Z0-9_-]*)" path)
       (map (comp keyword second))
       set))

;; Signal key extraction from Malli schemas

(defn extract-signal-keys
  "Extract all valid key paths from a Malli map schema.
   Returns a set of keywords (for flat keys) and vectors (for nested paths).

   [:map [:message :string] [:user [:map [:name :string]]]]
   -> #{:message :user [:user :name]}"
  ([schema] (extract-signal-keys schema []))
  ([schema prefix]
   (when (and (vector? schema) (= :map (first schema)))
     (let [entries (rest schema)]
       (reduce
        (fn [acc entry]
          (when (vector? entry)
            (let [[key-name schema-or-props & rest] entry
                  child-schema (if (map? schema-or-props)
                                 (first rest)
                                 schema-or-props)
                  full-path (conj prefix key-name)
                  ;; Add this key (either as keyword if at root, or as path)
                  acc (conj acc (if (empty? prefix)
                                  key-name
                                  full-path))]
              ;; Recurse into nested maps
              (if (and (vector? child-schema)
                       (= :map (first child-schema)))
                (into acc (extract-signal-keys child-schema full-path))
                acc))))
        #{}
        entries)))))

;; Token extraction from dispatch and target vectors

(defn- token?
  "Check if a value is a kaiin token."
  [v]
  (and (vector? v)
       (>= (count v) 2)
       (#{::signal ::path-param} (first v))))

(defn extract-tokens
  "Extract all tokens from a dispatch or target vector.
   Returns {:signal-tokens [...] :path-param-tokens [...]}"
  [v]
  (let [tokens (filter token? (tree-seq coll? seq v))]
    {:signal-tokens (filter #(= ::signal (first %)) tokens)
     :path-param-tokens (filter #(= ::path-param (first %)) tokens)}))

;; Validation functions

(defn- normalize-signal-path
  "Normalize a signal token path to the format used by extract-signal-keys.
   :key -> :key
   [:key] -> :key
   [:a :b] -> [:a :b]"
  [path]
  (if (vector? path)
    (if (= 1 (count path))
      (first path)
      path)
    path))

(defn- signal-path-valid?
  "Check if a signal path is valid against the set of extractable signal keys.
   For nested paths like [:user :name], also checks that parent [:user] exists."
  [signal-keys path]
  (let [normalized (normalize-signal-path path)]
    (if (vector? normalized)
      ;; For nested paths, check both the path and its prefix
      (let [prefix (vec (butlast normalized))
            prefix-key (if (= 1 (count prefix)) (first prefix) prefix)]
        (and (contains? signal-keys prefix-key)
             (contains? signal-keys normalized)))
      ;; For flat keys, just check membership
      (contains? signal-keys normalized))))

(defn validate-signal-tokens
  "Validate that all signal tokens reference keys extractable from the signals schema.
   Returns nil if valid, or a vector of error maps if invalid."
  [metadata]
  (let [signals (::signals metadata)
        signal-keys (extract-signal-keys signals)
        dispatch-tokens (extract-tokens (::dispatch metadata))
        target-tokens (extract-tokens (::target metadata))
        signal-tokens (concat (:signal-tokens dispatch-tokens)
                              (:signal-tokens target-tokens))]
    (when-let [errors (seq
                       (for [[_ path :as token] signal-tokens
                             :when (not (signal-path-valid? signal-keys path))]
                         {:token token
                          :path path
                          :available-keys signal-keys}))]
      (vec errors))))

(defn validate-path-param-tokens
  "Validate that all path-param tokens reference params in the path.
   Returns nil if valid, or a vector of error maps if invalid."
  [metadata]
  (let [path (::path metadata)
        path-params (extract-path-params path)
        dispatch-tokens (extract-tokens (::dispatch metadata))
        target-tokens (extract-tokens (::target metadata))
        path-param-tokens (concat (:path-param-tokens dispatch-tokens)
                                  (:path-param-tokens target-tokens))]
    (when-let [errors (seq
                       (for [[_ param :as token] path-param-tokens
                             :when (not (contains? path-params param))]
                         {:token token
                          :param param
                          :path path
                          :available-params path-params}))]
      (vec errors))))

(defn validate-metadata
  "Validate kaiin metadata completely.
   Returns nil if valid, or throws an exception with detailed error information."
  [metadata]
  (let [;; First check basic schema
        schema-errors (when-not (m/validate metadata-schema metadata)
                        (me/humanize (m/explain metadata-schema metadata)))
        ;; Then check token references
        signal-errors (when-not schema-errors
                        (validate-signal-tokens metadata))
        path-param-errors (when-not schema-errors
                            (validate-path-param-tokens metadata))]
    (when (or schema-errors signal-errors path-param-errors)
      (throw (ex-info "Invalid kaiin metadata"
                      {:schema-errors schema-errors
                       :signal-errors signal-errors
                       :path-param-errors path-param-errors
                       :metadata metadata})))))
