#!/usr/bin/env bash
# What to look at after merging upstream, and what to check in the run after.
#
# A zero-conflict merge is not a zero-regression merge. Upstream renames a
# function, adds a new one under the old name, and this branch's adaptation
# rides into the renamed copy where nothing calls it any more. Git reports
# nothing, the build passes, and the screen goes black at runtime.
#
# This branch runs its own Wayland host, so upstream work aimed at the AWT/X11
# bridge or the webview player does not reach it by merging. Anything the host
# is meant to have -- a new player option, a control, a UI affordance -- has to
# be ported across explicitly. Merging is the start of that, not the end.
#
#   scripts/audit-merge.sh <merge-commit>        # defaults to HEAD
#
# Exits non-zero when something needs a human decision.
set -uo pipefail

# Verdict on a run's log: did the UI actually draw, and did playback happen.
if [ "${1:-}" = "--check-log" ]; then
    log=${2:?usage: audit-merge.sh --check-log <logfile>}
    fail=0
    errs=$(grep -oE 'errs=[0-9]+' "${log}" | cut -d= -f2 | sort -rn | head -1)
    errs=${errs:-0}
    if [ "${errs}" -gt 0 ]; then
        echo "FAIL: ${errs} scene render failures -- the UI was not drawing"
        grep -m1 -A6 'scene.render failed' "${log}" | sed 's|^|   |'
        fail=1
    else
        echo "ok: no scene render failures"
    fi
    if grep -q 'hasFile=true' "${log}"; then
        echo "ok: playback was reached"
    else
        echo "WARN: no stream ever played -- the player path is unverified"
    fi
    for screen in MetaDetailsRepo Scraper; do
        grep -q "${screen}" "${log}" \
            && echo "ok: ${screen} exercised" \
            || echo "WARN: ${screen} never appeared -- that path is unverified"
    done
    exit "${fail}"
fi

merge=${1:-HEAD}
before=$(git rev-parse "${merge}^1") || exit 1
after=$(git rev-parse "${merge}") || exit 1
base=$(git merge-base "${before}" "$(git rev-parse "${merge}^2")") || exit 1
status=0

echo "auditing ${merge}: ${before}..${after}"
echo

echo "== files this branch had customised AND the merge changed =="
echo "   (each one can silently lose a branch adaptation)"
risk=0
for f in $(git diff --name-only "${before}" "${after}"); do
    if ! git diff --quiet "${base}" "${before}" -- "${f}" 2>/dev/null; then
        echo "   ${f}"
        risk=$((risk + 1))
    fi
done
[ "${risk}" -eq 0 ] && echo "   (none)"
echo

echo "== upstream functions added or renamed in those files =="
echo "   (an adaptation in the old name does not apply to the new one)"
for f in $(git diff --name-only "${before}" "${after}" -- '*.kt'); do
    git diff "${before}" "${after}" -- "${f}" \
        | grep -E '^\+(internal |private |public )?fun [A-Za-z]' \
        | sed "s|^+|   ${f}: |"
done
echo

echo "== upstream work the host does not get for free =="
echo "   changes to the AWT/X11 bridge or the webview player never reach the"
echo "   Wayland host by merging; port them across by hand or they are missing"
for f in $(git diff --name-only "${before}" "${after}"); do
    case "${f}" in
        */native/*|*/player-ui/*|*desktop/NativePlayerController.kt)
            echo "   ${f}" ;;
    esac
done
echo

echo "== desktop-path conventions in newly added code =="
echo "   haze blur is unusable on this branch's Compose; desktop uses"
echo "   nuvioBackdropEffect. Raw Coil AsyncImage resizes during decode."
# Herestring, not a pipe: `grep -q` exits on the first match, the writer takes
# SIGPIPE, and pipefail reports the pipeline as failed -- so the check silently
# never fires. A guard that cannot fail is worse than no guard.
added=$(git diff "${before}" "${after}")
if grep -qE '^\+.*hazeEffect\(' <<< "${added}"; then
    echo "   ADDED hazeEffect( -- confirm every desktop-reachable path is guarded:"
    git diff "${before}" "${after}" --name-only \
        | xargs -r grep -ln 'hazeEffect(' 2>/dev/null | sed 's|^|     |'
    status=1
fi
if grep -qE '^\+import coil3\.compose\.AsyncImage' <<< "${added}"; then
    echo "   ADDED raw coil AsyncImage import -- confirm desktop uses NuvioAsyncImage:"
    git diff "${before}" "${after}" --name-only \
        | xargs -r grep -ln 'import coil3.compose.AsyncImage' 2>/dev/null | sed 's|^|     |'
    status=1
fi
[ "${status}" -eq 0 ] && echo "   (nothing new)"
echo

cat <<'EOF'
== then run it, and read the health line ==
   Compiling is not evidence. A scene that throws leaves the host alive and
   paints black, so the only proof is a run that reaches the screens the merge
   touched.

   LD_PRELOAD=/tmp/libglxshim.so ./gradlew --console=plain :waylandHost:run \
     -Pnuvio.wayland.realApp=true -Pnuvio.wayland.videoLog=true \
     -Pnuvio.wayland.libmpv=/home/annihilator/dev/mpv/build/libmpv.so.2 \
     2>&1 | tee /tmp/nuvio-merge-check.log

   Walk home -> details -> episode -> streams -> play, then:

   scripts/audit-merge.sh --check-log /tmp/nuvio-merge-check.log
EOF
exit "${status}"
