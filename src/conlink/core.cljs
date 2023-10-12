#!/usr/bin/env nbb

(ns conlink.core
  (:require [clojure.string :as S]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprintln Epprint fatal
                                  trim indent deep-merge
                                  spawn read-file load-config]]
            [conlink.addrs :as addrs]
            #_["dockerode$default" :as Docker]))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

(def usage "
conlink: advanced container layer 2/3 linking/networking.

Usage:
  conlink <command> [options]

Command is one of: inner, outer, show.

General Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]

Inner Options:
  --bridge-mode BRIDGE-MODE         Bridge mode (ovs or linux) to use for
                                    broadcast domains
                                    [default: ovs]
  --network-file NETWORK-FILE...    Network configuration file
  --compose-file COMPOSE-FILE...    Docker compose file
  --docker-socket PATH              Docker socket to listen to
                                    [default: /var/run/docker.sock]
  --podman-socket PATH              Podman socket to listen to
                                    [default: /var/run/podman/podman.sock]

Outer Options:
  --network-image IMAGE             Image to use for network container
                                    [default: conlink]
  --network-mode MODE               Network container mode: docker, podman, here
                                    [default: docker]
")

(def OVS-START-CMD "/usr/share/openvswitch/scripts/ovs-ctl start --system-id=random --no-mlockall --delete-bridges")

(def ctx (atom {}))

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(def conjv (fnil conj []))

(defn load-configs [comp-cfgs net-cfgs]
  (P/let [comp-cfgs (P/all (map load-config comp-cfgs))
          xnet-cfgs (mapcat #(into [(:x-network %)]
                                   (map :x-network (-> % :services vals)))
                            comp-cfgs)
          net-cfgs (P/all (map load-config net-cfgs))
          net-cfg (reduce deep-merge {} (concat xnet-cfgs net-cfgs))]
    net-cfg))

(defn gen-network-state [net-cfg]
  (reduce (fn [c {:as l :keys [service container interface domain]}]
            (assert domain (str "No domain specified for link:"
                                (str (or container service) ":" interface)))
            (cond-> c
                true (assoc-in [:domains domain :status] nil)
                container (update-in [:containers container :links] conjv l)
                service   (update-in [:services service :links] conjv l)))
          {} (:links net-cfg)))

(defn link-add-outer-interface
  "outer-interface format:
     - standalone:  container          '-' interface
     - compose:     service '_' index  '-' interface
     - len > 15:    'c' cid[0:8]       '-' interface[0:5]"
  [{:as link :keys [container service interface]} cid index]
  (let [oif (str (if service (str service "_" index) container) "-" interface)
        oif (if (<= (count oif) 15)
              oif
              (str "c" (.substring cid 0 8)  "-" (.substring interface 0 5)))]
    (assoc link :outer-interface oif)))

(defn link-add-offset [{:as link :keys [ip mac]} offset]
  (let [mac (when mac
              (addrs/int->mac
                (+ offset (addrs/mac->int mac))))
        ip (when ip
             (let [[ip prefix] (S/split ip #"/")]
               (str (addrs/int->ip
                      (+ offset (addrs/ip->int ip)))
                    "/" prefix)))]
    (merge link (when mac {:mac mac}) (when ip {:ip ip}))))

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
                (P/delay sleep-ms)
                (swap! attempt inc) ;; Fix when P/loop supports values
                (P/recur))))))))))

(defn start-network-container
  [{:as opts :keys [log network-mode network-image]}]
  (P/let [docker (get opts network-mode)
          opts {:Image network-image,
                :Cmd ["sleep" "864000"]
                :Privileged true
                :Pid "host"
                #_#_:Network "none"}
          nc ^obj (.createContainer docker (->js opts))
          _ (.start nc)]
    (wait #(P/let [res (P/-> ^obj (.inspect nc) ->clj)]
             (if (-> res :State :Running)
               res
               (log "Waitig for network container to start"))))
    nc))

;;; Inner commands

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

(defn kmod-loaded? [opts kmod]
  (P/let [cmd (str "grep -o '^" kmod "\\>' /proc/modules")
          res (run cmd (assoc opts :error list))]
    (and (= 0 (:code res)) (= kmod (trim (:stdout res))))))

