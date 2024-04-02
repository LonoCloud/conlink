#!/usr/bin/env bash

export VERBOSE=${VERBOSE:-}
export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-conlink-test}
export DOCKER_COMPOSE=${DOCKER_COMPOSE:-docker-compose}
declare TEST_NUM=0
declare -A RESULTS
declare PASS=0
declare FAIL=0

die() { echo >&2 "${*}"; exit 1; }
vecho() { [ "${VERBOSE}" ] && echo "${*}" || true; }
dc() { ${DOCKER_COMPOSE} "${@}"; }
mdc() { ./mdc "${@}" || die "mdc invocation failed"; }

dc_init() {
  local cont="${1}" idx="${2}"
  dc down --remove-orphans -t1
  dc up -d --force-recreate "${@}"
  while ! dc logs network | grep "All links connected"; do
    vecho "log output:"
    [ "${VERBOSE}" ] && sleep 1 && dc logs network
    vecho "waiting for conlink startup"
    sleep 9
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


echo -e "\n\n>>> test9: patch test"
GROUP=test7
echo "COMPOSE_FILE=examples/test9-compose.yaml" > .env

dc_init; dc_wait 10 Npatch_1 'ip addr | grep "10\.0\.5\.1"' \
    || die "test9 startup failed"
echo " >> Ensure ingest filter rules exist"
dc_test network 'tc filter show dev Npatch_1-eth0 parent ffff: | grep "action order 1: mirred"'
dc_test network 'tc filter show dev Npatch_2-eth0 parent ffff: | grep "action order 1: mirred"'
echo " >> Check for round-trip connectivity"
dc_test Npatch_1 'ping -c2 10.0.5.2'
dc_test Npatch_2 'ping -c2 10.0.5.1'

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

