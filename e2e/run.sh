#!/bin/sh
# end-to-end tests: builds the image, starts two servers and drives headless chromium through
# pairing, messaging and calls on one server and across both.
# usage: e2e/run.sh (screenshots are written to e2e/out/)
set -e
cd "$(dirname "$0")/.."
PW=mcr.microsoft.com/playwright:v1.56.0-noble

docker build -q -t caw:e2e . >/dev/null
docker rm -f caw-e2e caw-e2e2 caw-e2e-pw >/dev/null 2>&1 || true
docker run -d --name caw-e2e -e CAW_PUBLIC_URL=http://localhost:8080 caw:e2e >/dev/null
# second server in the same netns; localhost:8081 is a separate origin to the browser
docker run -d --name caw-e2e2 --network container:caw-e2e -e CAW_BIND=0.0.0.0:8081 -e CAW_PUBLIC_URL=http://localhost:8081 caw:e2e >/dev/null
trap 'docker rm -f caw-e2e caw-e2e2 caw-e2e-pw >/dev/null 2>&1' EXIT
sleep 2
SETUP=$(docker logs caw-e2e 2>&1 | grep -o 'invite/[A-Za-z0-9_-]*' | cut -d/ -f2)
SETUP2=$(docker logs caw-e2e2 2>&1 | grep -o 'invite/[A-Za-z0-9_-]*' | cut -d/ -f2)

# shares the caw network namespace so the page is on localhost, a secure context
rc=0
docker run -i --name caw-e2e-pw --network container:caw-e2e -e SETUP="$SETUP" -e SETUP2="$SETUP2" -e DEBUG="$DEBUG" -v "$PWD/testdata/media:/media:ro,z" -w /t $PW \
  sh -c 'cat > call.mjs && npm i -s playwright@1.56.0 >/dev/null 2>&1 && node call.mjs' < e2e/call.mjs || rc=$?
rm -rf e2e/out && docker cp caw-e2e-pw:/out e2e/out >/dev/null 2>&1 || true
exit $rc
