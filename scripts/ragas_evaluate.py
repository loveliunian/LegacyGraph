#!/usr/bin/env python3
"""
P3-2: Ragas 评估脚本 — 由 RagasScoringService (Java) 通过子进程调用。
使用 ragas 库对 RAG 问答对进行质量评估，输出 JSON 格式的平均指标。

用法:
    python3 ragas_evaluate.py --dataset cases.jsonl --metrics context_precision,context_recall,faithfulness,answer_relevancy --llm-model gpt-4o-mini

输入: JSONL 文件，每行一个 JSON 对象，包含:
    - question: str
    - answer: str
    - contexts: List[str]

输出: 最后一行为 JSON，包含各指标的平均值。
"""

import argparse
import json
import sys

def main():
    parser = argparse.ArgumentParser(description="Ragas RAG evaluation script")
    parser.add_argument("--dataset", required=True, help="Path to JSONL dataset file")
    parser.add_argument("--metrics", default="context_precision,context_recall,faithfulness,answer_relevancy",
                        help="Comma-separated metric names")
    parser.add_argument("--llm-model", default="gpt-4o-mini", help="LLM model for ragas evaluation")
    args = parser.parse_args()

    try:
        from ragas import evaluate
        from ragas.metrics import (
            context_precision,
            context_recall,
            faithfulness,
            answer_relevancy,
        )
        from datasets import Dataset
    except ImportError as e:
        print(json.dumps({"error": f"Missing dependency: {e}. Install with: pip install ragas datasets"}))
        sys.exit(1)

    # 读取 JSONL 数据
    records = []
    with open(args.dataset, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))

    if not records:
        print(json.dumps({"error": "No records found in dataset"}))
        sys.exit(1)

    # 构建 Dataset
    ds = Dataset.from_list(records)

    # 映射 metric 名称到对象
    metric_map = {
        "context_precision": context_precision,
        "context_recall": context_recall,
        "faithfulness": faithfulness,
        "answer_relevancy": answer_relevancy,
    }
    selected_metrics = [metric_map[m.strip()] for m in args.metrics.split(",") if m.strip() in metric_map]

    if not selected_metrics:
        print(json.dumps({"error": "No valid metrics selected"}))
        sys.exit(1)

    # 执行评估
    try:
        result = evaluate(
            dataset=ds,
            metrics=selected_metrics,
        )
        # 输出结果：取各指标的平均值
        output = {}
        for metric_name in args.metrics.split(","):
            metric_name = metric_name.strip()
            if metric_name in result:
                # result 是一个 dict-like 对象，值是各样本的分数列表
                scores = result[metric_name]
                if isinstance(scores, list):
                    avg = sum(scores) / len(scores) if scores else 0
                else:
                    avg = float(scores)
                output[metric_name] = round(avg, 4)

        print(json.dumps(output))

    except Exception as e:
        print(json.dumps({"error": f"Evaluation failed: {e}"}))
        sys.exit(1)

if __name__ == "__main__":
    main()
