#!/bin/bash

# Copyright (c) 2023, Viasat, Inc
# Licensed under MPL 2.0

set -e

usage () {
  echo >&2 "${0} [OPTIONS] TYPE PID0 INTF0"
  echo >&2 ""
  echo >&2 "Create link TYPE interface INTF0 in netns PID0."
  echo >&2 ""
  echo >&2 "Positional arguments:"
  echo >&2 "  TYPE is the link/interface type"
  echo >&2 "  INTF0 is the primary interface name"
  echo >&2 "  PID0 is the primary netns process ID"
  echo >&2 ""
  echo >&2 "The INTF1/PID1 parameters have type specific meaning:"
  echo >&2 "  - veth: the peer interface in PID1 netns"
  echo >&2 "  - *vlan/*vtap: the parent interface in PID1 netns"
  echo >&2 "  - geneve/vxlan: not applicable"
  echo >&2 ""
  echo >&2 "OPTIONS:"
  echo >&2 "  --verbose                - Verbose output (set -x)"
  echo >&2 "  --pid1 PID1              - Secondary netns process ID"
  echo >&2 "                             (default: <SELF>)"
  echo >&2 "  --intf1 INTF1            - Secondary interface name"
  echo >&2 "                             (default: eth0)"
  echo >&2 "  --ip  IP0                - IP (CIDR) address for INTF0"
  echo >&2 "  --ip0 IP0                - IP (CIDR) address for INTF0"
  echo >&2 "  --ip1 IP1                - IP (CIDR) address for INTF1"
  echo >&2 "  --mac  MAC0              - MAC address for INTF0"
  echo >&2 "  --mac0 MAC0              - MAC address for INTF0"
  echo >&2 "  --mac1 MAC1              - MAC address for INTF1"
  echo >&2 "  --route|--route0 'ROUTE' - route to add to INTF0 (can repeat)"
  echo >&2 "  --route1 'ROUTE'         - route to add to INTF1 (can repeat)"
  echo >&2 "  --mtu MTU                - MTU for both interfaces"
  echo >&2 ""
  echo >&2 "  --mode MODE              - Mode settings for *vlan TYPEs"
  echo >&2 "  --vlanid VLANID          - VLAN ID for vlan TYPE"
  echo >&2 ""
  echo >&2 "  --remote REMOTE          - Remote address for geneve/vxlan types"
  echo >&2 "  --vni VNI                - Virtual Network Identifier for geneve/vxlan types"
  echo >&2 ""
  echo >&2 "  --netem NETEM            - tc qdisc netem OPTIONS (man 8 netem) (can repeat)"
  echo >&2 "  --nat TARGET             - Stateless NAT traffic to/from TARGET"
  echo >&2 "                             (in primary/PID0 netns)"
  echo >&2 ""
  exit 2
}

info() { echo "link-add [${LOG_ID}] ${*}"; }
warn() { >&2 echo "link-add [${LOG_ID}] ${*}"; }
die()  { warn "ERROR: ${*}"; exit 1; }

# Set MAC, IP, ROUTES, MTU, and up state for interface in netns
setup_if() {
  local IF=$1 NS=$2 MAC=$3 IP=$4 MTU=$5 ROUTES=$6 routes=
  echo >&2 "ROUTES: ${ROUTES}"
  while read rt; do
      [ "${rt}" ] && routes="${routes}\nroute add ${rt} dev ${IF}"
  done < <(echo -e "${ROUTES}")

  info "Setting ${IP:+IP ${IP}, }${MAC:+MAC ${MAC}, }${MTU:+MTU ${MTU}, }${ROUTES:+ROUTES '${ROUTES//$'\n'/,}', }up state"
  ip -netns ${NS} --force -b - <<EOF
      ${IP:+addr add ${IP} dev ${IF}}
      ${MAC:+link set dev ${IF} address ${MAC}}
      ${MTU:+link set dev ${IF} mtu ${MTU}}
      link set dev ${IF} up
      $(echo -e "${routes}")
EOF
}

IPTABLES() {
  local ns=${1}; shift
  ip netns exec ${ns} iptables -D "${@}" 2>/dev/null || true
  ip netns exec ${ns} iptables -I "${@}"
}


# Parse arguments
VERBOSE=${VERBOSE:-}
PID1=${PID1:-<SELF>} IF1=${IF1:-eth0}
IP0= IP1= MAC0= MAC1= ROUTES0= ROUTES1= MTU=
MODE= VLANID= REMOTE= VNI= NETEM= NAT=
positional=
while [ "${*}" ]; do
  param=$1; OPTARG=$2
  case ${param} in
  --verbose)        VERBOSE=1 ;;
  --pid1)           PID1="${OPTARG}"; shift ;;
  --intf1)          IF1="${OPTARG}"; shift ;;
  --ip|--ip0)       IP0="${OPTARG}"; shift ;;
  --ip1)            IP1="${OPTARG}"; shift ;;
  --mac|--mac0)     MAC0="${OPTARG}"; shift ;;
  --mac1)           MAC1="${OPTARG}"; shift ;;
  --route|--route0) ROUTES0="${ROUTES0}\n${OPTARG}"; shift ;;
  --route1)         ROUTES1="${ROUTES1}\n${OPTARG}"; shift ;;
  --mtu)            MTU="${OPTARG}"; shift ;;

  --mode)           MODE="${OPTARG}"; shift ;;
  --vlanid)         VLANID="${OPTARG}"; shift ;;

  --remote)         REMOTE="${OPTARG}"; shift ;;
  --vni)            VNI="${OPTARG}"; shift ;;

  --netem)          NETEM="${NETEM} ${OPTARG}"; shift ;;
  --nat)            NAT="${OPTARG}"; shift ;;
  -h|--help)        usage ;;
  *)                positional="${positional} $1" ;;
  esac
  shift
