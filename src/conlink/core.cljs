#!/usr/bin/env nbb

(ns conlink.core
  (:require [clojure.string :as S]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprn Eprintln Epprint
                                  fatal spawn exec load-config]]
            #_["dockerode$default" :as Docker]))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

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
")

(def OVS-START-CMD "/usr/share/openvswitch/scripts/ovs-ctl start --system-id=random --no-mlockall")

(def ctx (atom {}))

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(defn indent [s pre]
  (-> s
      (S/replace #"[\n]*$" "")
      (S/replace #"(^|[\n])" (str "$1" pre))))

(defn load-network-config [path]
  (P/let [net-cfg (load-config path)]
    (let [links (for [{:as link :keys [container interface]} (:links net-cfg)]
                  (merge link {:outer-interface (str container "-" interface)}))]
      (assoc net-cfg :links links))))

(defn gen-network-state [net-cfg]
  (reduce (fn [c {:as l :keys [outer-interface container domain]}]
            (-> c
                (update-in [:containers container :links] (fnil conj []) l)
                (assoc-in [:links outer-interface] (assoc l :status nil))
                (assoc-in [:containers container :status] nil)
                (assoc-in [:domains domain :status] nil)))
          {} (:links net-cfg)))


;;; Outer commands

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

(defn start-network-container [{:as opts :keys [log]}]
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
               (log "Waitig for network container to start"))))
    nc))

;;; Link and bridge commands

