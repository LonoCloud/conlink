#!/usr/bin/env bash

set -e

usage () {
    echo "${0} [CONLINK_OPTIONS] CONFIG_OPTIONS [-- CMD_OPTIONS]"
    echo ""
    echo "Where CONLINK_OPTIONS are:"
    echo "   --verbose              - Enable verbose output"
    echo "   --dry-run              - Show what would be done"
    echo "   --mode MODE            - Conlink launch mode: podman, docker"
    echo "                            (default: podman)"
    echo "   --image IMAGE          - Conlink image to use"
    echo "                            (default: conlink)"
    echo "   --host-mode HOST_MODE  - External container manager mode:"
    echo "                            podman, docker, none"
    echo "                            (default: none)"
    echo ""
    echo "Where CONFIG_OPTIONS are (at least one must be specified):"
    echo "   --network-file NET_CFG  - Conlink network config file"
    echo "   --compose-file COM_CFG  - Compose file containing conlink service"
    echo "                             with an optional an x-network key"
}

VERBOSE=${VERBOSE:-}
DRY_RUN=${DRY_RUN:-}
MODE=${MODE:-podman}
IMAGE="${IMAGE:-conlink}"
HOST_MODE=${HOST_MODE:-}
HOST_NETWORK=${HOST_NETWORK}
CMD="${CMD:-/app/build/conlink.js}"
NETWORK_FILE=${NETWORK_FILE:-}
COMPOSE_FILE=${COMPOSE_FILE:-}
COMPOSE_PROJECT=${COMPOSE_PROJECT:-}

die() { echo >&2 "${*}"; exit 1; }
vecho() { [ "${VERBOSE}" ] && echo "${@}" || true; }

psvc=
cleanup() {
    trap - EXIT INT TERM
    echo "Cleaning up"
    if [ "${psvc}" ]; then
      kill ${psvc}
      [ -e "${HOST_SOCK_PATH}" ] && rm -f "${HOST_SOCK_PATH}"
    fi
}

# Parse arguments
CMD_OPTS=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  -v|--verbose) VERBOSE=1 CMD_OPTS="${CMD_OPTS} -v" ;;
  #-vv) VERBOSE=1 CMD_OPTS="${CMD_OPTS} -v -v" ;;
  --dry-run) DRY_RUN=1 ;;
  --mode) MODE="${OPTARG}"; shift ;;
  --image) IMAGE="${OPTARG}"; shift ;;
  --host-mode) HOST_MODE="${OPTARG}"; shift ;;
  --network-file) NETWORK_FILE="${OPTARG}"; shift ;;
  --compose-file) COMPOSE_FILE="${OPTARG}"; shift ;;
  --compose-project) CMD_OPTS="${CMD_OPTS} --compose-project ${OPTARG}"; shift ;;
  -h|--help) usage ;;
  --) shift; break ;;
  *) CMD_OPTS="${CMD_OPTS} $1" ;;
  esac
  shift
done
CCMD="${MODE}"
[ "${DRY_RUN}" ] && CCMD="echo ${CCMD}"
# Remaining args are for podman/docker

### Sanity checks

# TODO: check for openvswitch and geneve

case "${MODE}" in
  podman|docker) ;;
  *) die "Unknown mode '${MODE}'" ;;
esac
[ "${NETWORK_FILE}" -o "${COMPOSE_FILE}" ] || \
  die "One or both required: --network-file or --compose-file"

### Construct command line arguments
vecho "Settings:"
RUN_OPTS="${RUN_OPTS} --security-opt apparmor=unconfined"

vecho "  - mount network/compose config files"
if [ "${NETWORK_FILE}" ]; then
  network_path=/root/$(basename ${NETWORK_FILE})
  RUN_OPTS="${RUN_OPTS} -v $(readlink -f ${NETWORK_FILE}):${network_path}:ro"
  CMD_OPTS="${CMD_OPTS} --network-file ${network_path}"
fi
if [ "${COMPOSE_FILE}" ]; then
  compose_path=/root/$(basename ${COMPOSE_FILE})
  RUN_OPTS="${RUN_OPTS} -v $(readlink -f ${COMPOSE_FILE}):${compose_path}:ro"
  CMD_OPTS="${CMD_OPTS} --compose-file ${compose_path}"
fi

# podman specific settings and shared container storage
case "${MODE}" in
  podman)
    vecho "  - support running systemd in containers"
    RUN_OPTS="${RUN_OPTS} --systemd=always"
    vecho "  - port forwarding with slirp4netns (for rootless mode)"
    RUN_OPTS="${RUN_OPTS} --network=slirp4netns:port_handler=slirp4netns"
    if [ "$(id -u)" = 0 ]; then
        host_containers=/var/lib/containers
    else
        host_containers=$HOME/.local/share/containers
    fi
    vecho "  - mount shared storage from ${host_containers}"
    RUN_OPTS="${RUN_OPTS} -v ${host_containers}:/var/lib/host-containers:ro"
    ;;
esac

# permissions/capabilities
if [ "$(id -u)" = 0 ]; then
  vecho "  - adding base capabilities"
  RUN_OPTS="${RUN_OPTS} --cap-add SYS_ADMIN --cap-add NET_ADMIN"
  RUN_OPTS="${RUN_OPTS} --cap-add SYS_NICE --cap-add NET_BROADCAST"
  RUN_OPTS="${RUN_OPTS} --cap-add IPC_LOCK"
else
  vecho "  - adding --priviledged (for rootless)"
  RUN_OPTS="${RUN_OPTS} --privileged"
fi

# Warning: --pid host (without cgroup v2) can leak conmon processes to
# the outer host if the internal cleanup doesn't complete fully.
case "${HOST_MODE}" in
  podman)
    vecho "  - adding connectivity to outer podman"
    RUN_OPTS="${RUN_OPTS} --pid host"
    HOST_SOCK_PATH=$(mktemp -u $(pwd)/podman.sock.XXXXXXXXXX)
    RUN_OPTS="${RUN_OPTS} -v ${HOST_SOCK_PATH}:/var/run/podman/podman.sock"
    ;;
  docker)
    vecho "  - adding connectivity to outer docker"
    RUN_OPTS="${RUN_OPTS} --pid host"
    RUN_OPTS="${RUN_OPTS} -v /var/lib/docker:/var/lib/docker"
    RUN_OPTS="${RUN_OPTS} -v /var/run/docker.sock:/var/run/docker.sock"
    ;;
  none|"")
    ;;
  *) die "Unknown host mode '${HOST_MODE}'" ;;
esac


### Start it up

trap cleanup EXIT INT TERM

### Start podman API service for external podman connectivity

if [ "${HOST_MODE}" = "podman" ]; then
  vecho "Starting outer podman API service"
  rm -f "${HOST_SOCK_PATH}"
  ${DRY_RUN:+echo} podman system service --time=0 "unix://${HOST_SOCK_PATH}" &
  psvc=$!   # for cleanup
  for i in $(seq 5); do
      [ -e "${HOST_SOCK_PATH}" ] && break
      echo "Waiting (try $i/5) for podman service to start"
      sleep 1
  done
  [ -e "${HOST_SOCK_PATH}" ] || die "podman service did not start in 5 seconds"
fi

### Run conlink/docker
vecho "Starting conlink"
echo ${MODE} run ${RUN_OPTS} "${@}" ${IMAGE} ${CMD} ${CMD_OPTS}
[ ${DRY_RUN} ] || ${MODE} run ${RUN_OPTS} "${@}" ${IMAGE} ${CMD} ${CMD_OPTS}

