#!/usr/bin/env sh
set -eu
if ! command -v java >/dev/null 2>&1; then echo 'Java 25 is required' >&2; exit 2; fi
case "$(java -version 2>&1)" in *'25.'*|*'25-ea'*) ;; *) echo 'Java 25 is required (check java -version)' >&2; exit 2;; esac
exec java tools/UmbBuild.java "${1:-build}"
