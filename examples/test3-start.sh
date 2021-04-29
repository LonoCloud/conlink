#!/usr/bin/env bash

set -ex

IMAGE_NAME="${IMAGE_NAME:-conlink}"
export NETWORK_NAME=${1}; shift
export NODE_NAME=${1}; shift

docker build -t ${IMAGE_NAME} .
docker run --rm \
    --name ${NETWORK_NAME} \
    --pid host \
    --network none \
    --cap-add SYS_ADMIN --cap-add SYS_NICE \
    --cap-add NET_ADMIN --cap-add NET_BROADCAST \
    -v /sys/fs/cgroup:/sys/fs/cgroup:ro \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v /var/lib/docker:/var/lib/docker \
    -v $(pwd)/:/test \
    -e NETWORK_NAME \
    -e NODE_NAME \
    ${IMAGE_NAME} \
    /sbin/conlink.py --network-file /test/examples/test3-network.yaml

