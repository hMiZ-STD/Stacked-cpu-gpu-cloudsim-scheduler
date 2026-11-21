#!/usr/bin/env python3
"""Convert Synergy's per-model throughput profiles into one JSON file per model and GPU count.

ANSWER TO THE OPEN QUESTION (read from Synergy commit 1bb2bed, not guessed)
===========================================================================
Synergy indexes a job's ``tput`` matrix as ``tput[cpu_idx][mem_idx]`` (simulator/opt_algo/
synergy_opt.py, ``SolveFirstLP`` and ``allocate_round``). The two indexes come from the maps that
``Job.__init__`` sets (simulator/jobs/job.py)::

    cpu_val = {0:1, 1:2, 2:3, 3:4, 4:5, 5:6, 6:9, 7:12, 8:24}   # CPU cores PER GPU
    mem_val = {0:20.83, 1:62.5, 2:125, 3:187.5, 4:250}          # GB of DRAM PER GPU

* **Rows are CPU cores per GPU: 1, 2, 3, 4, 5, 6, 9, 12, 24.** The steps are not uniform. Row 6
  is 9 cores, which is why ResNet-18's 2.4x speedup (ideal 9 cores per GPU) starts at row index 6.
* **Columns are memory per GPU in GB: 20.83, 62.5, 125, 187.5, 250.** Both axes are per GPU.
  ``SolveFirstLP`` multiplies ``cpu_val`` and ``mem_val`` by ``job_gpu_demand`` to get the job's
  total. ``allocate_round`` divides the job's demand by ``job_gpu_demand`` before it looks up the
  index.
* The fair share on Synergy's server (24 CPUs, 500 GB, 8 GPUs) is 3 cores and 62.5 GB per GPU.
  That is cell ``[2][1]``. Synergy's fair-share constraint reads exactly
  ``tput[fair_cpu_idx][fair_mem_idx]``.

The 4-column files are older: every ``*_2.py`` and ``*_4.py`` file, plus ``shufflenet_12.py``.
They have no 20.83 GB column, so their columns are 62.5, 125, 187.5 and 250 GB per GPU. Two
things show this. The older ``cvxpy_test.py`` uses the map ``{0:62.5, 1:125, 2:187.5, 3:250}``.
And each file's columns match columns 1..4 of the matching ``*_1.py`` file. Their 9 rows still
use the 9-entry ``cpu_val`` above. Synergy's simulator never loads these files, because
``jobs/model.py`` ``use_scores_from_tput`` always loads ``<model>_1``. They are converted for
completeness and marked ``"used_by_synergy": false``.

simulator/tput.py has a different map (``{.., 6:12, 7:8}``) and its own hard-coded matrices.
Only ``events/schedule_event.py`` uses it, in deployment mode. The simulation never does, so it
is ignored here.

About ``mem_fractions``: these files give no dataset sizes. Here, "fraction of the dataset
cached" is memory per GPU divided by the largest profiled value, 250 GB per GPU, so 1.0 means
"fully cached". The raw GB values are kept in ``mem_gb_per_gpu``. The tool warns if a row still
rises between the last two columns, because then 1.0 would not mean "fully cached".

The Synergy files are never imported or run. Their source is parsed with ``ast``, and only plain
arithmetic, int(), float(), round() and np.array() are evaluated.
"""
from __future__ import annotations

import argparse
import ast
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

# --- Synergy's axes (see the module docstring) -------------------------------------------------
CPU_VAL = [1, 2, 3, 4, 5, 6, 9, 12, 24]
MEM_VAL_5 = [20.83, 62.5, 125.0, 187.5, 250.0]
MEM_VAL_4 = [62.5, 125.0, 187.5, 250.0]
FULL_MEM_GB = 250.0

# Synergy's server: 24 CPUs, 500 GB, 8 GPUs, so the fixed share is 3 cores and 62.5 GB per GPU.
FAIR_CPUS_PER_GPU = 3
FAIR_MEM_GB_PER_GPU = 62.5

GPU_TYPE = "V100"
MODEL_DIRS = ["models", "models-4", "models-5", "models-6"]
NON_MODEL_FILES = {"__init__.py", "model_stats.py"}

