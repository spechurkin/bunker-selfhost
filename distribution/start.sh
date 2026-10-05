#!/bin/sh
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$app_dir/bunker-server.jar" "$@"