;;; Inner link and bridge commands

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
                    :linux (str "ip link add " domain " up type bridge")}
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
                  " failed to add into " domain))
      res)))

(defn domain-drop-link [{:as opts :keys [error bridge-mode]} domain interface]
  (P/let [cmd (get {:ovs (str "ovs-vsctl del-port " domain " " interface)
                    :linux (str "ip link set dev " interface " nomaster")}
                   bridge-mode)
          res (run cmd opts)]
    (if (not= 0 (:code res))
      (error (str "ERROR: link " interface
                  " failed to drop from " domain))
      res)))


(defn link-create [{:as opts :keys [error]} link inner-pid outer-pid]
  (P/let [{:keys [interface mtu mac ip route outer-interface]} link
          status-path [:network-state :links outer-interface :status]
          link-status (get-in @ctx status-path)]
    (if link-status
      (error (str "Link " outer-interface " already exists"))
      (P/let [_ (swap! ctx assoc-in status-path :created)
              cmd (str "./veth-link.sh"
                       (when mac (str " --mac0 " mac))
                       (when ip (str " --ip0 " ip))
                       (when route (str " --route0 '" route "'"))
                       " --mtu " (or mtu 9000)
                       " " interface " " outer-interface
                       " " inner-pid " " outer-pid)]
        (run cmd (assoc opts :id outer-interface))))))

(defn link-del [{:as opts :keys [error]} interface]
  (P/let [status-path [:network-state :links interface :status]
          res (run (str "ip link del " interface) opts)]
    (if (not= 0 (:code res))
      (error (str "Could not delete " interface ": " (:stderr res)))
      (do (swap! ctx assoc-in status-path nil)
          res))))


;;; docker/docker-compose utilities

