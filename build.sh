#!/usr/bin/env bash
set -euo pipefail

if ! command -v java >/dev/null 2>&1; then
  echo "build.sh: Java 25 is required but java was not found on PATH" >&2
  exit 1
fi

java_major="$(java -version 2>&1 | awk -F '\"' '/version/ { split($2, parts, "."); print parts[1]; exit }')"
if [[ "$java_major" != "25" ]]; then
  echo "build.sh: Java 25 is required (found ${java_major:-unknown})" >&2
  exit 1
fi

command_name="${1:-build}"
if (($# > 0)); then
  shift
fi
exec java tools/UmbBuild.java "$command_name" "$@"
