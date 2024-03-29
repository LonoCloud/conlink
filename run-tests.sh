#!/usr/bin/env bash

export VERBOSE=${VERBOSE:-}
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-conlink-test}
declare TEST_NUM=0
declare -A RESULTS
declare PASS=0
declare FAIL=0

die() { echo >&2 "${*}"; exit 1; }
vecho() { [ "${VERBOSE}" ] && echo "${*}" || true; }
dc() { docker-compose "${@}"; }
mdc() { ./mdc "${@}" || die "mdc invocation failed"; }

dc_init() {
  local cont="${1}" idx="${2}"
  dc down --remove-orphans -t1
  dc up -d --force-recreate "${@}"
  while ! dc logs network | grep "All links connected"; do
    vecho "waiting for conlink startup"
    sleep 1
  done
}

dc_wait() {
  local tries="${1}" cont="${2}" try=1 svc= idx= result=
  case "${cont}" in
      *_[0-9]|*_[0-9][0-9]) svc="${cont%_*}" idx="${cont##*_}" ;;
      *)                    svc="${cont}"    idx=1            ;;
  esac
  shift; shift

  #echo "target: ${1}, service: ${svc}, index: ${idx}"
  while true; do
    result=0
    if [ "${VERBOSE}" ]; then
      vecho "Running: dc exec -T --index ${idx} ${svc} sh -c ${*}"
      dc exec -T --index ${idx} ${svc} sh -c "${*}" || result=$?
    else
      dc exec -T --index ${idx} ${svc} sh -c "${*}" > /dev/null || result=$?
    fi
    [ "${result}" -eq 0 -o "${try}" -ge "${tries}" ] && break
    echo "    command failed (${result}), sleeping 2s before retry (${try}/${tries})"
    sleep 2
    try=$(( try + 1 ))
  done
  return ${result}
}

dc_test() {
  name="${TEST_NUM} ${GROUP}: ${@}"
  TEST_NUM=$(( TEST_NUM + 1 ))
  vecho "  > Running test: ${name}"
  dc_wait 1 "${@}"
  RESULTS["${name}"]=$?
  if [ "${RESULTS["${name}"]}" = 0 ]; then
    PASS=$(( PASS + 1 ))
    vecho "  > PASS (0 for ${*})"
  else
    FAIL=$(( FAIL + 1 ))
    echo "  > FAIL (${RESULTS[${name}]} for ${*})"
  fi
}


echo -e "\n\n>>> test1: combined config"
GROUP=test1
echo "COMPOSE_FILE=examples/test1-compose.yaml" > .env
dc_init || die "test1 startup failed"

echo " >> Ping nodes from other nodes"
dc_test h1 ping -c1 10.0.0.100
dc_test h2 ping -c1 192.168.1.100
dc_test h3 ping -c1 172.16.0.100

echo -e "\n\n>>> test2: separate config and scaling"
GROUP=test2
echo "COMPOSE_FILE=examples/test2-compose.yaml" > .env
dc_init || die "test2 startup failed"

echo " >> Cross-node ping and ping the 'internet'"
dc_test node_1 ping -c1 10.0.1.2
dc_test node_2 ping -c1 10.0.1.1
dc_test node_1 ping -c1 8.8.8.8
dc_test node_2 ping -c1 8.8.8.8

echo " >> Scale the nodes from 2 to 5"
dc up -d --scale node=5
dc_wait 10 node_5 'ip addr | grep "10\.0\.1\.5"' || die "test2 scale-up failed"
echo " >> Ping the fifth node from the second"
dc_test node_2 ping -c1 10.0.1.5


echo -e "\n\n>>> test4: multiple compose / mdc"
GROUP=test4
export MODES_DIR=./examples/test4-multiple/modes

mdc node1
dc_init; dc_wait 10 r0_1 'ip addr | grep "10\.1\.0\.100"' \
    || die "test4 node1 startup failed"
echo " >> Ping the r0 router host from node1"
dc_test node1_1 ping -c1 10.0.0.100

mdc node1,nodes2
dc_init; dc_wait 10 node2_2 'ip addr | grep "10\.2\.0\.2"' \
    || die "test4 node1,nodes2 startup failed"
echo " >> From both node2 replicas, ping node1 across the r0 router"
dc_test node2_1 ping -c1 10.1.0.1
dc_test node2_2 ping -c1 10.1.0.1
echo " >> From node1, ping both node2 replicas across the r0 router"
dc_test node1 ping -c1 10.2.0.1
dc_test node1 ping -c1 10.2.0.2

mdc all
dc_init; dc exec -T r0 /scripts/wait.sh -t 10.0.0.100:80 \
    || die "test4 all startup failed"
echo " >> From node2, download from the web server in r0"
dc_test node2_1 wget -O- 10.0.0.100
dc_test node2_2 wget -O- 10.0.0.100


echo -e "\n\n>>> test7: MAC, MTU, and NetEm settings"
GROUP=test7
echo "COMPOSE_FILE=examples/test7-compose.yaml" > .env

dc_init; dc_wait 10 node_1 'ip addr | grep "10\.0\.1\.1"' \
    || die "test7 startup failed"
echo " >> Ensure MAC and MTU are set correctly"
dc_test node_1 'ip link show eth0 | grep "ether 00:0a:0b:0c:0d:01"'
dc_test node_2 'ip link show eth0 | grep "ether 00:0a:0b:0c:0d:02"'
dc_test node_1 'ip link show eth0 | grep "mtu 4111"'
dc_test node_2 'ip link show eth0 | grep "mtu 4111"'
echo " >> Check for round-trip ping delay of 80ms"
dc_test node_1 'ping -c2 10.0.1.2 | tail -n1 | grep "max = 80\."'


echo -e "\n\n>>> Cleaning up"
dc down -t1 --remove-orphans
rm -f .env

if [ "${VERBOSE}" ]; then
  for t in "${!RESULTS[@]}"; do
    echo "RESULT: '${t}' -> ${RESULTS[${t}]}"
  done
fi

if [ "${FAIL}" = 0 ]; then
  echo -e "\n\n>>> ALL ${PASS} TESTS PASSED"
  exit 0
else
  echo -e "\n\n>>> ${FAIL} TESTS FAILED, ${PASS} TESTS PASSED"
  exit 1
fi