(defn get-container-id
  "Returns nil if no container ID can be determined (e.g. we are not
  running in a container)"
  []
  (P/let [[cgroup mountinfo]
          , (P/all [(read-file "/proc/self/cgroup" "utf8")
                    (read-file "/proc/self/mountinfo" "utf8")])
          cgroups (map second (re-seq #"/docker/([^/\n]*)" cgroup))
          containers (map second (re-seq #"/containers/([^/\n]*)" mountinfo))]
    (first (concat cgroups containers))))

(defn get-container
  [client cid]
  (P/-> ^obj (.inspect ^obj (.getContainer client cid)) ->clj))

(defn list-containers
  [client & [filters]]
  (P/let [opts (if filters {:filters (json-str filters)} {})]
    ^obj (.listContainers client (->js opts))))

;;;

(defn docker-client [{:keys [error log]} path]
  (P/catch
    (P/let
      [client (Docker. #js {:socketPath path})
       ;; client is lazy so trigger it now
       containers (list-containers client)]
      (log (str "Listening on " path))
      client)
    #(error "Could not start docker client on '" path "': " %)))

(defn docker-listen [{:keys [error log]} client filters event-callback]
  (P/catch
    (P/let
      [ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
       _ ^obj (.on ev-stream "data"
                   #(event-callback client (->clj (js/JSON.parse %))))]
      ev-stream)
    #(error "Could not start docker listener")))


(defn handle-event [{:as opts :keys [info log]} client {:keys [status id]}]
  (P/let [{:keys [network-state self-pid]} @ctx
          container (get-container client id)
          cname (->> (:Name container) (re-seq #"(.*/)?(.*)") first last)
          sname (-> container :Config :Labels :com.docker.compose.service)
          snum (-> container :Config :Labels :com.docker.compose.container-number)
          sindex (if snum (js/parseInt snum) 1)
          Pid (-> container :State :Pid)
          clinks (get-in network-state [:containers cname :links])
          slinks (get-in network-state [:services sname :links])
          links (concat clinks slinks)]
    (if (not (seq links))
      (info (str "Event: no links defined for " cname ", ignoring"))
      (do
        (info "Event:" status cname id)
        (P/all (for [{:as link :keys [interface domain ip mac]} links
                     :let [link (-> link
                                    (link-add-outer-interface id sindex)
                                    (link-add-offset (dec sindex)))
                           oif (:outer-interface link)]]
                 (if (= status "start")
                   (P/do
                     (log (str "Creating link (in " domain ") "
                               oif " -> " cname ":" interface))
                     (link-create opts link Pid self-pid)
                     (domain-add-link opts domain oif))
                   (P/do
                     (log (str "Deleting link (in " domain ") "
                               oif " -> " cname ":" interface))
                     (domain-drop-link opts domain oif)
                     (link-del opts oif)))))))))

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

(defn inner [{:as opts :keys [log info bridge-mode network-file compose-file]}]
  (P/let
    [_ (when (and (empty? network-file) (empty? compose-file))
         (fatal 2 "either --network-file or --compose-file is required"))
     kmod-okay? (if (= :ovs bridge-mode)
                  (kmod-loaded? opts "openvswitch")
                  true)
     _ (when (not kmod-okay?)
         (fatal 2 "bridge-mode is 'ovs', but no 'openvswitch' module loaded"))
     net-cfg (load-configs compose-file network-file)
     net-state (gen-network-state net-cfg)
     docker (docker-client opts (:docker-socket opts))
     podman (docker-client opts (:podman-socket opts))
     _ (when (and (not docker) (not podman))
         (fatal 1 "Failed to start either docker or podman client/listener"))
     self-cid (get-container-id)
     self-container (when self-cid
                      (get-container docker self-cid))
     self-labels (get-in self-container [:Config :Labels])
     compose-project (get self-labels :com.docker.compose.project)
     compose-workdir (get self-labels :com.docker.compose.project.working_dir)
     label-filters (when compose-project
                     {"label"
                      [(str "com.docker.compose.project=" compose-project)
                       (str "com.docker.compose.project.working_dir=" compose-workdir)]})
     event-filters (merge
                     {"event" ["start" "die"]}
                     label-filters)]

    (swap! ctx merge {:network-config net-cfg
                      :network-state net-state
                      :docker docker
                      :podman podman
                      :self-pid js/process.pid
                      :self-cid self-cid
                      :self-container self-container})
    (js/process.on "SIGINT" #(inner-exit-handler opts % "signal"))
    (js/process.on "SIGTERM" #(inner-exit-handler opts % "signal"))
    (js/process.on "uncaughtException" #(inner-exit-handler opts %1 %2))

    (log "Bridge mode:" (get {:ovs "openvswitch" :linux "linux"} bridge-mode))
    (when (:verbose opts)
      (info "Full network configuration:")
      (Epprint net-cfg))
    (when self-cid
      (info "Detected enclosing container:" self-cid))
    (when compose-project
      (info "Detected compose context:" compose-project))

    (P/do
      (when (= :ovs bridge-mode)
        (start-ovs opts))

      ;; Check that domains/switches do not already exist
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (check-no-domain opts domain)))
      ;; Create domains/switch configs
      (P/all (for [domain (-> @ctx :network-state :domains keys)]
               (domain-create opts domain)))

      (P/all (for [client [docker podman] :when client]
               (P/let
                 [;; Listen for docker and/or podman events
                  _ (docker-listen opts client event-filters
                                   (partial handle-event opts))
                  containers ^obj (list-containers client label-filters)]
                 ;; Generate fake events for existing containers
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
    [{:as opts :keys [command verbose]} (parse-opts usage args)
     _ (when verbose (Eprintln "User options:") (Epprint opts))
     opts (merge opts
                 {:bridge-mode (keyword (:bridge-mode opts))
                  :network-mode (keyword (:network-mode opts))}
                 {:error #(apply Eprintln "ERROR" %&)
                  :warn  #(apply Eprintln "WARNING:" %&)
                  :log   Eprintln}
                 (if verbose
                   {:info Eprintln}
                   {:info list}))
     command-fn (get COMMANDS command)]

    (when (not command-fn) (fatal 2 (str "Invalid command: " command)))
    (command-fn opts)
    #_(Epprint (dissoc @ctx :network-container :docker :podman))
    nil))

