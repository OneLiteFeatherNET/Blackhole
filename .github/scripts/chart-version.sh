#!/usr/bin/env bash
# Computes the versions the publish workflow uses, from the project version, the commit time and the commit.
#
#   chart-version.sh <project version> <commit unix time> <commit sha>
#
# Prints key=value lines (the format of $GITHUB_OUTPUT):
#   release_version  X.Y.Z                      the release the project version leads to (SNAPSHOT/BETA/ALPHA dropped)
#   dev_version      X.Y.Z-main.<unix time>     the dev chart version; unique per commit and semver-ordered, so Flux,
#                                               which caches charts by version, always sees a new one
#   image_tag        main-<unix time>-<sha7>    the dev image tag; Flux image automation orders it numerically with
#                                               ^main-(?P<ts>[0-9]+)-[0-9a-f]{7}$
#
# The dev version is the *upcoming* release as a pre-release: for 1.0.1-SNAPSHOT it is 1.0.1-main.<ts> (below
# 1.0.1, above 1.0.0); for a plain 1.2.0 on main, whose next version is not known yet, it is 1.2.1-main.<ts>.
# The unix time is a numeric semver identifier, so main.9 sorts below main.10.
#
# The commit time (git show -s --format=%ct) is passed in, never read from the clock, so the result is the
# same for every component built from one commit and can be tested.

set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <project version> <commit unix time> <commit sha>" >&2
  exit 1
fi

version="$1"
timestamp="$2"
sha="$3"

if [[ ! "${version}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-(SNAPSHOT|BETA|ALPHA)[0-9A-Za-z.]*)?$ ]]; then
  echo "not a project version (X.Y.Z or X.Y.Z-SNAPSHOT): '${version}'" >&2
  exit 1
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
prerelease="${BASH_REMATCH[4]}"

if [[ ! "${timestamp}" =~ ^[0-9]+$ ]]; then
  echo "not a unix time: '${timestamp}'" >&2
  exit 1
fi
if [[ ! "${sha}" =~ ^[0-9a-f]{7,40}$ ]]; then
  echo "not a commit sha (7 to 40 lower-case hex characters): '${sha}'" >&2
  exit 1
fi

if [[ -n "${prerelease}" ]]; then
  dev_patch="${patch}"
else
  dev_patch=$((patch + 1))
fi

echo "release_version=${major}.${minor}.${patch}"
echo "dev_version=${major}.${minor}.${dev_patch}-main.${timestamp}"
echo "image_tag=main-${timestamp}-${sha:0:7}"
