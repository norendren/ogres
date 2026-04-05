(ns ogres.app.core
  (:require [ogres.app.component.error        :refer [error-page]]
            [ogres.app.component.layout        :refer [layout]]
            [ogres.app.const                   :refer [PATH]]
            [ogres.app.provider.cursor         :as provider.cursor]
            [ogres.app.provider.dispatch       :as provider.dispatch]
            [ogres.app.provider.events         :as provider.events]
            [ogres.app.provider.idb            :as provider.idb]
            [ogres.app.provider.image          :as provider.image]
            [ogres.app.provider.local-session  :as provider.local-session]
            [ogres.app.provider.portal         :as provider.portal]
            [ogres.app.provider.release        :as provider.release]
            [ogres.app.provider.shortcut       :as provider.shortcut]
            [ogres.app.provider.state          :as provider.state]
            [ogres.app.provider.window         :as provider.window]
            [ogres.app.provider.session        :as provider.session]
            [uix.core :as uix :refer [defui $]]
            [uix.dom :as dom]))

(defn ^:private local-player? []
  (some? (.get (js/URLSearchParams. (.. js/window -location -search)) "local-player")))

(defn ^:private host? []
  (let [params (js/URLSearchParams. (.. js/window -location -search))]
    (and (nil? (.get params "join"))
         (nil? (.get params "local-player")))))

(def ^:private error-boundary
  (uix/create-error-boundary
   {:derive-error-state identity}
   error-page))

(defui ^:private app []
  ($ uix/strict-mode
    ($ :link
      {:rel "stylesheet"
       :href "https://fonts.googleapis.com/css2?family=Lora:wght@400..700&display=swap"
       :cross-origin "true"
       :precedence "high"})
    ($ :link {:rel "stylesheet" :href (str PATH "/ogres.app.css") :precedence "default"})
    ($ provider.events/provider
      ($ provider.idb/provider
        ($ provider.state/provider {:host (host?)}
          ($ provider.dispatch/provider
            ($ provider.release/provider
              ($ provider.image/provider
                ($ provider.portal/provider
                  ($ provider.window/provider
                    ($ provider.shortcut/listeners)
                    (if (local-player?)
                      ($ provider.local-session/player-listeners)
                      ($ provider.session/listeners))
                    (when (host?)
                      ($ provider.local-session/host-listeners))
                    ($ provider.cursor/listeners)
                    ($ error-boundary
                      ($ layout))))))))))))

(defn ^:export main []
  (let [elem (.querySelector js/document "#root")
        root (dom/create-root elem)]
    (dom/render-root ($ app) root)))
