#!/usr/bin/env python3
"""PM4Py 一致性校验脚本（评估 §6.2 G15）。

输入参数：
    --cases  用例 CSV（case_id, activity, timestamp）
    --model  流程模型 JSON（start_activity, end_activity, transitions: [{from, to}]）

输出（stdout JSON）：
{
    "fitness": 0.85,            # token-based replay fitness
    "precision": 0.7,           # 精度
    "generalization": 0.6,      # 泛化度
    "fitnessPerTrace": [...],   # 每条 trace 的 fitness
    "deviations": [...]         # 不符合模型的活动列表
}

降级行为：当 pm4py 未安装时，输出 {"available": false, "errorMessage": "..."}，
Java 端据此跳过一致性校验。
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PM4Py conformance check")
    parser.add_argument("--cases", required=True, help="CSV path: case_id,activity,timestamp")
    parser.add_argument("--model", required=True, help="JSON path: start/end + transitions")
    parser.add_argument("--no-deps", action="store_true",
                        help="Force skip pm4py import (for environments without pm4py)")
    return parser.parse_args()


def load_cases(cases_path: str):
    """Load traces from CSV."""
    traces = []
    if not Path(cases_path).exists():
        return traces
    with open(cases_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            traces.append({
                "case_id": row.get("case_id", ""),
                "activity": row.get("activity", ""),
                "timestamp": row.get("timestamp", ""),
            })
    return traces


def load_model(model_path: str) -> dict:
    with open(model_path, "r", encoding="utf-8") as f:
        return json.load(f)


def run_with_pm4py(cases, model: dict) -> dict:
    """Run conformance check using pm4py."""
    try:
        import pm4py  # type: ignore
    except ImportError as e:
        return {
            "available": False,
            "errorMessage": f"pm4py not installed: {e}",
        }

    # Build event log
    import pandas as pd  # noqa: F401
    df = pd.DataFrame(cases)
    if df.empty:
        return {
            "available": True,
            "fitness": 1.0,
            "precision": 1.0,
            "generalization": 1.0,
            "fitnessPerTrace": [],
            "deviations": [],
        }

    df = df.rename(columns={
        "case_id": "case:concept:name",
        "activity": "concept:name",
        "timestamp": "time:timestamp",
    })
    df["time:timestamp"] = pd.to_datetime(df["time:timestamp"], errors="coerce")
    df = df.dropna(subset=["time:timestamp"])

    # Build petri net from model
    net, im, fm = pm4py.parse_petri_net_string(_model_to_pnml(model))

    # Token-based replay for fitness
    fitness_per_trace = pm4py.fitness_token_based_replay(df, net, im, fm)
    log_fitness = fitness_per_trace.get("log_fitness", 0.0)
    trace_fitness = fitness_per_trace.get("trace_fitness", {})

    # Precision
    precision = pm4py.precision_token_based(df, net, im, fm) if hasattr(pm4py, "precision_token_based") else 0.0

    # Generalization (default fallback)
    generalization = 0.0
    try:
        if hasattr(pm4py, "generalization"):
            generalization = pm4py.generalization(df, net, im, fm)
    except Exception:
        pass

    # Deviations: activities that appear in log but not in model
    model_activities = set()
    for t in model.get("transitions", []):
        model_activities.add(t.get("from", ""))
        model_activities.add(t.get("to", ""))
    model_activities.discard("")
    seen_activities = set(df["concept:name"].astype(str).tolist())
    deviations = sorted(seen_activities - model_activities)

    return {
        "available": True,
        "fitness": round(float(log_fitness), 4),
        "precision": round(float(precision), 4),
        "generalization": round(float(generalization), 4),
        "fitnessPerTrace": [f"{k}: {v:.4f}" for k, v in trace_fitness.items()],
        "deviations": deviations,
    }


def _model_to_pnml(model: dict) -> str:
    """Convert simple JSON model to PNML string for pm4py."""
    start = model.get("start_activity", "START")
    end = model.get("end_activity", "END")
    transitions = model.get("transitions", [])

    places = []
    arcs = []
    transitions_xml = []

    # Start place -> start transition
    places.append('<place id="p_start"><name><text>start</text></name></place>')
    transitions_xml.append(f'<transition id="t_start"><name><text>{start}</text></name></transition>')
    arcs.append('<arc id="a1" source="p_start" target="t_start"/>')
    arcs.append(f'<arc id="a2" source="t_start" target="p_0"/>')

    prev_place = "p_0"
    for idx, tr in enumerate(transitions):
        t_id = f"t_{idx}"
        act = tr.get("from", f"act_{idx}")
        to_act = tr.get("to", "")
        if to_act:
            transitions_xml.append(f'<transition id="{t_id}"><name><text>{act}</text></name></transition>')
            places.append(f'<place id="p_{idx + 1}"/>')
            arcs.append(f'<arc id="ax_{idx}" source="{prev_place}" target="{t_id}"/>')
            arcs.append(f'<arc id="ay_{idx}" source="{t_id}" target="p_{idx + 1}"/>')
            prev_place = f"p_{idx + 1}"

    # Last transition -> end
    transitions_xml.append(f'<transition id="t_end"><name><text>{end}</text></name></transition>')
    arcs.append(f'<arc id="az" source="{prev_place}" target="t_end"/>')
    places.append('<place id="p_end"><name><text>end</text></name></place>')
    arcs.append('<arc id="ae" source="t_end" target="p_end"/>')

    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<pnml xmlns="http://www.pnml.org/version-2009/grammar/pnml">\n'
        '<net id="model" type="http://www.pnml.org/version-2009/grammar/petrinet">\n'
        f'<page id="p1">{"".join(places)}{"".join(transitions_xml)}{"".join(arcs)}</page>\n'
        '</net>\n</pnml>'
    )


def run_fallback(cases, model: dict) -> dict:
    """Fallback when pm4py is not available: compute simple activity coverage."""
    seen = sorted({c["activity"] for c in cases if c.get("activity")})
    model_activities = set()
    for tr in model.get("transitions", []):
        model_activities.add(tr.get("from", ""))
        model_activities.add(tr.get("to", ""))
    model_activities.discard("")
    coverage = (len(model_activities & set(seen)) / len(model_activities)) if model_activities else 1.0
    return {
        "available": False,
        "errorMessage": "pm4py not installed; computed simple activity coverage instead",
        "fitness": round(coverage, 4),
        "precision": 0.0,
        "generalization": 0.0,
        "fitnessPerTrace": [],
        "deviations": sorted(set(seen) - model_activities),
    }


def main() -> int:
    args = parse_args()
    cases = load_cases(args.cases)
    model = load_model(args.model)
    if args.no_deps:
        result = run_fallback(cases, model)
    else:
        try:
            result = run_with_pm4py(cases, model)
        except ImportError:
            result = run_fallback(cases, model)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())