(defn run [cmd {:keys [id info error]}]
  (P/let [id (if id (str " (" id ")") "")
          _ (info (str "Running" id ": " cmd))
          res (P/catch (spawn cmd) #(identity %))]
    (P/do 
      (if (= 0 (:code res))
        (when (not (empty? (:stdout res)))
          (info (str "Result" id ":\n"
                     (indent (:stdout res) "  "))))
        (error (str "[code: " (:code res) "]" id ":\n"
                  (indent (:stderr res) "  "))))
      res)))

(defn start-ovs [opts]
  (P/let [res (run OVS-START-CMD opts)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Failed starting OVS: " (:stderr res)))
      res)))

(defn check-no-domain [{:as opts :keys [bridge-mode]} domain]
  (P/let [cmd (get {:ovs (str "ovs-vsctl list-ifaces " domain)
                    :linux (str "ip link show type bridge " domain)}
                   bridge-mode)
          res (run cmd (assoc opts :error list))]
    (if (= 0 (:code res))
      ;; TODO: maybe mark as :exists and use without cleanup
      (fatal 1 (str "Domain " domain " already exists"))
      (if (re-seq #"(does not exist|no bridge named)" (:stderr res))
        true
        (fatal 1 (str "Unable to run '" cmd "': " (:stderr res)))))))

(defn domain-create [{:as opts :keys [info bridge-mode]} domain]
  (P/let [_ (info "Creating domain/switch" domain)
          cmd (get {:ovs (str "ovs-vsctl add-br " domain)
                    :linux (str "ip link add " domain " type bridge")}
                   bridge-mode)
          res (run cmd opts)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Unable to create bridge/domain " domain))
      (swap! ctx assoc-in [:network-state :domains domain :status] :created))))

(defn domain-del [{:as opts :keys [error info bridge-mode]} domain]
  (P/let [_ (info "Deleting domain/switch" domain)
          cmd (get {:ovs (str "ovs-vsctl del-br " domain)
                    :linux (str "ip link del " domain)} bridge-mode)
          res (run cmd opts)]
    (if (not= 0 (:code res))
      (error (str "Unable to delete bridge " domain))
      (swap! ctx assoc-in [:network-state :domains domain :status] nil))))

(defn domain-add-link [{:as opts :keys [error bridge-mode]} domain interface]
  (P/let [cmd (get {:ovs (str "ovs-vsctl add-port " domain " " interface)
                    :linux (str "ip link set dev " interface " master " domain)}
                   bridge-mode)
          res (run cmd opts)]
    (if (not= 0 (:code res))
      (error (str "ERROR: link " interface
                  " failed to adopt into " domain))
      res)))


(defn link-create [{:as opts :keys [error]} link inner-pid outer-pid]
  (P/let [{:keys [interface outer-interface mtu mac ip]} link
          status-path [:network-state :links outer-interface :status]
          link-status (get-in @ctx status-path)
          cmd (str "./veth-link.sh"
                   (when mac (str " --mac0 " mac))
                   (when ip (str " --ip0 " ip))
                   " --mtu " (or mtu 9000)
                   " " interface " " outer-interface
                   " " inner-pid " " outer-pid)]
    (if link-status
      (error (str "Link " outer-interface " already exists"))
      (P/do (swap! ctx assoc-in status-path :created)
            (run cmd (assoc opts :id outer-interface))))))

(defn link-del [{:as opts :keys [error]} interface]
  (P/let [status-path [:network-state :links interface :status]
          res (run (str "ip link del " interface) opts)]
    (if (not= 0 (:code res))
      (error (str "Could not delete " interface ": " (:stderr res)))
      (do (swap! ctx assoc-in status-path nil)
          res))))


;;;

(defn docker-client [{:keys [error log]} path filters event-callback]
  (P/catch
    (P/let
      [client (Docker. #js {:socketPath path})
       ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
       _ ^obj (.on ev-stream "data"
                   #(event-callback client (->clj (js/JSON.parse %))))]
      (log (str "Listening on " path))
      client)
    #(error "Could not start docker client/listener on" path)))

(defn handle-event [{:as opts :keys [info log]} client event]
  (P/let [{:keys [status id]} event
          container ^obj (.inspect ^obj (.getContainer client id))
          Name (.-Name ^obj container)
          Pid (.-Pid ^obj (.-State ^obj container))
          cname (->> Name (re-seq #"(.*/)?(.*)") first last)
          {:keys [network-state my-pid]} @ctx
          links (get-in network-state [:containers cname :links])]
    (if (not (seq links))
      (info (str "Event: no links defined for " cname ", ignoring"))
      (do
        (info "Event:" status cname id)
        (P/all (for [{:as link :keys [interface outer-interface domain]} links]
                 (if (= status "start")
                   (P/do
                     (log (str "Creating link (in " domain ") "
                               outer-interface " -> " cname ":" interface))
                     (link-create opts link Pid my-pid)
                     (domain-add-link opts domain outer-interface))
                   (link-del opts outer-interface))))))))

(defn inner-exit-handler [{:as opts :keys [log info]} err origin]
  (let [{:keys [network-state]} @ctx
        {:keys [links domains containers]} network-state
        ;; filter for :created status (ignore :exists)
        intf-names (keys (filter #(= :created (-> % val :status)) links))
        domain-names (keys (filter #(= :created (-> % val :status)) domains))]
    (info (str "Got " origin ":") err)
    (P/do
      (when (seq intf-names)
        (P/do
          (log (str "Removing links: " (S/join ", " intf-names)))
          (P/all (for [interface intf-names]
                   (link-del opts interface)))))
      (when (seq domain-names)
        (P/do
          (log (str "Removing domains/switches: " (S/join ", " domain-names)))
          (P/all (for [domain domain-names]
                   (domain-del opts domain)))))
      (js/process.exit 127))))

(defn inner [{:as opts :keys [bridge-mode]}]
  (P/let
    [net-cfg (load-network-config (first (:network-file opts)))
     net-state (gen-network-state net-cfg)
     docker (docker-client opts (:docker-socket opts) {"event" ["start" "die"]}
                           (partial handle-event opts))
     podman (docker-client opts (:podman-socket opts) {"event" ["start" "die"]}
                           (partial handle-event opts))]
    (when (and (not docker) (not podman))
      (fatal 1 "Failed to start either docker or podman client/listener"))
    (swap! ctx assoc
           :my-pid js/process.pid
           :docker docker
           :podman podman
           :network-config net-cfg
           :network-state net-state)
    (js/process.on "SIGINT" #(inner-exit-handler opts % "signal"))
    (js/process.on "uncaughtException" #(inner-exit-handler opts %1 %2))

    (P/do
      (when (= :ovs bridge-mode)
        (start-ovs opts))

      ;; Check that domains/switches do not already exist
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (check-no-domain opts domain)))
      ;; Create domains/switch configs
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (domain-create opts domain)))
      ;; Generate fake events for existing containers
      (P/all (for [client [docker podman] :when client]
               (P/let [containers ^obj (.listContainers client)]
                 (P/all (for [container containers
                              :let [ev {:status "start"
                                        :from "pre-existing"
                                        :id (.-Id ^obj container)}]]
                          (handle-event opts client ev)))))))))

;;;

(defn outer-exit-handler [{:as opts :keys [log info]} err origin]
  (let [{:keys [network-container network-state]} @ctx
        domains (keys (filter #(:status %)
                              (:domains network-state)))]
    (info (str "Got " origin ":") err)
    (P/do
      (when network-container
        (P/do
          (log "Killing network container")
          (.remove network-container #js {:force true})))
      (js/process.exit 127))))

(defn outer [opts]
  (P/let [net-cnt-obj (start-network-container opts)
          net-cnt ^obj (.inspect net-cnt-obj)
          opts (merge opts {:network-container net-cnt-obj
                            :network-pid (-> net-cnt :State :Pid)})]
    true))

(defn show [opts]
  (prn :show-command)
  true)

(def COMMANDS {"inner" inner
               "outer" outer
               "show"  show})

(defn main [& args]
  (P/let
    [{:as opts :keys [command verbose bridge-mode]} (parse-opts usage args)
     _ (when (empty? opts)
         (fatal 2 "either --network-file or --compose-file is required"))
     opts (merge opts
                 {:bridge-mode (keyword bridge-mode)}
                 {:error #(apply Eprintln "ERROR" %&)
                  :warn  #(apply Eprintln "WARNING:" %&)
                  :log   Eprintln}
                 (if verbose
                   {:info Eprintln}
                   {:info list}))
     command-fn (get COMMANDS command)]

    (when (not command-fn) (fatal 2 (str "Invalid command: " command)))
    (when verbose (Eprintln "User options:") (Epprint opts))
    (command-fn opts)
    #_(Epprint (dissoc @ctx :network-container :docker :podman))
    nil))

