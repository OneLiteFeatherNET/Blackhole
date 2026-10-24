#!/usr/bin/env bash
# Installs the pinned chart tools into a directory (never system-wide), verifying the SHA-256 of every download.
#
#   install-chart-tools.sh <directory> [tool...]      tools: helm yq kubeconform (default: all)
#
# The versions are pinned here once; the PR workflow and the release workflow both call this script, so the chart
# that ships is packaged by the exact Helm that linted and rendered it. Each version is at least two weeks old
# when it is bumped, and a bump changes version and checksum together (take the checksum from the release's own
# checksum file, never from the download that is being verified).
#
# Helm v4 is needed (not only preferred): the chart tests grep schema errors for JSON-Pointer paths such as
# /database/existingSecret, which Helm only prints since it validates with jsonschema/v6.

set -euo pipefail

HELM_VERSION="v4.3.0"
HELM_SHA256="86584a54def73570558f66f5111cc53dfed56689637ae32c1201205d494f54fb"
YQ_VERSION="v4.53.6"
YQ_SHA256="c5f056448f973ae7d39b5401949648a78f2dc1947d6a8eb65be60d5c504b9385"
KUBECONFORM_VERSION="v0.8.0"
KUBECONFORM_SHA256="9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <directory> [helm|yq|kubeconform...]" >&2
  exit 1
fi
dest="$1"
shift
tools=("$@")
if [[ ${#tools[@]} -eq 0 ]]; then
  tools=(helm yq kubeconform)
fi

mkdir -p "${dest}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

fetch() { # <url> <sha256> <file>
  curl -sSfL --retry 3 "$1" -o "${work}/$3"
  echo "$2  ${work}/$3" | sha256sum --check --status || {
    echo "checksum mismatch for $1" >&2
    exit 1
  }
}

for tool in "${tools[@]}"; do
  case "${tool}" in
    helm)
      fetch "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" "${HELM_SHA256}" helm.tgz
      tar -xzf "${work}/helm.tgz" -C "${dest}" --strip-components=1 linux-amd64/helm
      ;;
    yq)
      fetch "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64" "${YQ_SHA256}" yq
      install -m 0755 "${work}/yq" "${dest}/yq"
      ;;
    kubeconform)
      fetch "https://github.com/yannh/kubeconform/releases/download/${KUBECONFORM_VERSION}/kubeconform-linux-amd64.tar.gz" "${KUBECONFORM_SHA256}" kubeconform.tgz
      tar -xzf "${work}/kubeconform.tgz" -C "${dest}" kubeconform
      ;;
    *)
      echo "unknown tool: ${tool}" >&2
      exit 1
      ;;
  esac
done
