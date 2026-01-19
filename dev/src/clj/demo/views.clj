(ns demo.views
  (:require [ascolais.twk :as twk]
            [dev.onionpancakes.chassis.core :as c]))

(def styles
  "body { font-family: system-ui, sans-serif; max-width: 800px; margin: 0 auto; padding: 2rem; }
   #lobby { margin-top: 1rem; }
   #participants { margin-bottom: 1rem; }
   #participant-list { list-style: none; padding: 0; display: flex; gap: 0.5rem; flex-wrap: wrap; }
   #participant-list li { background: #e0e0e0; padding: 0.25rem 0.5rem; border-radius: 4px; }
   #messages { border: 1px solid #ccc; padding: 1rem; height: 300px; overflow-y: auto; margin-bottom: 1rem; }
   .message { margin-bottom: 0.5rem; }
   .system-message { color: #666; font-style: italic; }
   input { padding: 0.5rem; margin-right: 0.5rem; }
   button { padding: 0.5rem 1rem; cursor: pointer; }")

(defn lobby-page []
  [c/doctype-html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "UTF-8"}]
     [:title "Kaiin Demo - Lobby"]
     [:script {:src twk/CDN-url :type "module"}]
     [:style styles]]
    [:body {:data-signals:username ""
            :data-signals:message ""}
     [:h1 "Kaiin Demo - Lobby"]
     [:div#join-form
      [:p "Enter your name to join the lobby:"]
      [:input {:data-bind:username true :placeholder "Your name"}]
      [:button {:data-on:click "@post('/join')"} "Join Lobby"]]]]])

(defn lobby-ui [username]
  [:div#join-form {:data-init "@post('/sse')"}
   [:div#participants
    [:h3 "In Lobby:"]
    [:ul#participant-list
     [:li {:id (str "participant-" username)} username]]]
   [:div#messages
    [:div.message.system-message (str "Welcome to the lobby, " username "!")]]
   [:div
    [:input {:data-bind:message true :placeholder "Type a message"}]
    [:button {:data-on:click "@post('/message')"} "Send"]]
   [:button {:data-on:click "@post('/leave')" :style "margin-top: 1rem;"}
    "Leave Lobby"]])

(defn participant-item [username]
  [:li {:id (str "participant-" username)} username])

(defn message-bubble [username message]
  [:div.message [:strong username] ": " message])

(defn participant-left [username]
  [:div.message.system-message (str username " left the lobby")])