# ModelStats.__init__ defaults (simulator/models/model_stats.py), used when update_stats omits a field.
MODELSTATS_DEFAULTS = {
    "cpus": 3,
    "mem": 62.5,
    "speed": 62.5,
    "batch": 512,
    "placement_penalty": 1,
    "iter_time": 1,
    "speedup": 1,
}


class ProfileParseError(Exception):
    """A Synergy model file could not be parsed safely."""


# --- safe evaluation of the few expressions the model files use --------------------------------
_BINOPS = {
    ast.Add: lambda a, b: a + b,
    ast.Sub: lambda a, b: a - b,
    ast.Mult: lambda a, b: a * b,
    ast.Div: lambda a, b: a / b,
    ast.FloorDiv: lambda a, b: a // b,
    ast.Mod: lambda a, b: a % b,
    ast.Pow: lambda a, b: a ** b,
}
_SCALAR_CALLS = {"int": int, "float": float, "round": round}
_ARRAY_CALLS = {"np.array", "numpy.array", "np.asarray", "numpy.asarray"}


def _dotted_name(node: ast.AST) -> str | None:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        base = _dotted_name(node.value)
        return f"{base}.{node.attr}" if base else None
    return None


def safe_eval(node: ast.AST):
    """Evaluate a numeric literal, arithmetic, int/float/round, list, or np.array(list)."""
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) \
            and not isinstance(node.value, bool):
        return node.value
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        value = safe_eval(node.operand)
        return -value if isinstance(node.op, ast.USub) else value
    if isinstance(node, ast.BinOp) and type(node.op) in _BINOPS:
        return _BINOPS[type(node.op)](safe_eval(node.left), safe_eval(node.right))
    if isinstance(node, (ast.List, ast.Tuple)):
        return [safe_eval(e) for e in node.elts]
    if isinstance(node, ast.Call) and not node.keywords:
        name = _dotted_name(node.func)
        if name in _ARRAY_CALLS and len(node.args) == 1:
            return safe_eval(node.args[0])
        if name in _SCALAR_CALLS and 1 <= len(node.args) <= 2:
            return _SCALAR_CALLS[name](*[safe_eval(a) for a in node.args])
    raise ProfileParseError(f"unsupported expression: {ast.unparse(node)}")


# --- parsing one model file --------------------------------------------------------------------
@dataclass
class RawProfile:
    class_name: str
    gpu_count: int
    fields: dict = field(default_factory=dict)


def _self_attr_target(stmt: ast.stmt) -> tuple[str, ast.AST] | None:
    if isinstance(stmt, ast.Assign) and len(stmt.targets) == 1:
        target, value = stmt.targets[0], stmt.value
    elif isinstance(stmt, ast.AnnAssign) and stmt.value is not None:
        target, value = stmt.target, stmt.value
    else:
        return None
    if isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) \
            and target.value.id == "self":
        return target.attr, value
    return None


def _gpu_count_from_init(init: ast.FunctionDef) -> int | None:
    """Find ``super().__init__(name, N)`` and return N."""
    for node in ast.walk(init):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                and node.func.attr == "__init__" and isinstance(node.func.value, ast.Call) \
                and _dotted_name(node.func.value.func) == "super" and len(node.args) >= 2:
            return int(safe_eval(node.args[1]))
    return None


def parse_model_source(source: str, filename: str = "<model>") -> RawProfile:
    tree = ast.parse(source, filename=filename)
    classes = [n for n in tree.body if isinstance(n, ast.ClassDef)]
    if len(classes) != 1:
        raise ProfileParseError(f"{filename}: expected one class, found {len(classes)}")
    cls = classes[0]
    methods = {n.name: n for n in cls.body if isinstance(n, ast.FunctionDef)}
    if "update_stats" not in methods:
        raise ProfileParseError(f"{filename}: class {cls.name} has no update_stats")

    gpu_count = _gpu_count_from_init(methods["__init__"]) if "__init__" in methods else None
    if gpu_count is None:
        raise ProfileParseError(f"{filename}: cannot find super().__init__(name, gpus)")

    fields: dict = {}
    for stmt in methods["update_stats"].body:  # top-level statements only, in order
        hit = _self_attr_target(stmt)
        if hit is not None:
            name, value = hit
            fields[name] = safe_eval(value)  # a later assignment overrides an earlier one
    return RawProfile(class_name=cls.name, gpu_count=gpu_count, fields=fields)


