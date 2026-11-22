# Synergy V100 profiles

Throughput profiles converted from Synergy's simulator (Mohan et al., *Looking Beyond GPUs for DNN
Scheduling on Multi-Tenant Clusters*, OSDI '22), repository
[msr-fiddle/synergy](https://github.com/msr-fiddle/synergy), commit `1bb2bed`, MIT License,
Copyright (c) Microsoft Corporation.

Produced with:

```bash
tools/.venv/bin/python tools/convert_synergy_profiles.py --synergy-repo ../synergy/repo \
    -o presets/profiles/synergy-v100 --report presets/profiles/synergy-v100/fair-share-exceptions.md
```

| Folder | Synergy source | Cluster it belongs to |
|---|---|---|
| `models/` | `simulator/models` | 24 CPUs, 8 GPUs per server (default) |
| `models-4/`, `models-5/`, `models-6/` | `simulator/models-4/5/6` | 32, 40, 48 CPUs per server (Fig. 12) |

Format: `docs/formats.md`. Only `<model>_1.json` files are used by Synergy's simulator
(`used_by_synergy: true`).

Known points:
- In `models-4/5/6` the throughput table is the same as in `models/` (1.0 at 3 cores per GPU); only
  `ideal_speedup` changes, because it is stated relative to that cluster's fixed share (4, 5, 6 cores
  per GPU). The simulator normalises to the server's own fixed share, which reproduces these values.
  The `fair_share` block in these three folders still says 3 cores per GPU; that is a converter issue
  (being fixed) and the simulator does not read that block.
- `models-6/alexnet_1.json` and `models-6/shufflenet_1.json` have a changed `ideal_speedup` and
  `iter_time` but an unchanged table, so the table and `ideal_speedup` disagree there. Use
  `throughput_mode: synergy_two_point` to reproduce Synergy exactly.
- In the multi-GPU files, all-zero rows at high core counts mean "not measured".
