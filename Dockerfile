FROM ubuntu:20.04 as base

RUN apt-get -y update
RUN apt-get -y install util-linux iproute2 bridge-utils tzdata iptables

# network/debug
RUN apt-get -y install curl ethtool tcpdump socat iputils-ping

# config-mininet/mininet runtime deps
RUN apt-get -y install python3-minimal net-tools cgroup-tools telnet \
    openvswitch-switch openvswitch-testcontroller kmod

#############################################################
FROM base as python3-packages

# more config-mininet deps (mininet is just for mnexec executable)
RUN apt-get -y install python3-pip python3-pkg-resources mininet

RUN pip3 install pyyaml mininet cerberus docker psutil

#############################################################
FROM base as runtime

# Addition services/debug utilties we might want
RUN apt-get -y install strace socat wget iperf3 dnsmasq

COPY --from=python3-packages /usr/lib/python3/ /usr/lib/python3/
COPY --from=python3-packages /usr/lib/python3.8/ /usr/lib/python3.8/
COPY --from=python3-packages /usr/local/lib/python3.8/ /usr/local/lib/python3.8/
COPY --from=python3-packages /usr/bin/mnexec /usr/bin/
ADD config_mininet.py /usr/local/lib/python3.8/dist-packages/
ADD schema.yaml /usr/local/share/conlink_schema.yaml
ADD conlink.py veth-link.sh /sbin/
