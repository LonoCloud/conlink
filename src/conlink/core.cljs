;; Copyright (c) 2023, Viasat, Inc
;; Licensed under MPL 2.0

(ns conlink.core
  (:require [clojure.string :as S]
            [clojure.pprint :refer [pprint]]
            [promesa.core :as P]
            [cljs-bean.core :refer [->clj ->js]]
            [conlink.util :refer [parse-opts Eprintln fatal
                                  trim indent interpolate-walk deep-merge
                                  spawn read-file load-config resolve-path]]
            [conlink.addrs :as addrs]
            #_["ajv$default" :as Ajv]
            #_["dockerode$default" :as Docker]))

;; TODO: use require syntax when shadow-cljs works with "*$default"
(def Ajv (js/require "ajv"))
(def Docker (js/require "dockerode"))

(def usage "
conlink: advanced container layer 2/3 linking/networking.

Usage:
  conlink [options]

General Options:
  -v, --verbose                     Show verbose output (stderr)
                                    [env: VERBOSE]
  --show-config                     Print loaded network config JSON and exit
  --default-bridge-mode BRIDGE-MODE Default bridge mode (ovs, linux, patch, or auto)
                                    to use for bridge/switch connections
                                    [default: auto] [env: CONLINK_BRIDGE_MODE]
  --default-mtu MTU                 Default link MTU (for non *vlan types)
                                    [default: 65535]
  --network-file NETWORK-FILE...    Network config file
  --compose-file COMPOSE-FILE...    Docker compose file with network config
  --compose-project NAME            Docker compose project name for resolving
                                    link :service keys (if conlink is a
                                    compose service then this defaults to
                                    the current compose project name)
  --config-schema SCHEMA-FILE       JSON schema file for validating network config
                                    [default: schema.yaml]
  --docker-socket PATH              Docker socket to listen to
                                    [default: /var/run/docker.sock]
  --podman-socket PATH              Podman socket to listen to
                                    [default: /var/run/podman/podman.sock]
")

;; TODO: :service should require either command line option or
;; detection of running in a compose project (but not both).

;; TODO: shadow-cljs doesn't support *file*
;;(path/dirname *file*))
(def SCHEMA-PATHS [(js/process.cwd) "/app/build/" "/app/" "../"])

(def OVS-START-CMD (str "/usr/share/openvswitch/scripts/ovs-ctl start"
                        " --system-id=random --no-mlockall --delete-bridges"))

(def VLAN-TYPES #{:vlan :macvlan :macvtap :ipvlan :ipvtap})
(def LINK-ADD-OPTS [:ip :mac :route :mtu :nat :netem :mode :vlanid :remote :vni])
(def INTF-MAX-LEN 15)

(def ctx (atom {:error        #(apply Eprintln "ERROR:" %&)
                :warn         #(apply Eprintln "WARNING:" %&)
                :log          Eprintln
                :info         #(identity nil)
                :kmod-ovs?    false
                :kmod-mirred? false}))

;; Simple utility functions
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

(defn enrich-link
  "Resolve bridge name to full bridge map.
  Add default values to a link:
    - type: veth
    - dev: eth0
    - mtu: --default-mtu (for non *vlan type)
    - base: :conlink for veth type, :host for *vlan types, :local otherwise"
  [{:as link :keys [type base bridge ip vlanid]} bridges]
  (let [{:keys [default-mtu]} @ctx
        type (keyword (or type "veth"))
        base-default (cond (= :veth type)     :conlink
                           (VLAN-TYPES type)  :host
                           :else              :local)
        base (get link :base base-default)
        link (merge
               link
               {:type type
                :dev  (get link :dev "eth0")
                :base base}
               (when bridge
                 {:bridge (get bridges bridge)})
               (when (not (VLAN-TYPES type))
                 {:mtu  (get link :mtu default-mtu)}))]
    link))

(defn enrich-bridge
  "If bridge mode is :auto then return :ovs if the 'openvswitch' kernel module
  is loaded otherwise fall back to :linux. Exit with an error if mode is :ovs
  or :patch and the 'openvswitch' or 'act_mirred' kernel modules are not
  loaded respectively."
  [{:as bridge-opts :keys [bridge mode]}]
  (let [{:keys [warn default-bridge-mode kmod-ovs? kmod-mirred?]} @ctx
        mode (keyword (or mode default-bridge-mode))
        _ (when (and (= :ovs mode) (not kmod-ovs?))
            (fatal 1 (str "bridge " bridge " mode is 'ovs', "
                          "but no 'openvswitch' kernel module loaded")))
        _ (when (and (= :patch mode) (not kmod-mirred?))
            (warn (str "bridge " bridge " mode is 'patch', "
                       "but no 'act_mirred' kernel module loaded, "
                       " assuming it will load when needed.")))
        _ (when (and (= :auto mode) (not kmod-ovs?))
            (warn (str "bridge " bridge " mode is 'auto', "
                       " but no 'openvswitch' kernel module loaded, "
                       " so falling back to 'linux'")))
        mode (if (= :auto mode)
              (if kmod-ovs? :ovs :linux)
              mode)]
    (assoc bridge-opts :mode mode)))

(defn enrich-network-config
  "Validate and update each bridge (enrich-bridge) and link (enrich-link) and
  add :bridges, :containers, and :services maps with restructured bridge, link,
  and command configuration to provide a more efficient structure for looking
  up configuration later."
  [{:as cfg :keys [links commands bridges]}]
  (let [bridge-map (reduce (fn [acc b] (assoc acc (:bridge b) b))
                           {} bridges)
        ;; Add bridges specified in links only
        all-bridges (reduce (fn [bs b]
                              (assoc bs b (get bs b {:bridge b})))
                            bridge-map
                            (keep :bridge links))
        ;; Enrich each bridge
        bridges (reduce (fn [bs [k v]] (assoc bs k (enrich-bridge v)))
                        {} all-bridges)
        links (mapv #(enrich-link % bridges) links)
        cfg (merge cfg {:links links
                        :bridges bridges
                        :containers {}
                        :services {}})
        rfn (fn [kind cfg {:as x :keys [container service]}]
              (cond-> cfg
                container (update-in [:containers container kind] conjv x)
                service   (update-in [:services   service kind] conjv x)))
        cfg (reduce (partial rfn :links) cfg links)
        cfg (reduce (partial rfn :commands) cfg commands)]
    cfg))

(defn ajv-error-to-str [error]
  (let [path (:instancePath error)
        params (dissoc (:params error) :type :pattern :missingProperty)]
    (str "  " (if (not (empty? path)) path "/")
         " " (:message error)
         (if (not (empty? params)) (str " " params) ""))))

(defn check-schema [data schema verbose]
  (let [{:keys [info warn]} @ctx
        ajv (Ajv. #js {:allErrors true})
        validator (.compile ajv (->js schema))
        valid (validator (->js data))]
    (if valid
      data
      (let [errors (-> validator .-errors ->clj)
            msg (if verbose
                  (indent-pprint-str errors "  ")
                  (S/join "\n" (map ajv-error-to-str errors)))]
        (fatal 1 (str "\nError during schema validation:\n"
                      (when verbose
                        "\nUser config:\n" (indent-pprint-str data "  "))
                      "\nValidation errors:\n" msg))))))


;;; Runtime state related

(defn gen-network-state
  "Generate network state/context from network configuration. Adds
  empty :devices map and :bridges map containing nil status for
  each bridge mentioned in the network config :links and :tunnels."
  [{:keys [links tunnels bridges]}]
  {:devices {}
   :bridges (into {} (for [[k v] bridges]
                       [k (merge v {:status nil :links #{}})]))})

(defn link-outer-dev
  "outer-dev format:
     - standalone:  container          '-' dev
     - compose:     service '_' index  '-' dev
     - len > 15:    'c' cid[0:8]       '-' dev[0:5]"
  [{:as link :keys [container service dev]} cid index]
  (let [oif (str (if service (str service "_" index) container) "-" dev)
        oif (if (<= (count oif) INTF-MAX-LEN)
              oif
              (str "c" (.substring cid 0 8)  "-" (.substring dev 0 5)))]
    oif))

(defn link-add-offset
  "Add offset value to ip and mac keys in a link definition to account
  for multiple instances of that link i.e. a compose service with
  multiple replicas (scale >= 2)."
  [{:as link :keys [ip mac]} offset]
  ;; TODO: add vlanid
  (let [mac (when mac
              (addrs/int->mac
                (+ offset (addrs/mac->int mac))))
        ip (when ip
             (let [[ip prefix] (S/split ip #"/")]
               (str (addrs/int->ip
                      (+ offset (addrs/ip->int ip)))
                    "/" prefix)))]
    (merge link (when mac {:mac mac}) (when ip {:ip ip}))))


(defn link-instance-enrich
  "Add/update properties of a specific runtime link instance using the
  container properties from an event and the current pid of the
  network container. Updates iterable properties of the link
  (via link-add-offset) and adds the following keys:
  - :container - the container properties (passed in)
  - :outer-pid - PID of the network namespace (passed in)
  - :pid - PID of this container
  - :dev-id - container name + container interface name
  - :outer-dev - outer interface name for veth and *vlan link types"
  [link container self-pid]
  (let [{:keys [id pid index name]} container
        dev-id (str name ":" (:dev link))
        outer-pid (condp = (:base link)
                    :conlink self-pid
                    :host 1
                    :local nil)
        link (link-add-offset link (dec index))
        link (if (and outer-pid (not (:outer-dev link)))
               (assoc link :outer-dev (link-outer-dev link id index))
               link)
        link (merge link {:container container
                          :dev-id dev-id
                          :pid pid
                          :outer-pid outer-pid})]
    link))

(defn tunnel-instance-enrich
  [tunnel self-pid]
  (let [dev (str (:type tunnel) "-" (:vni tunnel))]
    (merge tunnel {:dev dev
                   :outer-dev dev
                   :dev-id dev
                   :pid self-pid})))

;;; General commands

(defn run
  "Run/spawn a shell command with result logging. If :quiet is not set
  then print indented results (success to stdout, failure to stderr).
  If :id is set then it will be included in the results. Returns
  command result (whether failure or success)."
  [cmd & [{:as opts :keys [quiet id]}]]
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

(defn run*
  "Like run but runs each cmd in cmds. Returns final cmd result or if
  a cmd fails then returns that cmd's result."
  [cmds opts]
  (P/loop [cmds cmds]
    (P/let [[cmd & cmds] cmds
            res (run cmd opts)]
      (if (and (= 0 (:code res)) (seq cmds))
        (P/recur cmds)
        res))))

(defn rename-docker-eth0
  "If eth0 exists, then rename it to DOCKER-ETH0 to prevent 'RTNETLINK
  answers: File exists' errors during creation of links that use
  'eth0' device name. This is necessary because even if the netns is
  specified with the same link create command, the creation and move
  does not appear to be idempotent and results in the conflict."
  []
  (P/let [{:keys [log]} @ctx
          res (run "[ -d /sys/class/net/eth0 ]" {:quiet true})]
    (if (not= 0 (:code res))
      (log "No eth0 docker network interface detected")
      (P/let [_ (log "Renaming eth0 to DOCKER-ETH0")
              res (run* [(str "ip route save dev eth0 > /tmp/routesave")
                         (str "ip link set eth0 down")
                         (str "ip link set eth0 name DOCKER-ETH0")
                         (str "ip link set DOCKER-ETH0 up")
                         (str "ip route restore < /tmp/routesave")]
                        {:id "rename"})]
        (when (not= 0 (:code res))
          (fatal 1 "Could not rename docker eth0 interface"))))))

(defn start-ovs
  "Start and initialize the openvswitch daemons. Exit with error if it
  can't be started."
  []
  (P/let [res (run OVS-START-CMD)]
    (if (not= 0 (:code res))
      (fatal 1 (str "Failed starting OVS: " (:stderr res)))
      res)))

(defn kmod-loaded?
  "Return whether kernel module 'kmod' is loaded."
  [kmod]
  (P/let [cmd (str "grep -o '^" kmod "\\>' /proc/modules")
          res (run cmd {:quiet true})]
    (and (= 0 (:code res)) (= kmod (trim (:stdout res))))))

;;; Bridge commands

(defn check-no-bridge
  "Check that no bridge named 'bridge' is currently configured.
  Bridge type is dependent on mode (:ovs or :linux). Exit with
  error if the bridge already exists."
  [{:keys [bridge mode]}]
  (P/let [{:keys [info]} @ctx
          cmd (get {:ovs (str "ovs-vsctl list-ifaces " bridge)
                    :linux (str "ip link show type bridge " bridge)
                    :patch nil}
                   mode)]
    (if (not cmd)
      true
      (P/let [res (run cmd {:quiet true})]
        (if (= 0 (:code res))
          ;; TODO: maybe mark as :exists and use without cleanup
          (fatal 1 (str "Bridge " bridge " already exists"))
          (if (re-seq #"(does not exist|no bridge named)" (:stderr res))
            true
            (fatal 1 (str "Unable to run '" cmd "': " (:stderr res)))))))))


(defn bridge-create
  "Create a bridge named 'bridge'.
  Bridge type is dependent on mode (:ovs or :linux)."
  [{:keys [bridge mode]}]
  (P/let [{:keys [info error]} @ctx
          cmd (get {:ovs (str "ovs-vsctl add-br " bridge)
                    :linux (str "ip link add " bridge " up type bridge")
                    :patch nil}
                   mode)]
    (if (not cmd)
      (info (str "Ignoring bridge/switch " bridge " for mode " mode))
      (P/let [_ (info "Creating bridge/switch" bridge)
              res (run cmd)]
        (if (not= 0 (:code res))
          (error (str "Unable to create bridge/switch " bridge))
          (swap! ctx assoc-in [:network-state :bridges bridge :status] :created))
        true))))

(defn bridge-del
  "Delete the bridge named 'bridge'.
  Bridge type is dependent on mode (:ovs or :linux)."
  [{:keys [bridge mode]}]
  (P/let [{:keys [info error]} @ctx
          cmd (get {:ovs (str "ovs-vsctl del-br " bridge)
                    :linux (str "ip link del " bridge)
                    :patch nil} mode)]
    (if (not cmd)
      (info (str "Ignoring bridge/switch " bridge " for mode " mode))
      (P/let [_ (info "Deleting bridge/switch" bridge)
              res (run cmd)]
        (if (not= 0 (:code res))
          (error (str "Unable to delete bridge " bridge))
          (swap! ctx assoc-in [:network-state :bridges bridge :status] nil))
        true))))

(defn bridge-add-link
  "Add the link/interface 'dev' to the bridge 'bridge'.
  Bridge type is dependent on mode (:ovs or :linux)."
  [{:keys [bridge mode]} dev]
  (P/let [{:keys [error]} @ctx
          cmd (get {:ovs (str "ovs-vsctl add-port " bridge " " dev)
                    :linux (str "ip link set dev " dev " master " bridge)}
                   mode)
          res (run cmd)]
    (if (= 0 (:code res))
      (swap! ctx update-in [:network-state :bridges bridge :links] conj dev)
      (error (str "Unable to add link " dev " into " bridge)))))

(defn bridge-drop-link
  "Remove the link/interface 'dev' from the bridge 'bridge'.
  Bridge type is dependent on mode (:ovs or :linux)."
  [{:keys [bridge mode]} dev]
  (P/let [{:keys [error]} @ctx
          cmd (get {:ovs (str "ovs-vsctl del-port " bridge " " dev)
                    :linux (str "ip link set dev " dev " nomaster")}
                   mode)
          res (run cmd)]
    (if (= 0 (:code res))
      (swap! ctx update-in [:network-state :bridges bridge :links] disj dev)
      (error (str "Unable to drop link " dev " from " bridge)))))

(defn patch-add-link
  "Setup patch between 'dev' and its peer link using tc qdisc mirred
  filter action. Peer links are tracked in pseudo-bridge 'bridge'."
  [{:keys [bridge mode]} dev]
  (let [{:keys [info error]} @ctx
        links-path [:network-state :bridges bridge :links]
        links (get-in @ctx links-path)
        peers (disj links dev)]
    (condp = (count peers)
      0
      (P/do
        (info (str "Registering first peer link "
                   dev " in :patch 'bridge' " bridge))
        (swap! ctx update-in links-path conj dev))

      1
      (P/let [cmd (str "link-mirred.sh " dev " " (first peers))
              res (run cmd)]
        (if (= 0 (:code res))
          (swap! ctx update-in links-path conj dev)
          (error (str "Failed to setup tc filter action for "
                      dev " in :patch 'bridge' " bridge))))

      (error "Cannot add third peer link "
             dev " to :patch 'bridge' " bridge))))

(defn patch-drop-link
  "Remove tracking of 'dev' from pseudo-bridge 'bridge'."
  [{:keys [bridge mode]} dev]
  (let [{:keys [info error]} @ctx
        links-path [:network-state :bridges bridge :links]]
    (info (str "Removing peer link "
               dev " from :patch 'bridge' " bridge))
    ;; State is in the links, no extra cleanup
    (swap! ctx update-in links-path conj dev)))

;;; Link commands

(defn link-add
  "Create a link/interface defined by 'link' in a container by calling
  the 'link-add.sh' script. This function just marshalls the command
  line arguments from the 'link' definition and reports the results."
  [link]
  (P/let [{:keys [error]} @ctx
          {:keys [type dev outer-dev pid outer-pid container dev-id]} link
          cmd (str "link-add.sh"
                   " '" (name type) "' '" pid "' '" dev "'"
                   (when outer-pid (str " --pid1 " outer-pid))
                   (when outer-dev (str " --intf1 " outer-dev))
                   (S/join ""
                           (for [o LINK-ADD-OPTS]
                             (when-let [v (get link o)]
                               (str " --" (name o) " '" v "'")))))
          res (run cmd {:id dev-id})]
    (when (not= 0 (:code res))
      (error (str "Unable to add " (name type) " " dev-id)))
    res))

(defn link-del
  "Delete a link/interface defined by 'link' in a container by calling
  the 'link-del.sh' script. This function just marshalls the command
  line arguments from the 'link' definition and reports the results."
  [link]
  (P/let [{:keys [warn error]} @ctx
          {:keys [dev pid dev-id]} link
          cmd (str "link-del.sh " pid " " dev)
          res (run cmd {:id dev-id :quiet true})]
    (when (not= 0 (:code res))
      (if (re-seq #"is no longer running" (:stderr res))
        (warn (str "Skipping delete of " dev-id " (container gone)"))
        (error (str "Unable to delete " dev-id ": " (:stderr res)))))
    res))


;;; docker/docker-compose utilities

(defn get-container-id
  "Determine and return our docker or podman container ID. Returns nil
  if no container ID can be determined (e.g. we are probably not
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

(defn list-containers
  "Return a sequence of container objects optionally limited to those
  matching filters in 'filters'."
  [client & [filters]]
  (P/let [opts (if filters {:filters (json-str filters)} {})]
    ^obj (.listContainers client (->js opts))))

(defn get-container
  "Return a dockerode container object with container ID 'cid'."
  [client cid]
  ^obj (.getContainer client cid))

(defn inspect-container
  "Return a map of inspected container properties for the 'container'
  container object."
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

;;;

(defn docker-client
  "Return a docker/dockerode client object for the docker/podman
  server listening at 'path'."
  [path]
  (P/let [{:keys [warn log]} @ctx]
    (P/catch
      (P/let
        [client (Docker. #js {:socketPath path})
         ;; client is lazy so trigger it now
         containers (list-containers client)]
        (log (str "Listening on " path))
        client)
      #(warn "Could not start docker client on '" path "': " %))))

(defn docker-listen
  "Listen for docker events from 'client' that match filter 'filters'.
  Calls 'event-callback' function with each decoded event map."
  [client filters event-callback]
  (P/let [{:keys [error log]} @ctx]
    (P/catch
      (P/let
        [ev-stream ^obj (.getEvents client #js {:filters (json-str filters)})
         _ ^obj (.on ev-stream "data"
                     #(event-callback client (->clj (js/JSON.parse %))))]
        ev-stream)
      #(error "Could not start docker listener"))))

(defn link-repr [{:keys [type dev remote outer-dev bridge ip dev-id]}]
  (str dev-id
       (if remote
         (str " (bridge " bridge ", remote " remote ")")
         (str (when ip (str " (IP " ip ")"))
              (when outer-dev (str " <-> " outer-dev))
              (when bridge (str " (bridge " bridge ")"))))))

(defn modify-link
  "Depending on 'action' create ('start') or delete ('die') a link
  defined by 'link'. veth type links and tunnel interfaces will also
  be added to the local bridge defined in the link. The network-state
  for this link will be updated to either :creating or :deleting
  before any action is taken.  Once the async commands complete, the
  state will be updated to either :created or nil."
  [link action]
  (P/let
    [{:keys [error log]} @ctx
     {:keys [type outer-dev bridge dev-id]} link
     status-path [:network-state :devices dev-id :status]
     link-status (get-in @ctx status-path)]
    (log (str (get {"start" "Creating" "die" "Deleting"} action)
              " " (name type) " link " (link-repr link)))
    (condp = action
      "start"
      (if link-status
        (error (str "Link " dev-id " already exists"))
        (P/do
          (swap! ctx assoc-in status-path :creating)
          (link-add link)
          (when bridge
            (if (= :patch (:mode bridge))
              (patch-add-link bridge outer-dev)
              (bridge-add-link bridge outer-dev)))
          (swap! ctx assoc-in status-path :created)))

      "die"
      (if (not link-status)
        (error (str "Link " dev-id " does not exist"))
        (P/do
          (swap! ctx assoc-in status-path :deleting)
          (when bridge
            (if (= :patch (:mode bridge))
              (patch-drop-link bridge outer-dev)
              (bridge-drop-link bridge outer-dev)))
          (link-del link)
          (swap! ctx assoc-in status-path nil))))))

(defn exec-command
  "Exec a command 'command' in the container named 'cname' using the
  'container' object. If 'command' is a string then call the command
  using 'sh -c'. Every line of output from the command is prefixed
  with 'cname' and printed to stdout."
  [cname container command]
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
              (fn [b] (log (str "  " cname ": " (trim (.toString b "utf8"))))))
    ex))

(defn all-connected-check
  "Check if all containers/services have been connected (at least one
  link for each service) and if so, output the ending network state (if
  verbose) and the message 'All links connected'. This will only fire
  once. Caveat: can fire early depending on the service replica/scale
  counts."
  []
  (let [{:keys [network-config network-state log info]} @ctx
        {:keys [links tunnels]} network-config
        {:keys [devices all-connected]} network-state]
    (when (and (not all-connected)
               (every? #(= :created (:status %)) (vals devices))
               (>= (count devices) (+ (count links) (count tunnels))))
      ;; Save all-connected to prevent scale/stop-start from
      ;; showing this message multiple times.
      (swap! ctx assoc-in [:network-state :all-connected] true)
      (info (str "Ending network state:\n"
                 (indent-pprint-str network-state "  ")))
      (log "All links connected"))))

(defn handle-event
  "Handle a docker/podman container event. Match the event to
  a container or service network definition (if any), then create all
  the links for that container and then run any commmands defined for
  the container. Finally call all-connected-check to check and notify
  if all containers/services are connected."
  [client {:keys [status id]}]
  (P/let
    [{:keys [log info network-config compose-opts self-pid]} @ctx
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
     containers (get-in network-config [:containers cname])
     services (when svc-match? (get-in network-config [:services svc-name]))
     links (concat (:links containers) (:links services))
     commands (concat (:commands containers) (:commands services))]
    (if (and (not (seq links)) (not (seq commands)))
      (info (str "Event: no matching config for " cname ", ignoring"))
      (P/do
        (info "Event:" status cname id)
        (P/all (for [link links
                     :let [link (link-instance-enrich
                                  link container-info self-pid)]]
                 (modify-link link status)))
        (when (= "start" status)
          (P/all (for [{:keys [command]} commands]
                   (exec-command cname container-obj command))))

        (all-connected-check)))))

(defn exit-handler
  "When the process is exiting, delete all links and bridges that are
  currently configured (have :created status)."
  [err origin]
  (let [{:keys [log info network-state]} @ctx
        {:keys [devices bridges]} network-state
        ;; filter for :created status (ignore :exists)
        devices (filter #(= :created (-> % val :status)) devices)
        bridges (filter #(= :created (-> % val :status)) bridges)]
    (info (str "Got " origin ":") err)
    (P/do
      (when (seq devices)
        (P/do
          (log (str "Removing devices: " (S/join ", " (keys devices))))
          (P/all (map link-del (vals devices)))))
      (when (seq bridges)
        (P/do
          (log (str "Removing bridges: " (S/join ", " (keys bridges))))
          (P/all (map bridge-del (vals bridges)))))
      (js/process.exit 127))))


;;;

(defn arg-checks
  "Check command line arguments. Exit with error if arguments are
  invalid."
  [{:keys [network-file compose-file config-schema orig-config-schema]}]
  (when (and (empty? network-file) (empty? compose-file))
    (fatal 2 "either --network-file or --compose-file is required"))
  (when (empty? config-schema)
    (fatal 2 "Could not find config-schema" orig-config-schema)))

(defn server
  "Process:
    - parse/validate command line options
    - load/combine/mangle/validate network configuration
    - connect to docker and/or podman server
    - determine our own container ID and compose properties (if any)
    - generate runtime network state and other process context/state
    - install exit/cleanup handlers
    - start/init openvswitch daemons/config (if any bridges use :ovs mode)
    - check that any defined bridges do not already exist
    - create any bridges defined in network config links
    - start listening/handling docker/podman container events
    - list/handle any already running containers that match the config
    "
  [& args]
  (P/let
    [{:as opts :keys [verbose show-config]} (parse-opts usage args)
     {:keys [log info]} (swap! ctx merge (when verbose {:info Eprintln}))
     opts (merge
            opts
            {:default-bridge-mode (keyword (:default-bridge-mode opts))
             :orig-config-schema (:config-schema opts)
             :config-schema (resolve-path (:config-schema opts) SCHEMA-PATHS)
             :network-file (mapcat #(S/split % #":") (:network-file opts))
             :compose-file (mapcat #(S/split % #":") (:compose-file opts))})
     _ (arg-checks opts)
     _ (info (str "User options:\n" (indent-pprint-str opts "  ")))

     {:keys [network-file compose-file compose-project]} opts
     env (js->clj (js/Object.assign #js {} js/process.env))
     self-pid js/process.pid
     schema (load-config (:config-schema opts))
     kmod-ovs? (kmod-loaded? "openvswitch")
     kmod-mirred? (kmod-loaded? "act_mirred")
     _ (swap! ctx merge {:default-bridge-mode (:default-bridge-mode opts)
                         :default-mtu (:default-mtu opts)
                         :kmod-ovs? kmod-ovs?
                         :kmod-mirred? kmod-mirred?})
     network-config (P/-> (load-configs compose-file network-file)
                          (interpolate-walk env)
                          (check-schema schema verbose)
                          (enrich-network-config))
     _ (when show-config
         (println (js/JSON.stringify (->js network-config)))
         (js/process.exit 0))

     docker (docker-client (:docker-socket opts))
     podman (docker-client (:podman-socket opts))
     _ (when (and (not docker) (not podman))
         (fatal 1 "Failed to start either docker or podman client/listener"))

     self-cid (get-container-id)
     self-container-obj (when self-cid
                          (get-container (or docker podman) self-cid))
     self-container (inspect-container self-container-obj)
     compose-opts (if compose-project
                    {:project compose-project}
                    (get-compose-labels self-container))
     network-state (gen-network-state network-config)
     ctx-data {:network-config network-config
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

    (log (str "Using schema at '" (:config-schema opts) "'"))
    (info (str "Starting network config\n"
               (indent-pprint-str network-config "  ")))
    (info (str "Starting network state:\n"
               (indent-pprint-str network-state "  ")))
    (when self-cid
      (info "Detected enclosing container:" self-cid))
    (when compose-project
      (info "Detected compose context:" compose-project))

    (P/do
      (when self-cid
        (rename-docker-eth0))

      (when (some #(= :ovs (:mode %)) (-> network-config :bridges vals))
        (start-ovs))

      ;; Check that bridges/switches do not already exist
      (P/all (for [bridge (vals (:bridges network-state))]
               (check-no-bridge bridge)))

      ;; Create bridges/switch configs
      ;; TODO: should be done on-demand
      (P/all (for [bridge (vals (:bridges network-state))]
               (bridge-create bridge)))

      ;; Create tunnels configs
      (P/all (for [tunnel (:tunnels network-config)
                   :let [tunnel (tunnel-instance-enrich tunnel self-pid)]]
               (modify-link tunnel "start")))

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
      nil)))


(defn main
  [& args]
  ;; nbb implicitly does this wrapping but shadow-cljs does not
  ;; (exceptions result in successful exit code).
  (P/catch
    (apply server args)
    (fn [err]
      (fatal 1 "Error during conlink server startup:" err))))