done
ROUTES0="${ROUTES0#\\n}"
ROUTES1="${ROUTES1#\\n}"
set -- ${positional}
TYPE=$1 PID0=$2 IF0=$3

[ "${VERBOSE}" ] && set -x || true

# Sanity check arguments
[ "${TYPE}" -a "${PID0}" -a "${IF0}" ] || usage
[ "${NAT}" -a -z "${IP0}" ] && die "--nat requires --ip0"

LOG_ID="${TYPE} ${PID0}:${IF0}"
case "${TYPE}" in
veth)
  LOG_ID="${LOG_ID} <-> ${PID1}:${IF1}"
  ;;
*vlan|*vtap)
  LOG_ID="${LOG_ID} <<< ${PID1}:${IF1}"
  [ "${IF1}" ] || die "--intf1 required for ${TYPE} link"
  [ "${REMOTE}" -o "${VNI}" ] && die "--remote/--vlanid incompatible with ${TYPE} link"
  ;;
geneve|vxlan)
  LOG_ID="${LOG_ID} <<->> ${REMOTE}(${VNI})"
  [ "${REMOTE}" -a "${VNI}" ] || die "--remote and --vni required for ${TYPE} link"
  [ "${MODE}" -o "${VLANID}" ] && die "--mode/--vlanid incompatible with ${TYPE} link"
  ;;
*)
  [ "${PID1}" != "<SELF>" ] && die "--pid1 not supported for ${TYPE} link"
  ;;
esac

[ "${PID1}" = "<SELF>" ] && PID1=$$
NS0=ns${PID0} NS1=ns${PID1}

# Sanity checks
[ ! -d /proc/$PID0 ] && die "PID0 $PID0 is no longer running!"
[ ! -d /proc/$PID1 ] && die "PID1 $PID1 is no longer running!"

### Do the work

info "Creating ${TYPE} link"

info "Creating ip netns to pid mappings"
export PATH=$PATH:/usr/sbin  # to find iptables
mkdir -p /var/run/netns
ln -sf /proc/${PID0}/ns/net /var/run/netns/${NS0}
ln -sf /proc/${PID1}/ns/net /var/run/netns/${NS1}

case "${TYPE}" in
veth)
  info "Creating ${TYPE} pair interfaces"
  echo ip link add ${IF0} netns ${NS0} type veth peer ${IF1} netns ${NS1}
  ip link add ${IF0} netns ${NS0} type veth peer ${IF1} netns ${NS1}
  ;;
*vlan|*vtap)
  info "Creating ${TYPE} interface"
  SIF0=if0-${RANDOM}
  ip -netns ${NS1} link add name ${SIF0} link ${IF1} type ${TYPE} \
    ${MODE:+mode ${MODE}} ${VLANID:+id ${VLANID}}
  info "Moving ${TYPE} interface"
  ip -netns ${NS1} link set ${SIF0} netns ${NS0}
  info "Renaming ${TYPE} interface"
  ip -netns ${NS0} link set ${SIF0} name ${IF0}
  ;;
geneve|vxlan)
  info "Creating ${TYPE} tunnel interface"
  ip -netns ${NS1} link add name ${IF0} type ${TYPE} \
    remote ${REMOTE} id ${VNI}
  ;;
*)
  info "Creating ${TYPE} interface"
  ip -netns ${NS0} link add ${IF0} type ${TYPE}
  ;;
esac

setup_if ${IF0} ${NS0} "${MAC0}" "${IP0}" "${MTU}" "${ROUTES0}"
[ "${TYPE}" = "veth" ] && \
  setup_if ${IF1} ${NS1} "${MAC1}" "${IP1}" "${MTU}" "${ROUTES1}"

if [ "${NETEM}" ]; then
  info "Setting tc qdisc netem: ${NETEM}"
  tc -netns ${NS0} qdisc add dev ${IF0} root netem ${NETEM}
fi

if [ "${NAT}" ]; then
  info "Adding NAT rule to ${NAT}"
  IPTABLES ${NS0} PREROUTING  -t nat -i ${IF0} -j DNAT --to-destination ${NAT}
  IPTABLES ${NS0} POSTROUTING -t nat -o ${IF0} -j SNAT --to-source ${IP0%/*}
fi

info "Created ${TYPE} link"
