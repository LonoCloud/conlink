#!/usr/bin/env bash

export VERBOSE=${VERBOSE:-}
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-conlink-test}
declare TEST_NUM=0
declare -A RESULTS
declare PASS=0
declare FAIL=0

die() { echo >&2 "${*}"; exit 1; }
vecho() { [ "${VERBOSE}" ] && echo "${*}" || true; }
dc() { ${DOCKER_COMPOSE} "${@}"; }
mdc() { ./mdc "${@}" || die "mdc invocation failed"; }

# Determine compose command
for dc in "docker compose" "docker-compose"; do
  ${dc} version 2>/dev/null >&2 && DOCKER_COMPOSE="${dc}" && break
done
[ "${DOCKER_COMPOSE}" ] || die "No compose command found"
echo >&2 "Using compose command '${DOCKER_COMPOSE}'"

dc_init() {
  local cont="${1}" idx="${2}"
  dc down --remove-orphans -t1
  dc up -d --force-recreate "${@}"
  while ! dc logs network | grep "All links connected"; do
    vecho "waiting for conlink startup"
    sleep 1
  done
}

dc_run() {
  local cont="${1}" svc= idx= result=0
  case "${cont}" in
      *_[0-9]|*_[0-9][0-9]) svc="${cont%_*}" idx="${cont##*_}" ;;
      *)                    svc="${cont}"    idx=1            ;;
  esac
  shift

  #echo "target: ${1}, service: ${svc}, index: ${idx}"
  if [ "${VERBOSE}" ]; then
    vecho "    Running: dc exec -T --index ${idx} ${svc} sh -c ${*}"
    dc exec -T --index ${idx} ${svc} sh -c "${*}" || result=$?
  else
    dc exec -T --index ${idx} ${svc} sh -c "${*}" > /dev/null || result=$?
  fi
  return ${result}
}

do_test() {
  local tries="${1}"; shift
  local name="${TEST_NUM} ${GROUP}${SUBGROUP}: ${@}" try=1 result=
  TEST_NUM=$(( TEST_NUM + 1 ))
  vecho "  > Running test ${name}"
  while true; do
    result=0
    if [ "${WITH_DC}" ]; then
      dc_run "${@}" || result=$?
    else
      vecho "    Running: eval ${*}"
      if [ "${VERBOSE}" ]; then
        sh -c "${*}" || result=$?
      else
        sh -c "${*}" >/dev/null || result=$?
      fi
    fi
    [ "${result}" -eq 0 -o "${try}" -ge "${tries}" ] && break
    echo "    command failed (${result}), sleeping 2s before retry (${try}/${tries})"
    sleep 2
    try=$(( try + 1 ))
  done
  RESULTS["${name}"]=${result}
  if [ "${RESULTS["${name}"]}" = 0 ]; then
    PASS=$(( PASS + 1 ))
    vecho "  > PASS (0 for ${*})"
  else
    FAIL=$(( FAIL + 1 ))
    echo "  > FAIL (${RESULTS[${name}]} for ${*})"
  fi
  return ${result}
}

dc_wait() { WITH_DC=1 do_test "${@}"; }
dc_test() { WITH_DC=1 do_test 1 "${@}"; }

do_test1() {
  echo -e "\n\n>>> test1: combined config"
  echo "COMPOSE_FILE=examples/test1-compose.yaml" > .env
  dc_init || die "test1 startup failed"

  echo " >> Ping nodes from other nodes"
  dc_test h1 ping -c1 10.0.0.100
  dc_test h2 ping -c1 192.168.1.100
  dc_test h3 ping -c1 172.16.0.100
}

do_test2() {
  echo -e "\n\n>>> test2: separate config and scaling"
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
}

do_test4() {
  echo -e "\n\n>>> test4: multiple compose / mdc"
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
}

