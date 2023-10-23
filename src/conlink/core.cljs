#!/usr/bin/env nbb

(ns conlink.core
  (:require [clojure.string :as S]
            [clojure.pprint :refer [pprint]]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprintln Epprint fatal
                                  trim indent interpolate-walk deep-merge
                                  spawn read-file load-config]]
            [conlink.addrs :as addrs]
            #_["dockerode$default" :as Docker]))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Docker (js/require "dockerode"))

(def usage "
conlink: advanced container layer 2/3 linking/networking.

Usage:
  conlink [options]

General Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --bridge-mode BRIDGE-MODE         Bridge mode (ovs or linux) to use for
                                    bridge/switch connections
                                    [default: ovs]
  --network-file NETWORK-FILE...    Network config file
  --compose-file COMPOSE-FILE...    Docker compose file with network config
  --compose-project NAME            Docker compose project name for resolving
                                    link :service keys (if conlink is a
                                    compose service then this defaults to
                                    the current compose project name)
  --docker-socket PATH              Docker socket to listen to
                                    [default: /var/run/docker.sock]
  --podman-socket PATH              Podman socket to listen to
                                    [default: /var/run/podman/podman.sock]
")

;; TODO: :service should require either command line option or
;; detection of running in a compose project (but not both).

(def OVS-START-CMD (str "/usr/share/openvswitch/scripts/ovs-ctl start"
                        " --system-id=random --no-mlockall --delete-bridges"))

(def VLAN-TYPES #{:vlan :macvlan :macvtap :ipvlan :ipvtap})

(def ctx (atom {:error #(apply Eprintln "ERROR:" %&)
                :warn  #(apply Eprintln "WARNING:" %&)
                :log   Eprintln
                :info  list}))

(defn json-str [obj]
  (js/JSON.stringify (->js obj)))

(def conjv (fnil conj []))

(defn indent-pprint-str [o pre]
  (indent (trim (with-out-str (pprint o))) pre))

(defn load-configs
  "Load network configs from a list of compose file paths and a list
  of network config file paths. Network configs in compose files are
  under the 'x-network' top-level key or as an 'x-network' property of
  services. The network configs are merged together into a single
  network configuration that is returned."
  [comp-cfgs net-cfgs]
  (P/let [comp-cfgs (P/all (map load-config comp-cfgs))
          xnet-cfgs (mapcat #(into [(:x-network %)]
                                   (for [[s sd] (:services %)
                                         :let [cfg (:x-network sd)]]
                                     ;; current service is default
                                     (assoc cfg :links
                                            (for [l (:links cfg)]
                                              (merge {:service (name s)} l)))))
                            comp-cfgs)
          net-cfgs (P/all (map load-config net-cfgs))
          net-cfg (reduce deep-merge {} (concat xnet-cfgs net-cfgs))]
    net-cfg))

(defn check-and-enrich-link
  [{:as link :keys [type base bridge ip vlanid]}]
  (let [type (keyword (or type "veth"))
        base-default (cond (= :veth type)     :conlink
                           (VLAN-TYPES type)  :host
                           :else              :local)
        base (get link :base base-default)
        link (merge
               link
               {:type type
                :dev  (get link :dev "eth0")
                :base base}
               (when (not (VLAN-TYPES type))
                 {:mtu  (get link :mtu 9000)}))]
    (cond
      (and (not bridge) (= :veth type) (= :conlink base))
      (fatal 2 (str "Bridge required for conlink veth link: " link))

      (and bridge (or (not= :veth type) (not= :conlink base)))
      (fatal 2 (str "Bridge only allowed for conlink veth links: " link))

      (and ip (not (re-seq #"/" ip)))
      (fatal 2 (str "IP " ip " is missing prefix (*/prefix): " link))

      (and vlanid (not= :vlan type))
      (fatal 2 (str "Non vlan link cannot have vlanid: " link))

      (and vlanid (not= :vlan type))
      (fatal 2 (str "vlanid is not supported for " type " link: " link)))
    link))

(defn check-and-enrich-network-config
  [{:as cfg :keys [links]}]
  (assoc cfg :links (vec (map check-and-enrich-link links))))


(defn gen-network-state
  "Generate network state/context from network configuration. This
  restructures link configuration into top-level keys: :bridges,
  :containers, and :services that provide a more efficient structure
  for looking up runtime status/state of those aspects of the
  configuration."
  [net-cfg]
  (let [rfn (fn [kind cfg {:as x :keys [bridge container service]}]
              (cond-> cfg
                bridge    (assoc-in  [:bridges    bridge :status] nil)
                container (update-in [:containers container kind] conjv x)
                service   (update-in [:services   service kind] conjv x)))
        state {:bridges {} :containers {} :services {}}
        state (reduce (partial rfn :links) state (:links net-cfg))
        state (reduce (partial rfn :commands) state (:commands net-cfg))]
    state))

(defn link-outer-dev
  "outer-dev format:
     - standalone:  container          '-' dev
     - compose:     service '_' index  '-' dev
     - len > 15:    'c' cid[0:8]       '-' dev[0:5]"
  [{:as link :keys [container service dev]} cid index]
  (let [oif (str (if service (str service "_" index) container) "-" dev)
        oif (if (<= (count oif) 15)
              oif
              (str "c" (.substring cid 0 8)  "-" (.substring dev 0 5)))]
    oif))

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


(defn link-enrich [link container self-pid]
  (let [{:keys [id dev pid index name]} container
        link-id (str name ":" (:dev link))
        outer-pid (condp = (:base link)
                    :conlink self-pid
                    :host 1
                    :local nil)
        link (link-add-offset link (dec index))
        link (if (and outer-pid (not (:outer-dev link)))
               (assoc link :outer-dev (link-outer-dev link id index))
               link)
        link (merge link {:container container
                          :link-id link-id
                          :pid pid
                          :outer-pid outer-pid})]
    link))

;;; General commands

(defn run [cmd & [{:as opts :keys [quiet id]}]]
  (P/let [{:keys [info warn]} @ctx
          id (if id (str " (" id ")") "")
          _ (when (not quiet) (info (str "Running" id ": " cmd)))
          res (P/catch (spawn cmd) #(identity %))]
    (P/do
      (when (not quiet)
        (if (= 0 (:code res))
          (when (not (empty? (:stdout res)))
            (info (str "Result" id ":\n"
                       (indent (:stdout res) "  "))))
          (warn (str "[code: " (:code res) "]" id ":\n"
                     (indent (:stdout res) "  ") "\n"
                     (indent (:stderr res) "  ")))))
      res)))

(defn start-ovs []
  (P/let [res (run OVS-START-CMD)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Failed starting OVS: " (:stderr res)))
      res)))

(defn kmod-loaded? [kmod]
  (P/let [cmd (str "grep -o '^" kmod "\\>' /proc/modules")
          res (run cmd {:quiet true})]
    (and (= 0 (:code res)) (= kmod (trim (:stdout res))))))

;;; Link and bridge commands

(defn check-no-bridge [bridge]
  (P/let [{:keys [info bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl list-ifaces " bridge)
                    :linux (str "ip link show type bridge " bridge)}
                   bridge-mode)
          res (run cmd {:quiet true})]
    (if (= 0 (:code res))
      ;; TODO: maybe mark as :exists and use without cleanup
      (fatal 1 (str "Bridge " bridge " already exists"))
      (if (re-seq #"(does not exist|no bridge named)" (:stderr res))
        true
        (fatal 1 (str "Unable to run '" cmd "': " (:stderr res)))))))

(defn bridge-create [bridge]
  (P/let [{:keys [info error bridge-mode]} @ctx
          _ (info "Creating bridge/switch" bridge)
          cmd (get {:ovs (str "ovs-vsctl add-br " bridge)
                    :linux (str "ip link add " bridge " up type bridge")}
                   bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (error (str "Unable to create bridge/switch " bridge))
      (swap! ctx assoc-in [:network-state :bridges bridge :status] :created))
    res))

(defn bridge-del [bridge]
  (P/let [{:keys [info error bridge-mode]} @ctx
          _ (info "Deleting bridge/switch" bridge)
          cmd (get {:ovs (str "ovs-vsctl del-br " bridge)
                    :linux (str "ip link del " bridge)} bridge-mode)
          res (run cmd)]
    (if (not= 0 (:code res))
      (error (str "Unable to delete bridge " bridge))
      (swap! ctx assoc-in [:network-state :bridges bridge :status] nil))
    res))

(defn bridge-add-link [bridge dev]
  (P/let [{:keys [error bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl add-port " bridge " " dev)
                    :linux (str "ip link set dev " dev " master " bridge)}
                   bridge-mode)
          res (run cmd)]
    (when (not= 0 (:code res))
      (error (str "Unable to add link " dev " into " bridge)))
    res))

(defn bridge-drop-link [bridge dev]
  (P/let [{:keys [error bridge-mode]} @ctx
          cmd (get {:ovs (str "ovs-vsctl del-port " bridge " " dev)
                    :linux (str "ip link set dev " dev " nomaster")}
                   bridge-mode)
          res (run cmd)]
    (when (not= 0 (:code res))
      (error (str "Unable to drop link " dev " from " bridge)))
    res))


(defn link-add [link]
  (P/let [{:keys [error]} @ctx
          {:keys [type dev outer-dev pid outer-pid container link-id]} link
          cmd (str "link-add.sh"
                   " '" (name type) "' '" pid "' '" dev "'"
                   (when outer-pid (str " --pid1 " outer-pid))
                   (when outer-dev (str " --intf1 " outer-dev))
                   (S/join ""
                           (for [o [:ip :mac :route :mtu :mode :vlanid :nat]]
                             (when-let [v (get link o)]
                               (str " --" (name o) " '" v "'")))))
          res (run cmd {:id link-id})]
    (when (not= 0 (:code res))
      (error (str "Unable to add " (name type) " " link-id)))
    res))

(defn link-del [link]
  (P/let [{:keys [warn error]} @ctx
          {:keys [dev pid link-id]} link
          cmd (str "link-del.sh " pid " " dev)
          res (run cmd {:id link-id :quiet true})]
    (when (not= 0 (:code res))
      (if (re-seq #"is no longer running" (:stderr res))
        (warn (str "Skipping delete of " link-id " (container gone)"))
        (error (str "Unable to delete " link-id ": " (:stderr res)))))
    res))


;;; docker/docker-compose utilities

(defn get-container-id
  "Returns nil if no container ID can be determined (e.g. we are not
  running in a container)"
  []
  (P/let [[cgroup mountinfo]
          , (P/all [(read-file "/proc/self/cgroup" "utf8")
                    (read-file "/proc/self/mountinfo" "utf8")])
          ;; docker
          d-cgroups (map second (re-seq #"/docker/([^/\n]*)" cgroup))
          ;; podman (root)
          p-cgroups (map second (re-seq #"libpod-([^/.\n]*)" cgroup))
          ;; general fallback
          o-mounts (map second (re-seq #"containers/([^/]{64})/.*/etc/hosts" mountinfo))]
    (first (concat d-cgroups p-cgroups o-mounts))))

(defn get-container
  [client cid]
  ^obj (.getContainer client cid))

(defn inspect-container
  [container]
  (P/-> ^obj (.inspect container) ->clj))

(defn get-compose-labels
  "Return a map of compose related container labels with the
  'com.docker.compose.' prefix stripped off and converted to
  keywords."
  [container]
  (into {}
        (for [[k v] (get-in container [:Config :Labels])
              :let [n (name k)]
              :when (S/starts-with? n "com.docker.compose.")]
          [(keyword (-> n
                        (S/replace #"^com\.docker\.compose\." "")
                        (S/replace #"\." "-")))
           v])))

(defn list-containers
  [client & [filters]]
  (P/let [opts (if filters {:filters (json-str filters)} {})]
    ^obj (.listContainers client (->js opts))))

;;;

(defn docker-client [path]
  (P/let [{:keys [error log]} @ctx]
    (P/catch
      (P/let
        [client (Docker. #js {:socketPath path})
         ;; client is lazy so trigger it now
         containers (list-containers client)]
        (log (str "Listening on " path))
        client)
      #(error "Could not start docker client on '" path "': " %))))

(defn docker-listen [client filters event-callback]
  (P/let [{:keys [error log]} @ctx]
    (P/catch
      (P/let
        [ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
         _ ^obj (.on ev-stream "data"
                     #(event-callback client (->clj (js/JSON.parse %))))]
        ev-stream)
      #(error "Could not start docker listener"))))


(defn modify-link [link action]
  (P/let
    [{:keys [error log]} @ctx
     {:keys [type dev outer-dev bridge ip mac link-id]} link
     link-status (get-in @ctx [:network-state :links link-id :status])]
    (log (str (get {"start" "Creating" "die" "Deleting"} action)
              " " (name type) " link " link-id
              (when ip (str " (IP " ip ")"))
              (when outer-dev (str " <-> " outer-dev))
              (when bridge (str " (bridge " bridge ")"))))
    (condp = action
      "start"
      (if link-status
        (error (str "Link " link-id " already exists"))
        (P/do
          (swap! ctx update-in [:network-state :links link-id]
                 merge link {:status :created})
          (link-add link)
          (when bridge (bridge-add-link bridge outer-dev))))

      "die"
      (if (not link-status)
        (error (str "Link " link-id " does not exist"))
        (P/do
          (when bridge (bridge-drop-link bridge outer-dev))
          (link-del link)
          (swap! ctx update-in [:network-state :links link-id]
                 merge link {:status nil}))))))

(defn exec-command [cname container command]
  (P/let [{:keys [error log]} @ctx
          cmd (if (string? command)
                ["sh", "-c", command]
                command)
          _ (log (str "Exec command in " cname ": " cmd))
          ex (.exec container (->js {:Cmd cmd
                                     :AttachStdout true
                                     :AttachStderr true}))
          stream (.start ex)]
    ^obj (.on stream "data"
              (fn [b] (log (str cname ": " (trim (.toString b "utf8"))))))
    ex))

(defn handle-event [client {:keys [status id]}]
  (P/let
    [{:keys [log info network-state compose-opts self-pid]} @ctx
     container-obj (get-container client id)
     container (inspect-container container-obj)
     cname (->> container :Name (re-seq #"(.*/)?(.*)") first last)
     pid (-> container :State :Pid)

     clabels (get-compose-labels container)
     svc-name (:service clabels)
     svc-num (:container-number clabels)
     cindex (if svc-num (js/parseInt svc-num) 1)
     container-info {:id id
                     :name cname
                     :index cindex
                     :service svc-name
                     :pid pid
                     :labels clabels}

     svc-match? (and (let [p (:project compose-opts)]
                       (or (not p) (= p (:project clabels))))
                     (let [d (:project-working_dir compose-opts)]
                       (or (not d) (= d (:project-working_dir clabels)))))
     containers (get-in network-state [:containers cname])
     services (when svc-match?  (get-in network-state [:services svc-name]))
     links (concat (:links containers) (:links services))
     commands (concat (:commands containers) (:commands services))]
    (if (and (not (seq links)) (not (seq commands)))
      (info (str "Event: no matching config for " cname ", ignoring"))
      (P/do
        (info "Event:" status cname id)
        (P/all (for [link links
                     :let [link (link-enrich link container-info self-pid)]]
                 (modify-link link status)))
        (when (= "start" status)
          (P/all (for [{:keys [command]} commands]
                   (exec-command cname container-obj command))))))))

(defn exit-handler [err origin]
  (let [{:keys [log info network-state]} @ctx
        {:keys [links bridges]} network-state
        ;; filter for :created status (ignore :exists)
        links (filter #(= :created (-> % val :status)) links)
        bridges (filter #(= :created (-> % val :status)) bridges)]
    (info (str "Got " origin ":") err)
    (P/do
      (when (seq links)
        (P/do
          (log (str "Removing links: " (S/join ", " (keys links))))
          (P/all (map link-del (vals links)))))
      (when (seq bridges)
        (P/do
          (log (str "Removing bridges:" (S/join ", " (keys bridges))))
          (P/all (map bridge-del (keys bridges)))))
      (js/process.exit 127))))


;;;

(defn arg-checks [{:keys [network-file compose-file]}]
  (when (and (empty? network-file) (empty? compose-file))
    (fatal 2 "either --network-file or --compose-file is required")))

(defn startup-checks [bridge-mode docker podman]
  (P/let
    [kmod-okay? (if (= :ovs bridge-mode)
                  (kmod-loaded? "openvswitch")
                  true)]
    (when (not kmod-okay?)
      (fatal 2 "bridge-mode is 'ovs', but no 'openvswitch' module loaded"))
    (when (and (not docker) (not podman))
      (fatal 1 "Failed to start either docker or podman client/listener"))))

(defn main [& args]
  (P/let
    [{:as opts :keys [verbose]} (parse-opts usage args)
     {:keys [log info]} (swap! ctx merge (when verbose {:info Eprintln}))
     opts (merge
            opts
            {:bridge-mode (keyword (:bridge-mode opts))
             :network-file (mapcat #(S/split % #":") (:network-file opts))
             :compose-file (mapcat #(S/split % #":") (:compose-file opts))})
     _ (arg-checks opts)
     _ (info (str "User options:\n" (indent-pprint-str opts "  ")))

     {:keys [network-file compose-file compose-project bridge-mode]} opts
     env (js->clj (js/Object.assign #js {} js/process.env))
     self-pid js/process.pid
     net-cfg (P/-> (load-configs compose-file network-file)
                   (interpolate-walk env)
                   (check-and-enrich-network-config))

     network-state (gen-network-state net-cfg)
     docker (docker-client (:docker-socket opts))
     podman (docker-client (:podman-socket opts))
     _ (startup-checks bridge-mode docker podman)
     self-cid (get-container-id)
     self-container-obj (when self-cid
                          (get-container (or docker podman) self-cid))
     self-container (inspect-container self-container-obj)
     compose-opts (if compose-project
                    {:project compose-project}
                    (get-compose-labels self-container))
     ctx-data {:bridge-mode bridge-mode
               :network-state network-state
               :compose-opts compose-opts
               :docker docker
               :podman podman
               :self-pid self-pid
               :self-cid self-cid}]


    (swap! ctx merge ctx-data)

    (js/process.on "SIGINT" #(exit-handler % "signal"))
    (js/process.on "SIGTERM" #(exit-handler % "signal"))
    (js/process.on "uncaughtException" #(exit-handler %1 %2))

    (log "Bridge mode:" (name bridge-mode))
    (info (str "Starting network state:\n"
               (indent-pprint-str network-state "  ")))
    (when self-cid
      (info "Detected enclosing container:" self-cid))
    (when compose-project
      (info "Detected compose context:" compose-project))

    (P/do
      (when (= :ovs bridge-mode)
        (start-ovs))

      ;; Check that bridges/switches do not already exist
      (P/all (for [bridge (-> @ctx :network-state :bridges keys)]
               (check-no-bridge bridge)))
      ;; Create bridges/switch configs
      (P/all (for [bridge (-> @ctx :network-state :bridges keys)]
               (bridge-create bridge)))

      (P/all (for [client [docker podman] :when client]
               (P/let
                 [event-filter {"event" ["start" "die"]}
                  ;; Listen for docker and/or podman events
                  _ (docker-listen client event-filter handle-event)
                  containers ^obj (list-containers client)]
                 ;; Generate fake events for existing containers
                 (P/all (for [container containers
                              :let [ev {:status "start"
                                        :from "pre-existing"
                                        :id (.-Id ^obj container)}]]
                          (handle-event client ev))))))
      #_(Epprint (dissoc @ctx :error :warn :log :info
                       :network-container :docker :podman :self-container))
      nil)))
