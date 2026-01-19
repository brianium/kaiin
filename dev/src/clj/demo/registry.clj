(ns demo.registry
  (:require [ascolais.sandestin :as s]
            [ascolais.kaiin :as kaiin]
            [ascolais.twk :as twk]
            [demo.views :as views]))

(def lobby-registry
  "Registry for lobby actions with kaiin metadata.
   Actions return effects that sandestin dispatches to connections."
  {::s/actions
   {:lobby/send-message
    {::s/description "Send a message to all lobby participants"
     ::s/schema [:tuple [:= :lobby/send-message] :string :string]
     ::s/handler (fn [_state username message]
                   [[::twk/patch-elements (views/message-bubble username message)
                     {twk/selector "#messages" twk/patch-mode twk/pm-append}]])

     ;; Kaiin metadata - generates POST /message route
     ::kaiin/path "/message"
     ::kaiin/method :post
     ::kaiin/signals [:map [:username :string] [:message :string]]
     ::kaiin/dispatch [:lobby/send-message
                       [::kaiin/signal :username]
                       [::kaiin/signal :message]]
     ::kaiin/target [:* [:lobby :*]]}}})