do_test7() {
  echo -e "\n\n>>> test7: MAC, MTU, and NetEm settings"
  echo "COMPOSE_FILE=examples/test7-compose.yaml" > .env

  dc_init; dc_wait 10 node_1 'ip addr | grep "10\.0\.1\.1"' \
      || die "test7 startup failed"
  echo " >> Ensure MAC and MTU are set correctly"
  dc_test node_1 'ip link show eth0 | grep "ether 00:0a:0b:0c:0d:01"'
  dc_test node_2 'ip link show eth0 | grep "ether 00:0a:0b:0c:0d:02"'
  dc_test node_1 'ip link show eth0 | grep "mtu 4111"'
  dc_test node_2 'ip link show eth0 | grep "mtu 4111"'
  echo " >> Check for min round-trip ping delay of about 80ms"
  dc_test node_1 'ping -c5 10.0.1.2 | grep "min/avg/max = 8[012345]\."'
}

do_test9() {
  echo -e "\n\n>>> test9: bridge modes and variable templating"
  echo "COMPOSE_FILE=examples/test9-compose.yaml" > .env

  echo -e "\n\n >> test9: bridge mode: auto"
  SUBGROUP=-auto
  export BRIDGE_MODE=auto
  dc_init; dc_wait 10 node_1 'ip addr | grep "10\.0\.1\.1"' \
      || die "test9 (auto) startup failed"
  echo " >> Check for round-trip ping connectivity (BRIDGE_MODE=auto)"
  dc_test node_1 'ping -c2 10.0.1.2'

  echo -e "\n\n >> test9: bridge mode: linux"
  SUBGROUP=-linux
  export BRIDGE_MODE=linux
  dc_init; dc_wait 10 node_1 'ip addr | grep "10\.0\.1\.1"' \
      || die "test9 (linux) startup failed"
  echo " >> Check for round-trip ping connectivity (BRIDGE_MODE=linux)"
  dc_test node_1 'ping -c2 10.0.1.2'

  echo -e "\n\n >> test9: bridge mode: patch"
  SUBGROUP=-patch
  export BRIDGE_MODE=patch
  dc_init; dc_wait 10 node_1 'ip addr | grep "10\.0\.1\.1"' \
      || die "test9 startup failed"
  echo " >> Ensure ingest filter rules exist (BRIDGE_MODE=patch)"
  dc_test network 'tc filter show dev node_1-eth0 parent ffff: | grep "action order 1: mirred"'
  echo " >> Check for round-trip ping connectivity (BRIDGE_MODE=patch)"
  dc_test node_1 'ping -c2 10.0.1.2'
}

do_test10() {
  echo -e "\n\n>>> test10: port forwarding"
  GROUP=test10
  echo "COMPOSE_FILE=examples/test10-compose.yaml" > .env

  dc_init; dc_wait 10 node1_1 'ip addr | grep "10\.1\.0\.1"' \
      || die "test10 startup failed"
  echo " >> Check ping between replicas"
  dc_test node2_1 'ping -c2 10.2.0.2'
  echo " >> Check pings across router"
  dc_test node1_1 'ping -c2 10.2.0.1'
  dc_test node1_1 'ping -c2 10.2.0.2'
  dc_test node2_1 'ping -c2 10.1.0.1'
  echo " >> Ensure ports are forwarded correctly"
  do_test 10 'curl -s -S "http://0.0.0.0:3080" | grep "log"'
  do_test 10 'curl -s -S "http://0.0.0.0:8080" | grep "log"'
  do_test 10 'curl -s -S "http://0.0.0.0:80" | grep "share"'
  do_test 10 'curl -s -S "http://0.0.0.0:81" | grep "share"'
}


ALL_TESTS="test1 test2 test4 test7 test9 test10"
TESTS="${*:-}"
case "${*}" in
  all|ALL|"") TESTS="${ALL_TESTS}" ;;
  *) TESTS="${*}" ;;
esac

for t in ${TESTS}; do
  GROUP="${t}" SUBGROUP=
  eval do_${t}
done

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

