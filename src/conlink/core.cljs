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
  --work-queue-interval MS          Check work queue every MS milliseconds
                                    [default: 100]
")

(def OVS-CMD ["/usr/share/openvswitch/scripts/ovs-ctl"
              "start" "--system-id=random", "--no-mlockall"])

(def state (atom {}))
(def work-queue (array))

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(defn wait
 ([pred] (wait {} pred))
 ([opts pred]
  (P/let [{:keys [attempts sleep-ms timeout-fn]
           :or {sleep-ms 200 timeout-fn #(identity false)}} opts]
    (P/let [attempt (atom 0)]
      (P/loop []
        (P/let [res (pred)]
          (if res
            res
            (if (and attempts (> @attempt  attempts))
              (timeout-fn)
              (P/do
                (P/delay 100)
                (swap! attempt inc) ;; Fix when P/loop supports values
                (P/recur))))))))))

(defn process-network-config [net-cfg]
  (let [domains (for [link (:links net-cfg)
                      :when (:domain link)] (:domain link))]
    (merge net-cfg
           {:domains (set domains)})))

(defn container-client [path filters event-callback]
  (P/catch
    (P/let
      [client (Docker. #js {:socketPath path})
       ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
       _ ^obj (.on ev-stream "data"
                   #(event-callback client (->clj (js/JSON.parse %))))]
      (Eprintln (str "Listening on " path))
      client)
    #(Eprintln "Could not start docker client/listener on" path)))

(defn start-network-container [cfg]
  (P/let [mode (keyword (:network-mode cfg))
          docker (get cfg mode)
          opts {:Image "conlink",
                :Cmd ["sleep", "864000"],
                :Privileged true,
                :Pid "host"
                #_#_:Network "none"}
          nc ^obj (.createContainer docker (->js opts))
          _ (.start nc)]
    (wait #(P/let [res (P/-> ^obj (.inspect nc) ->clj)]
             (if (-> res :State :Running)
               res
               (Eprintln "Waitig for network container to start"))))
    (P/let [ex (.exec nc (->js {:Cmd OVS-CMD}))
            _ (.start ex)]
      (wait #(P/let [res (P/-> ^obj (.inspect ex) ->clj)
                     code (-> res :ExitCode)]
               (condp = code
                 nil (Eprintln "Waitig for OVS startup")
                 0 res
                 (fatal 1 (str "OVS startup exited with " code)))))
      nc)))

(defn exit-handler [err origin]
  (let [{:keys [network-container]} @state]
    (Eprintln (str "Got " origin ":") err)
    (when network-container
      (P/do
        (Eprintln "Killing network container")
        (.remove network-container #js {:force true})
        (js/process.exit 127)))))

(defn process-work-queue [{:keys [verbose]}]
  (let [q-count (.-length work-queue)]
    (when (> q-count 0)
      (when verbose (Eprintln (str "Queued events:" q-count)))
      (let [event (.shift work-queue)]
        (Eprintln "Event:" ((juxt :status :id) event))))))

(defn main [& args]
  (P/let
    [cfg (parse-opts usage args)
     _ (when (empty? cfg)
         (fatal 2 "either --network-file or --compose-file is required"))
     {:keys [verbose work-queue-interval]} cfg
     _ (when verbose (Eprintln "Settings:") (Epprint cfg))
     net-cfg (load-config (first (:network-file cfg)))
     cfg (merge cfg
                {:work-queue-interval (js/parseInt work-queue-interval)}
                (process-network-config net-cfg))
     event-handler (fn [client {:keys [id] :as ev}]
                     (P/let [c ^obj (.inspect ^obj (.getContainer client id))]
                       (.push work-queue (assoc ev :container c))))
     docker (container-client (:docker-socket cfg) {"event" ["start" "die"]}
                              event-handler)
     podman (container-client (:podman-socket cfg) {"event" ["start" "die"]}
                              event-handler)
     _ (if (and (not docker) (not podman))
         (fatal 1 "Failed to start either docker or podman client/listener"))
     cfg (merge cfg {:docker docker
                     :podman podman})
     net-cnt-obj (start-network-container cfg)
     net-cnt ^obj (.inspect net-cnt-obj)
     cfg (merge cfg {:network-container net-cnt-obj
                     :network-pid (-> net-cnt :State :Pid)})]
    (reset! state cfg)
    (js/process.on "SIGINT" #(exit-handler % "signal"))
    (js/process.on "uncaughtException" exit-handler)

    ;; Generate fake events for existing containers
    ;; TODO: move up into container-client
    (P/all (for [client [docker podman]]
             (P/let [containers ^obj (.listContainers client)]
               (P/all (for [container containers]
                        (event-handler client {:status "start"
                                               :from "pre-existing"
                                               :id (.-Id ^obj container)}))))))

    (js/setInterval #(process-work-queue cfg) work-queue-interval)
    (Epprint (dissoc @state :network-container))))
