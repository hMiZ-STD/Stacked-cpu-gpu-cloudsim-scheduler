#!/usr/bin/env bash
# Run Synergy's simulator for ONE configuration and keep its recorded job trace and exact JCTs.
#
#   SYNERGY_SIM=<synergy repo>/simulator  WORK=<scratch dir>  PY=<python with Synergy's deps>  \
#       run_synergy.sh <name> <scheduler e.g. FIFO+tune> <jobs/hr> <split e.g. 20,70,10> <cpus/server 24|32|40|48> [--multigpu]
#
# Synergy's code is copied to $WORK/runs/<name>/simulator first; the repository checkout is never modified.
# Compatibility changes for newer Python (none touch scheduling): collections.Mapping -> collections.abc.Mapping,
# matplotlib basex -> base, gRPC stubs generated from the .proto files (as deployment/Makefile does), a stand-in
# `numa` module (only physical deployment uses NUMA). Needs: numpy pandas matplotlib psutil cvxpy grpcio grpcio-tools protobuf.
set -euo pipefail
: "${SYNERGY_SIM:?path to synergy/simulator}" "${WORK:?scratch directory}" "${PY:?python with Synergy's dependencies}"
NAME=$1 SCHED=$2 JPH=$3 SPLIT=$4 CPUS=$5 EXTRA=${6:-}
D=$WORK/runs/$NAME
rm -rf "$D"; mkdir -p "$D"
cp -r "$SYNERGY_SIM" "$D/simulator"
S=$D/simulator
cat > "$S/numa.py" <<'PYEOF'
"""Stand-in for libnuma: only physical deployment uses NUMA; simulation must never call it."""
def _unused(*a, **k):
    raise RuntimeError("numa stand-in called")
available = get_max_node = node_to_cpus = _unused
PYEOF
(cd "$S/deployment" && for p in scheduler_to_worker worker_to_scheduler job_to_scheduler common; do
   "$PY" -m grpc_tools.protoc -Iruntime/protobuf --python_out=runtime/rpc_stubs --grpc_python_out=runtime/rpc_stubs runtime/protobuf/$p.proto 2>/dev/null; done)
for f in "$S/resources/cluster.py" "$S/jobs/job.py"; do
  sed -i 's/collections\.Mapping/collections.abc.Mapping/g; 0,/^import collections$/s//import collections\nimport collections.abc/' "$f"
done
sed -i 's/basex=2/base=2/g' "$S/metrics/stats.py"
# the configuration: one scheduler, one load, one split (runner.py lines 393-394, 405, 408)
sed -i "393s/.*/    schedulers = ['$SCHED']/; 394s/.*/    scheduler_name = ['$NAME']/" "$S/runner.py"
sed -i "405s/.*/    jobs_per_hours = [$JPH]/; 408s/.*/    class_split=[($SPLIT)]/" "$S/runner.py"
CFG=()
if [ "$CPUS" != 24 ]; then
  k=$((CPUS / 8))
  (cd "$S" && mv models models-3 && mv "models-$k" models)
  CFG=(--config_file "configs/cluster_$k.ini")
fi
mkdir -p "$S/cache"
cd "$S"
start=$SECONDS
# the plotting step at the end may fail on a new matplotlib; the results are written before it
"$PY" runner.py --cluster_job_log trace/cluster_job_log --no_use_cache \
    --record_trace --rec_trace ./record "${CFG[@]}" $EXTRA > out.log 2>&1 || true
echo "$NAME: $((SECONDS - start)) s; $(grep -o 'Terminating workload at [0-9.]* hrs.*' out.log | tail -1)"
ls "$S"/record* "$S"/cache/stats_* >/dev/null