# --- building the output object ----------------------------------------------------------------
def _num(x):
    """Return an int when the value is integral, so the JSON reads 9 and not 9.0."""
    return int(x) if float(x).is_integer() else float(x)


def build_profile(raw: RawProfile, source_rel: str, warnings: list[str]) -> dict:
    if "tput" not in raw.fields:
        raise ProfileParseError(f"{source_rel}: update_stats does not set self.tput")
    tput = raw.fields["tput"]
    if not tput or not all(isinstance(r, list) for r in tput):
        raise ProfileParseError(f"{source_rel}: tput is not a 2-D matrix")
    n_rows, n_cols = len(tput), len(tput[0])
    if any(len(r) != n_cols for r in tput):
        raise ProfileParseError(f"{source_rel}: tput rows have different lengths")
    if n_rows != len(CPU_VAL):
        raise ProfileParseError(
            f"{source_rel}: tput has {n_rows} rows, Synergy's cpu_val has {len(CPU_VAL)}")
    if n_cols == 5:
        mem_gb, mapping = MEM_VAL_5, "jobs/job.py Job.mem_val (5 columns)"
    elif n_cols == 4:
        mem_gb, mapping = MEM_VAL_4, ("inferred: cvxpy_test.py mem_val = Job.mem_val[1:] "
                                      "(4-column legacy format)")
    else:
        raise ProfileParseError(f"{source_rel}: tput has {n_cols} columns; expected 4 or 5")

    defaults_used = sorted(k for k in MODELSTATS_DEFAULTS if k not in raw.fields)
    f = {**MODELSTATS_DEFAULTS, **raw.fields}

    model = raw.class_name.rsplit("_", 1)[0]
    stem_gpus = raw.class_name.rsplit("_", 1)[-1]
    if stem_gpus.isdigit() and int(stem_gpus) != raw.gpu_count:
        warnings.append(f"{source_rel}: class name says {stem_gpus} GPUs, "
                        f"super().__init__ says {raw.gpu_count}; using {raw.gpu_count}")

    throughput = [[float(v) for v in row] for row in tput]
    for i, row in enumerate(throughput):
        if row[-1] > row[-2] + 1e-9:
            warnings.append(f"{source_rel}: row {i} ({CPU_VAL[i]} CPUs) still rises at the last "
                            f"memory column, so mem_fraction 1.0 may not mean fully cached")

    fair_row = CPU_VAL.index(FAIR_CPUS_PER_GPU)
    fair_col = mem_gb.index(FAIR_MEM_GB_PER_GPU)

    return {
        "model": model,
        "gpu_type": GPU_TYPE,
        "gpu_count": raw.gpu_count,
        "batch_size": _num(f["batch"]),
        "iter_time_s": _num(f["iter_time"]),
        "iter_time_base_s": _num(f["iter_time_base"]) if "iter_time_base" in f else None,
        "ideal_cpus_per_gpu": _num(f["cpus"]),
        "ideal_mem_gb_per_gpu": _num(f["mem"]),
        "ideal_storage_speed_per_gpu": _num(f["speed"]),
        "ideal_speedup": _num(f["speedup"]),
        "placement_penalty": _num(f["placement_penalty"]),
        "cpus": list(CPU_VAL),
        "mem_fractions": [round(m / FULL_MEM_GB, 4) for m in mem_gb],
        "mem_gb_per_gpu": [_num(m) for m in mem_gb],
        "throughput": throughput,
        "fair_share": {
            "cpus_per_gpu": FAIR_CPUS_PER_GPU,
            "mem_gb_per_gpu": FAIR_MEM_GB_PER_GPU,
            "throughput": throughput[fair_row][fair_col],
            "throughput_full_mem": throughput[fair_row][-1],
        },
        "column_mapping": mapping,
        "used_by_synergy": raw.class_name.endswith("_1") and n_cols == 5,
        "defaults_used": defaults_used,
        "source": source_rel,
    }


