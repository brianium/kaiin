(ns demo.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.twk :as twk]
            [demo.views :as views]))

(def lobby-registry
  "Registry for lobby effects.
   Note: /message route is a custom handler, not kaiin-generated,
   because kaiin's current implementation broadcasts raw dispatch vectors
   rather than the resulting twk effects."
  {::s/effects
   {:lobby/send-message
    {::s/description "Send a message to all lobby participants"
     ::s/schema [:tuple [:= :lobby/send-message] :string :string]
     ::s/handler (fn [_ctx _sys username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])}}})
