#!/bin/bash

# Copyright (c) 2021, Viasat, Inc
# Licensed under MPL 2.0

set -e

usage () {
    echo "${0} [OPTIONS] INTF0 INTF1 PID0 PID1"
    echo ""
    echo "  INTF0 is the name of first veth interface"
    echo "  INTF1 is the name of second veth interface name"
    echo "  PID0 is the process ID of the first container"
    echo "  PID1 is the process ID of the second container"
    echo ""
    echo "  Where OPTIONS are:"
    echo "     --ip0 IP0    - IP address"
    echo "     --ip1 IP1    - IP address"
    echo "     --mac0 MAC0  - MAC address"
    echo "     --mac1 MAC1  - MAC address"
    exit 2
}

# Parse arguments
VERBOSE=${VERBOSE:-}
IP0= IP1= MAC0= MAC1= PID0= PID1= CID0= CID1=
POSITIONAL=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose) VERBOSE=1 ;;
  --ip0) IP0="${OPTARG}"; shift ;;
  --ip1) IP1="${OPTARG}"; shift ;;
  --mac0) MAC0="${OPTARG}"; shift ;;
  --mac1) MAC1="${OPTARG}"; shift ;;
  -h|--help) usage ;;
  *) POSITIONAL="${POSITIONAL} $1" ;;
  esac
  shift
done
set -- ${POSITIONAL}

IF0=$1
IF1=$2
PID0=$3
PID1=$4

info() {
    echo "veth-link [${PID0}/${IF0} <-> ${PID1}/${IF1}] ${*}"
}

warn() {
    >&2 echo "veth-link [${PID0}/${IF0} <-> ${PID1}/${IF1}] ${*}"
}

die () {
    warn "ERROR: ${*}"
    exit 1
}

info "Creating veth pair link (${IP0}|${MAC0} <-> ${IP1}|${MAC1})"

# Check arguments
[ "${IF0}" -a "${IF1}" -a "${PID0}" -a "${PID1}" ] || usage

[ "${VERBOSE}" ] && set -x || true

TMP_IF0=T0-${PID0}
TMP_IF1=T1-${PID1}

# Sanity checks
[ ! -d /proc/$PID0 ] && die "PID0 $PID0 is no longer running!"
[ ! -d /proc/$PID1 ] && die "PID1 $PID1 is no longer running!"
ip link show "${TMP_IF0}" > /dev/null 2>&1 && die "Interface ${TMP_IF0} exists"
ip link show "${TMP_IF1}" > /dev/null 2>&1 && die "Interface ${TMP_IF1} exists"


insert_if() {
  local ORIG_IF=$1 IF=$2 PID=$3 MAC=$4 IP=$5 OTHER_IP=$6

  # Create a netns links to the container network namespace
  NSID=ns${PID}
  mkdir -p /var/run/netns
  rm -f /var/run/netns/${NSID}
  ln -sf /proc/${PID}/ns/net /var/run/netns/${NSID}

  # Inject the veth interface into the container
  ip link set ${ORIG_IF} netns ${NSID}

  # Setup the inner interface
  ip -netns ${NSID} --force -b - <<EOF
      link set ${ORIG_IF} name ${IF}
      ${IP:+addr add ${IP} dev ${IF}}
      ${MAC:+link set dev ${IF} address ${MAC}}
      link set dev ${IF} up
      netns del ${NSID}
EOF
      #${OTHER_IP:+route replace ${OTHER_IP} dev ${IF}}
      #${OTHER_IP:+route replace default via ${OTHER_IP%/*} dev ${IF}}

}

### Do the work

#info "Assigning ${IP} to ${CID:0:12}, VRIP: ${VRIP}, PID: ${PID}"

# Create the veth pair
ip link add ${TMP_IF0} type veth peer name ${TMP_IF1}

# Insert the ends into the containers
insert_if "${TMP_IF0}" "${IF0}" "${PID0}" "${MAC0}" "${IP0}" "${IP1}"
insert_if "${TMP_IF1}" "${IF1}" "${PID1}" "${MAC1}" "${IP1}" "${IP0}"

info "Created veth pair link (${IP0}|${MAC0} <-> ${IP1}|${MAC1})"
