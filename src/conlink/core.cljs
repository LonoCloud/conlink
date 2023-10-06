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
  conlink <command> [options]

Command is one of: inner, outer, show.

Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --bridge-mode BRIDGE-MODE         Bridge mode (ovs or linux) to use for
                                    broadcast domains
                                    [default: ovs]
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

(def ctx (atom {}))
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

(defn network-state [net-cfg]
  (reduce (fn [c {:as l :keys [container domain interface]}]
            (prn :l l)
            (-> c
                (assoc-in [:containers container :status] nil)
                (update-in [:containers container :links] (fnil conj []) l)
                (assoc-in [:domains domain :status] nil)
                (update-in [:domains domain :links] (fnil conj []) l)))
          net-cfg (:links net-cfg)))

(defn check-no-domain [opts domain]
  (P/let [check-cmd (str "brctl show " domain)
          res (P/catch (spawn check-cmd) #(identity %))]
    (if (= 1 (:code res))
      true
      (if (= 0 (:code res))
        (fatal 1 (str "Domain " domain " already exists"))
        (fatal 1 (str "Unable to run '" check-cmd "': " (:stderr res)))))))

(defn create-domain [{:as opts :keys [verbose]} domain]
  (P/let [create-cmd (str "brctl addbr " domain)
          _ (when verbose (Eprintln "Creating domain/switch" domain))
          res (P/catch (spawn create-cmd) #(identity %))]
    (if (not= 0 (:code res))
      (fatal 1 (str "Unable to run '" create-cmd "': " (:stderr res)))
      (swap! ctx assoc-in [:network-state :domains domain :status] :created))))

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

(defn start-network-container [opts]
  (P/let [mode (keyword (:network-mode opts))
          docker (get opts mode)
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

(defn handle-event [{:as opts :keys [verbose]} event]
  (let [{:keys [status id container]} event
        Name (.-Name ^obj container)
        cname (->> Name (re-seq #"(.*/)?(.*)") first last)
        link (get-in opts [:container cname])
        domain (get-in opts [:domain (:domain link)])]
    (if link
      (when verbose (Eprintln "Event:" status cname id link domain))
      (Eprintln (str "Event: no links defined for " cname ", ignoring")))

    #_(prn :handling-event event)))

(defn process-work-queue [{:as opts :keys [verbose]}]
  (let [q-count (.-length work-queue)]
    (when (> q-count 0)
      (when verbose (Eprintln (str "Queued events:" q-count)))
      (let [event (.shift work-queue)]
        (handle-event opts event)))))

(defn inner-exit-handler [err origin]
  (let [{:keys [network-container network-state]} @ctx
        _ (prn :exit :domains (:domains network-state))
        domains (keys (filter #(-> % val :status)
                              (:domains network-state)))]
    (prn :exit :domains domains)
    (Eprintln (str "Got " origin ":") err)
    (P/do
      (when (seq domains)
        (P/all (for [domain domains]
                 (P/do
                   (Eprintln (str "Removing domain/switch " domain))
                   (spawn (str "brctl delbr " domain))))))
      (js/process.exit 127))))

(defn inner [{:as opts :keys [verbose bridge-mode work-queue-interval]}]
  (P/let
    [net-cfg (load-config (first (:network-file opts)))
     net-state (network-state net-cfg)
     event-handler (fn [client {:keys [id] :as ev}]
                     (P/let [c ^obj (.inspect ^obj (.getContainer client id))]
                       (.push work-queue (assoc ev :container c))))
     docker (container-client (:docker-socket opts) {"event" ["start" "die"]}
                              event-handler)
     podman (container-client (:podman-socket opts) {"event" ["start" "die"]}
                              event-handler)]
    (when (and (not docker) (not podman))
      (fatal 1 "Failed to start either docker or podman client/listener"))
    (swap! ctx assoc
           :docker docker
           :podman podman
           :network-config net-cfg
           :network-state net-state)
    (js/process.on "SIGINT" #(inner-exit-handler % "signal"))
    (js/process.on "uncaughtException" inner-exit-handler)

    (when (= "ovs" bridge-mode)
      true #_(P/let [res (spawn )]))

    (P/do
      ;; Check that domains/switches do not already exist
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (check-no-domain opts domain)))
      ;; Create domains/switch configs
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (create-domain opts domain)))
      ;; Generate fake events for existing containers
      (P/all (for [client [docker podman]]
               (P/let [containers ^obj (.listContainers client)]
                 (P/all (for [container containers]
                          (event-handler client {:status "start"
                                                 :from "pre-existing"
                                                 :id (.-Id ^obj container)}))))))

      (js/setInterval #(process-work-queue opts) work-queue-interval)
      (Epprint (dissoc @ctx :network-container)))))

(defn outer-exit-handler [err origin]
  (let [{:keys [network-container network-state]} @ctx
        domains (keys (filter #(:status %)
                              (:domains network-state)))]
    (Eprintln (str "Got " origin ":") err)
    (P/do
      (when network-container
        (P/do
          (Eprintln "Killing network container")
          (.remove network-container #js {:force true})))
      (js/process.exit 127))))

(defn outer [opts]
  (P/let [net-cnt-obj (start-network-container opts)
          net-cnt ^obj (.inspect net-cnt-obj)
          opts (merge opts {:network-container net-cnt-obj
                            :network-pid (-> net-cnt :State :Pid)})]
    opts))

(defn show [opts]
  (prn :show-command))

(def COMMANDS {"inner" inner
               "outer" outer
               "show"  show})

(defn main [& args]
  (P/let
    [{:as opts :keys [command verbose]} (parse-opts usage args)
     _ (when (empty? opts)
         (fatal 2 "either --network-file or --compose-file is required"))
     command-fn (get COMMANDS command)
     opts (update opts :work-queue-interval js/parseInt)]

    (when (not command-fn) (fatal 2 (str "Invalid command: " command)))
    (when verbose (Eprintln "User options:") (Epprint opts))
    (command-fn opts)))

