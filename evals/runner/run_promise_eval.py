#!/usr/bin/env python3
"""Run Promise Compiler evals — exact-field and safety-critical-field scoring."""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PROMISE_ADAPTER_NAMES = ("baseline", "azure_openai")


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


_RUNNER_DIR = Path(__file__).resolve().parent
if str(_RUNNER_DIR) not in sys.path:
    sys.path.insert(0, str(_RUNNER_DIR))

from adapters.azure_openai import KNOWN_DEPLOYMENTS  # noqa: E402
from adapters.promise_azure import get_promise_compiler  # noqa: E402
from adapters.promise_baseline import PromiseBaselineCompiler  # noqa: E402
from promise_models import (  # noqa: E402
    POLICY_FIELDS,
    SAFETY_CRITICAL_FIELDS,
    load_promise_jsonl,
    repo_root,
    safety_violation,
    score_assistant_ux,
    score_policy,
    score_temporal_clocks,
)


def run_eval(compiler: Any, cases: list) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    metrics: dict[str, Any] = {
        "total": 0,
        "exact_match": 0,
        "safety_match": 0,
        "safety_violations": 0,
        "parse_failures": 0,
        "content_filter_blocks": 0,
        "total_latency_ms": 0.0,
        "temporal_total": 0,
        "temporal_ok": 0,
        "clock_confusion": 0,
        "clock_class_stats": {},
        "clarify_ok": 0,
        "option_ok": 0,
        "package_leaks": 0,
        "unsafe_auto_start": 0,
        "clarify_expected": 0,
    }
    field_match_counts: dict[str, int] = {field: 0 for field in POLICY_FIELDS}

    for case_index, case in enumerate(cases, start=1):
        started = time.perf_counter()
        if isinstance(compiler, PromiseBaselineCompiler):
            predicted = compiler.compile(case)
            error = None
            latency_ms = (time.perf_counter() - started) * 1000.0
        else:
            result = compiler.compile(case)
            predicted = result.policy
            error = result.error
            latency_ms = result.latency_ms or (time.perf_counter() - started) * 1000.0

        field_matches, exact, safety = score_policy(case.expected_policy, predicted)
        violation = safety_violation(case.expected_policy, predicted)
        content_filtered = predicted is None and "content_filter" in (error or "")
        if content_filtered:
            violation = "content_filter_block"

        clock_key = case.clock_class or case.cluster
        temporal = score_temporal_clocks(case.expected_policy, predicted, clock_key)
        metrics["temporal_total"] += 1
        metrics["temporal_ok"] += int(bool(temporal.get("clock_ok")))
        metrics["clock_confusion"] += int(bool(temporal.get("confusion")))
        bucket = metrics["clock_class_stats"].setdefault(
            clock_key, {"n": 0, "ok": 0, "confusion": 0}
        )
        bucket["n"] += 1
        bucket["ok"] += int(bool(temporal.get("clock_ok")))
        bucket["confusion"] += int(bool(temporal.get("confusion")))

        assistant = score_assistant_ux(case.expected_policy, predicted)
        if case.expected_policy.get("clarificationRequired") or case.expected_policy.get(
            "followUpQuestionRequired"
        ):
            metrics["clarify_expected"] += 1
        metrics["clarify_ok"] += int(bool(assistant.get("clarification_required_ok")))
        metrics["option_ok"] += int(bool(assistant.get("option_quality_ok")))
        metrics["package_leaks"] += int(bool(assistant.get("package_leak")))
        metrics["unsafe_auto_start"] += int(bool(assistant.get("unsafe_auto_start")))

        metrics["total"] += 1
        metrics["exact_match"] += int(exact)
        metrics["safety_match"] += int(safety)
        metrics["safety_violations"] += int(violation is not None)
        metrics["parse_failures"] += int(predicted is None and not content_filtered)
        metrics["content_filter_blocks"] += int(content_filtered)
        metrics["total_latency_ms"] = float(metrics["total_latency_ms"]) + float(latency_ms)
        for field, matched in field_matches.items():
            field_match_counts[field] += int(matched)

        mismatches = [f for f in POLICY_FIELDS if not field_matches.get(f, False)]
        safety_mismatches = [f for f in SAFETY_CRITICAL_FIELDS if not field_matches.get(f, False)]

        dims = case.dimensions or {}
        rows.append(
            {
                "caseIndex": case_index,
                "id": case.id,
                "cluster": case.cluster,
                "clockClass": case.clock_class,
                "language": str(dims.get("language") or ""),
                "typoLevel": str(dims.get("typo_level") or ""),
                "promiseType": str(dims.get("promise_type") or ""),
                "appSurface": str(dims.get("app_surface") or ""),
                "timeRole": str(dims.get("time_role") or ""),
                "safetyRisk": str(dims.get("safety_risk") or ""),
                "expectedFollowupTag": str(dims.get("expected_followup") if dims else ""),
                "expectedGuardrailsTag": ",".join(dims.get("expected_guardrails") or []),
                "adapter": compiler.name,
                "exactMatch": str(exact),
                "safetyMatch": str(safety),
                "clockOk": str(bool(temporal.get("clock_ok"))),
                "clockConfusion": str(bool(temporal.get("confusion"))),
                "clockDetail": temporal.get("detail") or "",
                "clarifyOk": str(bool(assistant.get("clarification_required_ok"))),
                "optionOk": str(bool(assistant.get("option_quality_ok"))),
                "packageLeak": str(bool(assistant.get("package_leak"))),
                "unsafeAutoStart": str(bool(assistant.get("unsafe_auto_start"))),
                "safetyViolation": violation or "",
                "parseError": error or "",
                "mismatchedFields": ";".join(mismatches),
                "safetyMismatches": ";".join(safety_mismatches),
                "expectedCommitmentType": case.expected_policy.get("commitmentType", ""),
                "predictedCommitmentType": (predicted or {}).get("commitmentType", ""),
                "expectedGuardrails": ",".join(case.expected_policy.get("activeGuardrails", [])),
                "predictedGuardrails": ",".join((predicted or {}).get("activeGuardrails", [])),
                "followUpExpected": str(case.expected_policy.get("followUpQuestionRequired", False)),
                "followUpPredicted": str((predicted or {}).get("followUpQuestionRequired", "")),
                "expectedQuotas": json.dumps(
                    case.expected_policy.get("quotas", []), ensure_ascii=False
                ),
                "predictedQuotas": json.dumps(
                    (predicted or {}).get("quotas", []), ensure_ascii=False
                ),
                "latencyMs": f"{latency_ms:.1f}",
                "predictedPolicy": json.dumps(predicted, ensure_ascii=False) if predicted else "",
                "notes": case.notes,
            }
        )

    total = int(metrics["total"])
    metrics["field_match_rates"] = {
        field: round(count / total, 4) if total else 0.0
        for field, count in field_match_counts.items()
    }
    return rows, metrics


