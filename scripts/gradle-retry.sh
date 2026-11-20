#!/usr/bin/env bash
# Runs a Gradle command and re-runs it, up to GRADLE_RETRY_ATTEMPTS times (default 3, with a growing pause), ONLY when
# the failure looks like a transient network miss while resolving dependencies or fetching the Gradle distribution
# (a 404/5xx/timeout from Maven Central or the plugin portal; seen on CI as `Could not find io.ktor:ktor-version-catalog:3.6.1`,
# checkup 1.0.0 C-GATE-1). Any other failure (a compile error, a failing test, a build-script error such as `Could not find
# method`, a verification or strict-lock violation) is final on the first attempt, so a real red is never slowed down or
# re-rolled.
#
#   scripts/gradle-retry.sh ./gradlew --no-daemon :server:installDist
#
# It never loosens anything: the retried command is exactly the one given (strict locking and dependency verification
# stay on; no --offline, no --refresh-dependencies), and `Dependency verification failed` or a strict-lock violation
# (`Resolved '...' which is not part of the dependency lock state`, a lock state out of date) is never retried.
#
# Why metadata is dropped between attempts: Gradle caches a MISSING module (a 404) in the metadata cache and trusts it for
# 24 hours, so a bare re-run in the same GRADLE_USER_HOME would read the cached miss and fail again without asking the
# repository. Deleting `caches/modules-2/metadata-*` forgets the misses. It also drops the artifact index, so already
# downloaded jars MAY be fetched again on the next attempt; every resolved artifact is re-verified against
# gradle/verification-metadata.xml either way. A command that runs `gradlew` must pass --no-daemon (a daemon would hold the
# deleted metadata open): the script refuses otherwise.
set -uo pipefail

attempts="${GRADLE_RETRY_ATTEMPTS:-3}"
pause="${GRADLE_RETRY_PAUSE_SECONDS:-15}"

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/gradle-retry.sh <gradle command...>" >&2
  exit 2
fi
if [[ "$*" == *gradle* && "$*" != *--no-daemon* ]]; then
  echo "gradle-retry: a gradle command must pass --no-daemon (the retry deletes the metadata cache a daemon would hold open)" >&2
  exit 2
fi

# Transient-network signatures: a failed HTTP fetch, a miss on a full group:artifact:version coordinate (the 404 Maven Central
# answered transiently), HTTP 5xx, resets/timeouts, DNS, and the wrapper's distribution download. Deliberately NOT a bare
# "Could not find"/"Could not resolve", which also start build-script errors.
transient='Could not (GET|HEAD|download) |Could not find [A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+|Read timed out|Connect timed out|Connection (reset|refused|timed out)|Remote host terminated|Premature end of Content-Length|Received status code 5[0-9][0-9]|Server returned HTTP response code: 5|Unknown host|Temporary failure in name resolution|java\.net\.(SocketException|SocketTimeoutException|UnknownHostException|ConnectException)|javax\.net\.ssl\.SSLException'
# Never retried, even next to a transient signature. (`Did not resolve ... which is part of the dependency lock state` is NOT here
# on purpose: it is what the real transient miss prints beside `Could not find`.)
final='Dependency verification failed|not part of the dependency lock state|[Ll]ock state (is )?out of date|Invalid lock state'

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

n=1
while true; do
  "$@" 2>&1 | tee "$log"
  code="${PIPESTATUS[0]}"
  if [ "$code" -eq 0 ]; then
    exit 0
  fi
  if [ "$n" -ge "$attempts" ] || grep -Eq "$final" "$log" || ! grep -Eq "$transient" "$log"; then
    exit "$code"
  fi
  wait_for=$((pause * n))
  echo "::warning::gradle-retry: attempt $n of $attempts failed on a transient dependency-resolution error; retrying in ${wait_for}s" >&2
  find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -maxdepth 1 -name 'metadata-*' -exec rm -rf {} + 2> /dev/null || true
  sleep "$wait_for"
  n=$((n + 1))
done
