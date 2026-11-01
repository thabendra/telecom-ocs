#!/usr/bin/env bash
# TE-4: one full run of all three modules. Passes only with 0 failures and 0 skipped: Testcontainers skips a test
# class without a word when Docker isn't there, and a green build would then mean nothing. TE-5: the backend's build
# also fails if a business package is under its coverage minimum; the report is in target/site/jacoco/index.html.
#
# Usage: scripts/test-all.sh   (from the project root; needs Docker and scripts/generate-dev-certs.sh)
# COVERAGE=off still writes the report, but doesn't fail on the minimum.
# CHECK_ONLY=1 runs nothing: it only reads the reports of the last run.
set -uo pipefail

FAILED=0

docker run --rm hello-world >/dev/null 2>&1 || { echo "Docker can't run a container: nothing would really be tested" >&2; exit 2; }

# check_module <directory>: the module's reports say nothing was skipped, and every test class really ran
check_module() {
  local dir="$1" tests=0 skipped=0 failures=0 errors=0 line missing=0
  for report in "$dir"/target/surefire-reports/*.txt; do
    [ -e "$report" ] || continue
    line="$(grep -m1 'Tests run:' "$report")"
    tests=$((tests + $(echo "$line" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')))
    failures=$((failures + $(echo "$line" | sed -E 's/.*Failures: ([0-9]+).*/\1/')))
    errors=$((errors + $(echo "$line" | sed -E 's/.*Errors: ([0-9]+).*/\1/')))
    skipped=$((skipped + $(echo "$line" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')))
  done
  while read -r source; do
    grep -qE '@(Test|ParameterizedTest)([^A-Za-z]|$)' "$source" || continue   # e.g. the @IntegrationTest annotation itself
    class="$(basename "$source" .java)"
    if ! ls "$dir"/target/surefire-reports/*."$class".txt >/dev/null 2>&1; then
      echo "FAIL  $dir: $class did not run"
      missing=$((missing + 1))
    fi
  done < <(find "$dir/src/test/java" -name '*Test.java')
  echo "$dir: $tests tests, $failures failures, $errors errors, $skipped skipped, $missing classes that did not run"
  [ "$tests" -gt 0 ] && [ "$failures" = 0 ] && [ "$errors" = 0 ] && [ "$skipped" = 0 ] && [ "$missing" = 0 ] || FAILED=1
}

JACOCO=()
[ "${COVERAGE:-on}" = off ] && JACOCO=(-Djacoco.haltOnFailure=false)

if [ -z "${CHECK_ONLY:-}" ]; then
  mvn -B clean verify "${JACOCO[@]}" || FAILED=1
fi
check_module .
if [ -z "${CHECK_ONLY:-}" ]; then
  mvn -B -f gateway-simulator/pom.xml clean verify || FAILED=1
fi
check_module gateway-simulator
if [ -z "${CHECK_ONLY:-}" ]; then
  mvn -B -f token-server/pom.xml clean verify || FAILED=1
fi
check_module token-server

[ "$FAILED" = 0 ] && echo "All three modules passed, nothing skipped." || echo "NOT all passed: see above."
exit "$FAILED"
