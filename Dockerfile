FROM ubuntu:20.04 as base

RUN apt-get -y update #1

RUN apt-get -y install python3-minimal


#############################################################
FROM base as podman

VOLUME /var/lib/containers

# network/debug
RUN apt-get -y install util-linux iproute2 bridge-utils tzdata \
    iptables ethtool tcpdump socat iputils-ping strace socat \
    curl wget iperf3 dnsmasq jq

# podman for launching "docker" containers
RUN apt-get -y install gnupg && \
    echo "deb https://download.opensuse.org/repositories/devel:/kubic:/libcontainers:/stable/xUbuntu_20.04/ /" > /etc/apt/sources.list.d/devel:kubic:libcontainers:stable.list && \
    curl -L "https://download.opensuse.org/repositories/devel:/kubic:/libcontainers:/stable/xUbuntu_20.04/Release.key" | apt-key add - && \
    apt-get update && \
    apt-get -y install fuse-overlayfs podman

ADD https://raw.githubusercontent.com/containers/libpod/master/contrib/podmanimage/stable/containers.conf /etc/containers/containers.conf

RUN chmod 644 /etc/containers/containers.conf && \
    sed -i -e 's|^#mount_program|mount_program|g' \
           -e '/additionalimage.*/a "/var/lib/host-containers/storage",' \
           -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' \
           /etc/containers/storage.conf


#############################################################
FROM base as build

# more config-mininet deps
RUN apt-get -y install git python3-pip python3-pkg-resources help2man

RUN pip3 install pyyaml cerberus docker psutil python-iptables

RUN git clone --branch dev-dockerhost https://github.com/kanaka/mininet /root/mininet && \
    cd /root/mininet && \
    pip3 install . && \
    PYTHON=python3 make install-mnexec

#############################################################
FROM podman as conlink

# config-mininet/mininet runtime deps
RUN apt-get -y install net-tools cgroup-tools telnet \
    openvswitch-switch openvswitch-testcontroller kmod

COPY --from=build /usr/lib/python3/ /usr/lib/python3/
COPY --from=build /usr/lib/python3.8/ /usr/lib/python3.8/
COPY --from=build /usr/local/lib/python3.8/ /usr/local/lib/python3.8/
COPY --from=build /usr/bin/mnexec /usr/bin/

ADD config_mininet.py conlink.py podmanhost.py compose_interpolation.py /usr/local/lib/python3.8/dist-packages/
ADD schema.yaml /usr/local/share/conlink_schema.yaml
ADD conlink config_mininet veth-link.sh tun-link.sh /sbin/

