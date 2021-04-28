#!/usr/bin/env bash

set -x
IMAGE_NAME="${IMAGE_NAME:-conlink}"

docker build -t ${IMAGE_NAME} .
docker run --rm \
    --name ZZZ_conlink \
    --pid host \
    --network none \
    --cap-add SYS_ADMIN --cap-add SYS_NICE --cap-add NET_ADMIN \
    -v /sys/fs/cgroup:/sys/fs/cgroup:ro \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v /var/lib/docker:/var/lib/docker \
    -v $(pwd)/:/test \
    ${IMAGE_NAME} \
    /sbin/conlink.py --network-file /test/examples/test3-network.yaml

