#!/usr/bin/env bash

set -ex

export NETWORK_NAME=${1}; shift
export NODE_NAME=${1}; shift

IMAGE_NAME="${IMAGE_NAME:-conlink}"
NETWORK_FILE=${NETWORK_FILE:-/test/examples/test4-network.yaml}
CMD="${CMD:-/sbin/conlink --network-file ${NETWORK_FILE}}"
HOST_SOCK_PATH=$(pwd)/podman.sock

die() { echo >&2 "${*}"; exit 1; }

psvc=
cleanup() {
    trap - EXIT INT TERM
    echo "Cleaning up"
    [ "${psvc}" ] && kill ${psvc}
    [ -e "${HOST_SOCK_PATH}" ] && rm -f "${HOST_SOCK_PATH}"
}
trap cleanup EXIT INT TERM

if [ "$(id -u)" = 0 ]; then
    echo "Detected root, running rootful podman"
    HOST_CONTAINERS=/var/lib/containers
else
    echo "Detected non-root, running rootless podman"
    HOST_CONTAINERS=$HOME/.local/share/containers
fi
# TODO: check for openvswitch

[ -e "${HOST_SOCK_PATH}" ] && rm "${HOST_SOCK_PATH}"
podman system service --time=0 "unix://${HOST_SOCK_PATH}" &
psvc=$!
podman build -t ${IMAGE_NAME} -f Dockerfile .
for i in $(seq 5); do
    [ -e "${HOST_SOCK_PATH}" ] && break
    echo "Waiting (try $i/5) for podman service to start"
    sleep 1
done
[ -e "${HOST_SOCK_PATH}" ] || die "podman service did not start in 5 seconds"

# --pid host (without cgroup v2) can leak conmon processes to the
# outer host if the internal cleanup doesn't complete fully.
podman run --rm \
    --name ${NETWORK_NAME} \
    --pid host \
    --privileged \
    -v ${HOST_CONTAINERS}:/var/lib/host-containers \
    -v ${HOST_SOCK_PATH}:/var/run/docker.sock \
    -v /var/lib/docker:/var/lib/docker \
    -v $(pwd)/:/test \
    -e NETWORK_NAME="${NETWORK_NAME}" \
    -e NODE_NAME="${NODE_NAME}" \
    ${IMAGE_NAME} \
    ${CMD}

