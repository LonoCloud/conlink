#!/usr/bin/env nbb

(ns conlink.core
  (:require [clojure.string :as S]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprn Eprintln Epprint
                                  fatal spawn exec load-config]]
            ["dockerode$default" :as Docker]))

(def usage "
conlink: advanced container layer 2/3 linking/networking.

Usage:
  conlink [options]

Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --project PROJECT                 Docker compose project name
                                    [env: COMPOSE_PROJECT_NAME]
  --network-file NETWORK-FILE...    Network configuration file
  --compose-file COMPOSE-FILE...    Docker compose file
  --network-image IMAGE             Image to use for network container
                                    [default: conlink]
  --network-mode MODE               Network container mode: docker, podman, here
                                    [default: docker]
  --docker-socket PATH              Docker socket to listen to
                                    [default: /var/run/docker.sock]
  --podman-socket PATH              Podman socket to listen to
                                    [default: /var/run/podman/podman.sock]
")

(def state (atom {}))

(def OVS-CMD ["/usr/share/openvswitch/scripts/ovs-ctl"
              "start" "--system-id=random", "--no-mlockall"])

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(defn process-network-config [net-cfg]
  (let [domains (for [link (:links net-cfg)
                      :when (:domain link)] (:domain link))]
    (merge net-cfg
           {:domains (set domains)})))

(defn container-client [path filters callback]
  (P/let
    [docker (Docker. #js {:socketPath path})
     ev-stream ^obj (.getEvents docker #js {:filters (json-str filters)})
     _ ^obj (.on ev-stream "data" #(callback (->clj (js/JSON.parse %))))]
    (Eprintln (str "Listening on " path))
    docker))

(defn start-network-container [cfg]
  (P/let [mode (keyword (:network-mode cfg))
          docker (get cfg mode)
          _ (Eprn :docker docker)
          opts {:Image "conlink",
                :Cmd ["sleep", "864000"],
                :Privileged true,
                :Pid "host"
                #_#_:Network "none"}
          nc ^obj (.createContainer docker (->js opts))
          _ (.start nc)
          ex (.exec nc (->js {:Cmd OVS-CMD}))
          _ (.start ex)
          cnt (atom 0)]
    (P/loop []
      (P/let [res ^obj (.inspect ex)
              res (->clj res)]
        (if (:ExitCode res)
          (if (= 0 (:ExitCode res))
            nc
            (fatal 1 "Network container ovs processes failed to start"))
          (P/do (P/delay 100)
                (when (> @cnt 50)
                  (fatal 1 "Timeout waiting for network container ovs processes"))
                ;; Fix this when P/loop support values
                (swap! cnt inc)
                (P/recur)))))))

(defn handle-container-event [cfg ev]
  (prn :event ev))

(defn main [& args]
  (P/let
    [cfg (parse-opts usage args)
     _ (when (empty? cfg)
         (fatal 2 "either --network-file or --compose-file is required"))
     {:keys [verbose]} cfg
     _ (when verbose (Eprintln "Settings:") (Epprint cfg))
     net-cfg (load-config (first (:network-file cfg)))
     cfg (merge cfg
                (process-network-config net-cfg))
     docker (container-client
              (:docker-socket cfg) {"event" ["start" "die"]}
              #(handle-container-event cfg %))
     podman (container-client
              (:podman-socket cfg) {"event" ["start" "die"]}
              #(handle-container-event cfg %))
     cfg (merge cfg {:docker docker
                     :podman podman})
     net-cnt (start-network-container cfg)
     cfg (merge cfg {:network-container net-cnt})]
    (reset! state cfg)
    (Epprint @state)
     ))