def write_report(rows: list[dict[str, Any]], path: Path, metadata: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        handle.write(f"# metadata: {json.dumps(metadata, ensure_ascii=False)}\n")
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    path.with_suffix(".meta.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def print_summary(metrics: dict[str, Any], adapter_name: str) -> None:
    total = int(metrics["total"])
    exact = int(metrics["exact_match"])
    safety = int(metrics["safety_match"])
    print(f"Adapter: {adapter_name}")
    print(f"Cases: {total}")
    print(f"Exact field match: {exact / total:.1%} ({exact}/{total})")
    print(f"Safety-critical match: {safety / total:.1%} ({safety}/{total})")
    print(f"Safety violations: {int(metrics['safety_violations'])}")
    print(f"Parse failures: {int(metrics['parse_failures'])}")
    print(f"Content filter blocks: {int(metrics['content_filter_blocks'])}")
    avg = float(metrics["total_latency_ms"]) / total if total else 0.0
    print(f"Avg latency/case: {avg:.0f}ms")

    tt = int(metrics.get("temporal_total") or 0)
    if tt:
        tok = int(metrics.get("temporal_ok") or 0)
        conf = int(metrics.get("clock_confusion") or 0)
        print(f"Temporal clock OK: {tok / tt:.1%} ({tok}/{tt})")
        print(f"Clock confusions (media↔budget/lock↔session): {conf}")
        stats = metrics.get("clock_class_stats") or {}
        if stats:
            print("\nPer clockClass:")
            for cc, b in sorted(stats.items(), key=lambda kv: -kv[1]["n"]):
                n = b["n"]
                print(f"  {cc}: clock_ok {b['ok']}/{n} confusion {b['confusion']}")

    print(
        f"Clarification agreement: {int(metrics.get('clarify_ok') or 0) / total:.1%}"
        if total
        else "Clarification agreement: n/a"
    )
    print(
        f"Option quality: {int(metrics.get('option_ok') or 0) / total:.1%}"
        if total
        else "Option quality: n/a"
    )
    print(
        f"Package leak rate: {int(metrics.get('package_leaks') or 0) / total:.1%}"
        if total
        else "Package leak rate: n/a"
    )
    print(
        f"Unsafe auto-start proxy: {int(metrics.get('unsafe_auto_start') or 0) / total:.1%}"
        if total
        else "Unsafe auto-start proxy: n/a"
    )

    rates = metrics.get("field_match_rates")
    if isinstance(rates, dict):
        print("\nPer-field match rates:")
        for field, rate in sorted(rates.items(), key=lambda kv: kv[1]):
            print(f"  {field}: {rate:.0%}")


def parse_args() -> argparse.Namespace:
    root = repo_root()
    parser = argparse.ArgumentParser(description="Run PhoneCodex Promise Compiler eval")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=root / "evals" / "datasets" / "v2_commitment_language.jsonl",
    )
    parser.add_argument(
        "--report-dir",
        type=Path,
        default=root / "evals" / "reports",
    )
    parser.add_argument(
        "--adapter",
        choices=PROMISE_ADAPTER_NAMES,
        default="baseline",
    )
    parser.add_argument(
        "--prompt",
        type=Path,
        default=root / "evals" / "prompts" / "promise_compiler_v01.txt",
    )
    parser.add_argument(
        "--deployment",
        choices=sorted(KNOWN_DEPLOYMENTS.keys()),
        default=None,
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional cap on cases (smoke / cost control)",
    )
    return parser.parse_args()


def main() -> int:
    root = repo_root()
    load_dotenv(root / ".env")
    args = parse_args()

    prompt_path = args.prompt if args.prompt.is_absolute() else root / args.prompt
    dataset_path = args.dataset if args.dataset.is_absolute() else root / args.dataset

    try:
        compiler = get_promise_compiler(
            args.adapter,
            prompt_path=prompt_path if args.adapter == "azure_openai" else None,
            deployment=args.deployment,
        )
    except (ValueError, RuntimeError, FileNotFoundError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    cases = load_promise_jsonl(dataset_path)
    if args.limit:
        cases = cases[: args.limit]

    started_at = datetime.now(timezone.utc)
    rows, metrics = run_eval(compiler, cases)
    ended_at = datetime.now(timezone.utc)

    total = int(metrics["total"])
    metadata = {
        "task": "promise_compiler",
        "adapter": compiler.name,
        "dataset": dataset_path.name,
        "prompt": prompt_path.name if args.adapter == "azure_openai" else None,
        "deployment": args.deployment or os.environ.get("AZURE_OPENAI_DEPLOYMENT"),
        "caseCount": total,
        "exactMatchRate": round(int(metrics["exact_match"]) / total, 4) if total else 0.0,
        "safetyMatchRate": round(int(metrics["safety_match"]) / total, 4) if total else 0.0,
        "safetyViolations": int(metrics["safety_violations"]),
        "parseFailures": int(metrics["parse_failures"]),
        "contentFilterBlocks": int(metrics["content_filter_blocks"]),
        "fieldMatchRates": metrics.get("field_match_rates", {}),
        "temporalClockOkRate": (
            round(int(metrics["temporal_ok"]) / int(metrics["temporal_total"]), 4)
            if int(metrics.get("temporal_total") or 0)
            else None
        ),
        "clockConfusions": int(metrics.get("clock_confusion") or 0),
        "clockClassStats": metrics.get("clock_class_stats", {}),
        "clarificationAgreementRate": (
            round(int(metrics["clarify_ok"]) / total, 4) if total else 0.0
        ),
        "optionQualityRate": (
            round(int(metrics["option_ok"]) / total, 4) if total else 0.0
        ),
        "packageLeakRate": (
            round(int(metrics["package_leaks"]) / total, 4) if total else 0.0
        ),
        "unsafeAutoStartRate": (
            round(int(metrics["unsafe_auto_start"]) / total, 4) if total else 0.0
        ),
        "startedAt": started_at.isoformat(),
        "endedAt": ended_at.isoformat(),
    }

    parts = ["promise_compiler", compiler.name]
    if args.deployment:
        parts.append(args.deployment)
    if args.adapter == "azure_openai":
        parts.append(prompt_path.stem)
    parts.append(dataset_path.stem)
    if args.limit:
        parts.append(f"limit{args.limit}")
    timestamp = ended_at.strftime("%Y%m%d_%H%M%S")
    report_path = args.report_dir / f"{'_'.join(parts)}_{timestamp}.csv"
    write_report(rows, report_path, metadata)

    print(f"Dataset: {dataset_path}")
    if args.adapter == "azure_openai":
        print(f"Prompt: {prompt_path}")
        print(f"Deployment: {metadata['deployment']}")
    print(f"Report: {report_path}")
    print_summary(metrics, compiler.name)

    failures = [r for r in rows if r["safetyViolation"]]
    if failures[:10]:
        print("\nSafety violations (first 10):")
        for row in failures[:10]:
            print(
                f"- caseIndex={row['caseIndex']} id={row['id']}: "
                f"{row['safetyViolation']} ({row['cluster']})"
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
