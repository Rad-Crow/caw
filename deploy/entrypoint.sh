#!/bin/sh
# bind-mounted /data is root-owned; chown it, then drop to the caw user
set -e
if [ "$(id -u)" = 0 ]; then
  chown -R caw:caw /data
  exec setpriv --reuid=caw --regid=caw --init-groups "$@"
fi
exec "$@"