def throughput_at(profile: dict, cpus_per_gpu: int, mem_fraction: float) -> float:
    """Look up throughput for an exact (cpus per GPU, mem fraction) grid point."""
    i = profile["cpus"].index(cpus_per_gpu)
    j = min(range(len(profile["mem_fractions"])),
            key=lambda k: abs(profile["mem_fractions"][k] - mem_fraction))
    if abs(profile["mem_fractions"][j] - mem_fraction) > 1e-6:
        raise KeyError(f"mem fraction {mem_fraction} is not a grid point")
    return profile["throughput"][i][j]


def fair_share_exceptions(profiles: list[dict]) -> list[dict]:
    """Profiles that are NOT 1.0 at 3 CPUs/GPU with full memory, or at the fair 62.5 GB column."""
    out = []
    for p in profiles:
        fs = p["fair_share"]
        if abs(fs["throughput_full_mem"] - 1.0) > 1e-9 or abs(fs["throughput"] - 1.0) > 1e-9:
            out.append({"source": p["source"],
                        "at_fair_mem": fs["throughput"],
                        "at_full_mem": fs["throughput_full_mem"]})
    return out


# --- driver ------------------------------------------------------------------------------------
def model_files(simulator_dir: Path) -> list[Path]:
    files = []
    for d in MODEL_DIRS:
        folder = simulator_dir / d
        if folder.is_dir():
            files.extend(sorted(p for p in folder.glob("*.py") if p.name not in NON_MODEL_FILES))
    return files


def convert_all(synergy_repo: Path, out_dir: Path) -> tuple[list[dict], list[str], list[str]]:
    """Convert every model file. Returns (profiles, written paths, warnings)."""
    simulator = synergy_repo / "simulator"
    if not simulator.is_dir():
        raise FileNotFoundError(f"{simulator} does not exist (is --synergy-repo right?)")
    files = model_files(simulator)
    if not files:
        raise FileNotFoundError(f"no model files under {simulator}/{{{','.join(MODEL_DIRS)}}}")

    profiles, written, warnings, errors = [], [], [], []
    for path in files:
        rel = path.relative_to(synergy_repo).as_posix()
        try:
            raw = parse_model_source(path.read_text(), filename=rel)
            profile = build_profile(raw, rel, warnings)
        except (ProfileParseError, SyntaxError, ValueError, ZeroDivisionError) as exc:
            errors.append(f"{rel}: {exc}")
            continue
        target = out_dir / path.parent.name / f"{path.stem}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(profile, indent=2) + "\n")
        profiles.append(profile)
        written.append(str(target))
    if errors:
        raise ProfileParseError("could not convert:\n  " + "\n  ".join(errors))
    return profiles, written, warnings


def main(argv: list[str] | None = None) -> int:
    default_repo = Path(__file__).resolve().parents[2] / "synergy" / "repo"
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--synergy-repo", type=Path, default=default_repo,
                    help=f"Synergy checkout (default: {default_repo})")
    ap.add_argument("-o", "--out", type=Path, required=True,
                    help="output folder; files go to <out>/<models dir>/<model>_<gpus>.json")
    ap.add_argument("--report", type=Path,
                    help="also write the fair-share exception list (Markdown) to this file")
    args = ap.parse_args(argv)

    try:
        profiles, written, warnings = convert_all(args.synergy_repo, args.out)
    except (FileNotFoundError, ProfileParseError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)
    print(f"converted {len(written)} model files into {args.out}")

    exceptions = fair_share_exceptions(profiles)
    lines = ["| source | at 3 CPU, 62.5 GB | at 3 CPU, full mem |", "|---|---|---|"]
    lines += [f"| {e['source']} | {e['at_fair_mem']} | {e['at_full_mem']} |" for e in exceptions]
    if exceptions:
        print(f"{len(exceptions)} profile(s) are NOT 1.0 at the fixed share:")
        print("\n".join(lines))
    else:
        print("all profiles are 1.0 at the fixed share (3 CPUs/GPU) with 62.5 GB and full memory")
    if args.report:
        body = "\n".join(lines) if exceptions else "No exceptions: every profile is 1.0."
        args.report.write_text("# Fair-share exceptions\n\n" + body + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
