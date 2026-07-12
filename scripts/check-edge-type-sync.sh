#!/usr/bin/env bash
# =============================================================================
# H07: EdgeType 枚举与 SQL 字典同步校验
#   1. 从 EdgeType.java 提取枚举值
#   2. 从 db/migration/V*.sql 提取 graph_edge_type 字典条目
#   3. 对比差异，差异 > 0 时 exit 1
# 用法: bash scripts/check-edge-type-sync.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EDGE_TYPE_JAVA="$PROJECT_ROOT/backend/src/main/java/io/github/legacygraph/common/EdgeType.java"
MIGRATION_DIR="$PROJECT_ROOT/backend/src/main/resources/db/migration"

# --- 1. 提取 EdgeType.java 枚举值 ---
# 匹配: ENUM_NAME("description") 或 ENUM_NAME("description"),  或 ENUM_NAME("description");
java_enums=$(grep -oE '^\s+[A-Z][A-Z_]+\(' "$EDGE_TYPE_JAVA" | sed 's/^[[:space:]]*//' | sed 's/($//' | sort -u)

if [ -z "$java_enums" ]; then
    echo "ERROR: No enum values found in $EDGE_TYPE_JAVA"
    exit 1
fi

java_count=$(echo "$java_enums" | wc -l | tr -d ' ')
echo "EdgeType.java: $java_count enum values"

# --- 2. 从 migration SQL 提取 graph_edge_type 字典条目 ---
# graph_edge_type 的 dict_id
DICT_ID='d501c3d4-5678-9abc-def0-1234567890ab'
# 只搜索包含 graph_edge_type dict_id 的行，提取 item_value（第 3 个字段，格式: 'EDGE_TYPE_NAME'）
sql_enums=$(grep -rh "$DICT_ID" "$MIGRATION_DIR"/V*.sql 2>/dev/null \
    | grep -oE "'[A-Z][A-Z_]+'" \
    | sed "s/'//g" \
    | grep -v "^$DICT_ID$" \
    | sort -u)

if [ -z "$sql_enums" ]; then
    echo "ERROR: No graph_edge_type dict items found in migration SQL"
    exit 1
fi

sql_count=$(echo "$sql_enums" | wc -l | tr -d ' ')
echo "SQL dict items: $sql_count entries"

# --- 3. 对比差异 ---
missing_in_sql=$(comm -23 <(echo "$java_enums") <(echo "$sql_enums"))
missing_in_java=$(comm -13 <(echo "$java_enums") <(echo "$sql_enums"))

has_diff=0

if [ -n "$missing_in_sql" ]; then
    echo ""
    echo "FAIL: Enum values missing in SQL dict (need to add to migration):"
    echo "$missing_in_sql" | sed 's/^/  - /'
    has_diff=1
fi

if [ -n "$missing_in_java" ]; then
    echo ""
    echo "WARN: SQL dict items not in EdgeType enum (orphan dict entries):"
    echo "$missing_in_java" | sed 's/^/  - /'
fi

if [ $has_diff -eq 0 ]; then
    echo ""
    echo "OK: EdgeType enum ($java_count) is in sync with SQL dict ($sql_count)"
    exit 0
else
    echo ""
    echo "FAIL: EdgeType enum and SQL dict are out of sync"
    exit 1
fi
