#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前后端接口一致性审计脚本：扫描后端 Controller 端点与前端 API 调用点，生成差集。"""
import os
import re
import json

BASE = "/Users/huymac/工作/数智/LegacyGraph"
CTRL_DIR = os.path.join(BASE, "backend/src/main/java/io/github/legacygraph/controller")
API_DIR = os.path.join(BASE, "frontend/src/api")

def normalize_path(path):
    """Normalize a URL path for comparison: replace all path variables with {var},
    strip query strings, remove template literal artifacts."""
    # strip query string
    path = path.split("?")[0]
    # Replace ${var} -> {var} (for frontend template strings)
    path = re.sub(r'\$\{([^}]*)\}', r'{\1}', path)
    # Normalize {encodeURIComponent(var)} -> {var}
    path = re.sub(r'\{encodeURIComponent\(([^)]*)\)\}', r'{\1}', path)
    # Handle /api/ prefix (proxy)
    if path.startswith("/api/"):
        path = path[4:]
    # Split by / and process each segment
    parts = path.split("/")
    new_parts = []
    for part in parts:
        if not part:
            new_parts.append(part)
            continue
        if part.startswith("{") and part.endswith("}") and len(part) > 2:
            # Full path variable segment -> normalize to {var}
            new_parts.append("{var}")
        else:
            # Remove any {var} embedded in the segment (e.g., "start{suffix}" -> "start")
            part = re.sub(r'\{[^}]*\}', '', part)
            if part:
                new_parts.append(part)
            elif not new_parts:
                # keep empty only at start
                pass
    # Rejoin, but filter out empty parts (except leading /)
    result = "/".join(p for p in new_parts if p)
    if not result.startswith("/"):
        result = "/" + result
    # Remove double slashes
    result = re.sub(r'/+', '/', result)
    return result

# ---------- 后端端点提取 ----------
def extract_backend_endpoints():
    endpoints = []  # list of dict: method, path, controller, func
    for fname in sorted(os.listdir(CTRL_DIR)):
        if not fname.endswith(".java"):
            continue
        fpath = os.path.join(CTRL_DIR, fname)
        with open(fpath, "r", encoding="utf-8") as f:
            content = f.read()
        lines = content.split("\n")
        # find class-level @RequestMapping
        base_path = ""
        # match @RequestMapping("/path") or @RequestMapping(value="/path")
        for i, line in enumerate(lines):
            if "@RequestMapping" in line and "class " not in line:
                # could be class-level; check if next few lines have class
                m = re.search(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"', line)
                if m:
                    base_path = m.group(1)
                else:
                    # maybe multi-line: @RequestMapping(\n  "/path"\n)
                    # look ahead
                    for j in range(i, min(i+5, len(lines))):
                        m2 = re.search(r'"(/[^"]*)"', lines[j])
                        if m2 and "@RequestMapping" in "".join(lines[i:j+1]):
                            base_path = m2.group(1)
                            break
                break
        # find method-level mappings
        # We'll iterate lines and for each mapping annotation, capture path + next method name
        i = 0
        while i < len(lines):
            line = lines[i]
            # match @GetMapping / @PostMapping / @PutMapping / @DeleteMapping / @PatchMapping
            mapping_match = re.search(
                r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*(\([^)]*\))?',
                line
            )
            if mapping_match:
                http_verb_map = {
                    "GetMapping": "GET",
                    "PostMapping": "POST",
                    "PutMapping": "PUT",
                    "DeleteMapping": "DELETE",
                    "PatchMapping": "PATCH",
                }
                verb = http_verb_map[mapping_match.group(1)]
                args = mapping_match.group(2) or ""
                method_path = ""
                # try to extract path from args
                # patterns: ("/path"), value="/path", path="/path", {"/path"}
                pm = re.search(r'(?:value|path)\s*=\s*"([^"]*)"', args)
                if pm:
                    method_path = pm.group(1)
                else:
                    pm2 = re.search(r'"([^"]*)"', args)
                    if pm2:
                        method_path = pm2.group(1)
                # if args has produces/consumes but no path, method_path stays ""
                # Also handle multi-line annotation: if line has ( but no closing ) on same line
                if "(" in line and ")" not in line[line.find("@"):]:
                    # multi-line; read until closing paren
                    buf = line
                    j = i + 1
                    while j < len(lines) and ")" not in buf:
                        buf += " " + lines[j]
                        j += 1
                    # re-extract args from buf
                    arg_start = buf.find("(")
                    if arg_start >= 0:
                        args = buf[arg_start:]
                    pm = re.search(r'(?:value|path)\s*=\s*"([^"]*)"', args)
                    if pm:
                        method_path = pm.group(1)
                    else:
                        pm2 = re.search(r'"([^"]*)"', args)
                        if pm2:
                            method_path = pm2.group(1)
                # find the method name: look for public/private/protected method after annotation
                func_name = ""
                # search forward from current line for method signature
                for j in range(i, min(i + 15, len(lines))):
                    mfunc = re.search(
                        r'(?:public|private|protected)\s+(?:[\w<>,\s\[\]?]+)\s+(\w+)\s*\(',
                        lines[j]
                    )
                    if mfunc:
                        func_name = mfunc.group(1)
                        break
                # build full path
                full_path = base_path + method_path
                # normalize: ensure no double slash, ensure leading /
                full_path = re.sub(r'/+', '/', full_path)
                if not full_path.startswith("/"):
                    full_path = "/" + full_path
                # skip @RequestMapping on class itself (already handled)
                # Also skip if this is actually the class-level mapping line
                if "class " in line or "class " in "".join(lines[i:i+3]):
                    # This might be the class-level mapping, skip
                    i += 1
                    continue
                controller_name = fname.replace(".java", "")
                endpoints.append({
                    "method": verb,
                    "path": full_path,
                    "norm_path": normalize_path(full_path),
                    "controller": controller_name,
                    "func": func_name,
                    "file": fname,
                    "line": i + 1,
                })
            i += 1
    return endpoints

# ---------- 前端调用点提取 ----------
def extract_frontend_calls():
    calls = []  # list of dict: method, path, file, raw
    # scan all .ts files in api dir (including subdirs? no, just top level per task, but let's include all)
    api_files = []
    for root, dirs, files in os.walk(API_DIR):
        # skip __tests__
        if "__tests__" in root:
            continue
        for f in files:
            if f.endswith(".ts"):
                api_files.append(os.path.join(root, f))
    # Also scan .vue files in src for fetch/direct calls (task says frontend/src/api but mentions views)
    # Task says: 在 frontend/src/api/ 目录下扫描所有 .ts 文件
    # But previous report also checked views. Let's stick to api/ per task, plus note view-only calls.
    # Actually task step 2 says: "在 frontend/src/api/ 目录下扫描所有 .ts 文件"
    # We'll also scan views for completeness since previous report found calls there.
    vue_dir = os.path.join(BASE, "frontend/src")
    vue_files = []
    for root, dirs, files in os.walk(vue_dir):
        # skip node_modules and __tests__
        if "node_modules" in root:
            continue
        if "__tests__" in root:
            continue
        for f in files:
            if f.endswith(".vue") or f.endswith(".ts"):
                vue_files.append(os.path.join(root, f))

    # Known API path prefixes
    API_PREFIXES = ('/lg/', '/qa/', '/reports/', '/agents/', '/llm/', '/change-tasks/')

    def is_api_path(path):
        """Check if a normalized path looks like a real API path."""
        if not path.startswith("/"):
            return False
        # Must start with a known API prefix (possibly after {var} base url prefix)
        for prefix in API_PREFIXES:
            if path.startswith(prefix):
                return True
            # Handle {var}/lg/... pattern (baseUrl prefix)
            if re.match(r'^/\{var\}' + prefix, path):
                return True
        # Also allow exact matches like /lg, /qa (no trailing slash)
        for prefix in API_PREFIXES:
            p = prefix.rstrip("/")
            if path == p:
                return True
        return False

    def strip_baseurl_prefix(path):
        """Strip leading {var} base URL prefix: /{var}/lg/... -> /lg/..."""
        for prefix in API_PREFIXES:
            m = re.match(r'^/\{var\}(' + prefix + '.*)$', path)
            if m:
                return m.group(1)
        return path

    all_files = list(set(api_files + vue_files))
    for fpath in sorted(all_files):
        rel = os.path.relpath(fpath, BASE)
        with open(fpath, "r", encoding="utf-8") as f:
            content = f.read()
        lines = content.split("\n")
        # Helper: find line number from character offset
        def line_of(offset):
            return content[:offset].count("\n") + 1
        # Process full content (not line by line) to handle multi-line calls
        # Pattern: get/post/put/del with optional generics, then (, then optional whitespace/newlines, then string literal
        for http_method, patterns in [
            ("GET", [r'request\.get(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bget(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']']),
            ("POST", [r'request\.post(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bpost(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bupload(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']']),
            ("PUT", [r'request\.put(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bput(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']']),
            ("DELETE", [r'request\.delete(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bdel(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']', r'\bdelete(?:<.*?>)?\(\s*[`"\']([^`"\']*)[`"\']']),
        ]:
            for pat in patterns:
                for m in re.finditer(pat, content):
                    url = m.group(1)
                    if not url:
                        continue
                    url_norm = normalize_path(url)
                    url_norm = strip_baseurl_prefix(url_norm)
                    if not is_api_path(url_norm):
                        continue
                    calls.append({
                        "method": http_method,
                        "path": url_norm,
                        "file": rel,
                        "line": line_of(m.start()),
                        "raw": url[:120],
                    })
        # Detect string-concatenation URLs: + '/lg/...' or + '/qa/...' or + '/reports/...'
        for m in re.finditer(r"""\+\s*['"`](/(?:lg|qa|reports|agents|llm|change-tasks)/[^'"`]*)['"`]""", content):
            url = m.group(1)
            url_norm = normalize_path(url)
            url_norm = strip_baseurl_prefix(url_norm)
            if not is_api_path(url_norm):
                continue
            ln = line_of(m.start())
            # determine method: look ahead for method: 'POST' etc (search wider: 30 lines)
            method = "GET"  # default
            context_end = min(len(lines), ln + 30)
            context = "\n".join(lines[ln-1:context_end])
            if re.search(r"""method\s*:\s*['"]POST['"]""", context):
                method = "POST"
            elif re.search(r"""method\s*:\s*['"]PUT['"]""", context):
                method = "PUT"
            elif re.search(r"""method\s*:\s*['"]DELETE['"]""", context):
                method = "DELETE"
            calls.append({
                "method": method,
                "path": url_norm,
                "file": rel,
                "line": ln,
                "raw": url[:120],
            })
        # Detect downloadFile / upload helper calls with URL literals
        for m in re.finditer(r'(?:downloadFile|download)\(\s*[`"\']([^`"\']*)[`"\']', content):
            url = m.group(1)
            url_norm = normalize_path(url)
            url_norm = strip_baseurl_prefix(url_norm)
            if is_api_path(url_norm):
                calls.append({
                    "method": "GET",
                    "path": url_norm,
                    "file": rel,
                    "line": line_of(m.start()),
                    "raw": url[:120],
                })
        # Detect fetch() calls with string-literal URLs; check context for method
        for m in re.finditer(r'fetch\(\s*[`"\']([^`"\']*)[`"\']', content):
            url = m.group(1)
            if not url:
                continue
            url_norm = normalize_path(url)
            url_norm = strip_baseurl_prefix(url_norm)
            if not is_api_path(url_norm):
                continue
            ln = line_of(m.start())
            ctx_end = min(len(lines), ln + 15)
            context = "\n".join(lines[ln-1:ctx_end])
            method = "GET"
            if re.search(r"""method\s*:\s*['"]POST['"]""", context):
                method = "POST"
            elif re.search(r"""method\s*:\s*['"]PUT['"]""", context):
                method = "PUT"
            elif re.search(r"""method\s*:\s*['"]DELETE['"]""", context):
                method = "DELETE"
            calls.append({
                "method": method,
                "path": url_norm,
                "file": rel,
                "line": ln,
                "raw": url[:120],
            })
        # Build set of norm_paths already captured by above patterns
        seen_norm_paths = set(c["path"] for c in calls)
        # Detect ${apiBaseUrl} prefixed template string URLs not captured by other patterns
        # e.g. `${apiBaseUrl}/reports/confidence/...` used via downloadFile(url) variable
        for m in re.finditer(r"""\$\{apiBaseUrl\}(/(?:lg|qa|reports|agents|llm|change-tasks)/[^`"']*)""", content):
            url = m.group(1)
            url_norm = normalize_path(url)
            url_norm = strip_baseurl_prefix(url_norm)
            if not is_api_path(url_norm):
                continue
            # Skip if this path was already captured by get/post/upload/downloadFile/fetch patterns
            if url_norm in seen_norm_paths:
                continue
            ln = line_of(m.start())
            # Determine method from surrounding context
            ctx_start = max(0, ln - 6)
            ctx_end = min(len(lines), ln + 15)
            context = "\n".join(lines[ctx_start:ctx_end])
            method = "GET"  # default for downloads / link hrefs
            if re.search(r"""method\s*:\s*['"]POST['"]""", context):
                method = "POST"
            elif re.search(r"""method\s*:\s*['"]PUT['"]""", context):
                method = "PUT"
            elif re.search(r"""method\s*:\s*['"]DELETE['"]""", context):
                method = "DELETE"
            elif re.search(r"""\bdownloadFile\b|\bdownload\b""", context):
                method = "GET"
            calls.append({
                "method": method,
                "path": url_norm,
                "file": rel,
                "line": ln,
                "raw": url[:120],
            })
        # Detect variable-assigned API URLs: const url = `/lg/...` or let url = '/lg/...'
        for m in re.finditer(r"""=\s*[`"'](/(?:lg|qa|reports|agents|llm|change-tasks)/[^`"']*)[`"']""", content):
            url = m.group(1)
            url_norm = normalize_path(url)
            url_norm = strip_baseurl_prefix(url_norm)
            if not is_api_path(url_norm):
                continue
            ln = line_of(m.start())
            # Determine method from surrounding context
            ctx_start = max(0, ln - 6)
            ctx_end = min(len(lines), ln + 15)
            context = "\n".join(lines[ctx_start:ctx_end])
            method = "GET"  # default
            if re.search(r'\bpost\s*\(', context):
                method = "POST"
            elif re.search(r'\bput\s*\(', context):
                method = "PUT"
            elif re.search(r'\bdel\s*\(|\bdelete\s*\(', context):
                method = "DELETE"
            elif re.search(r'\bget\s*\(', context):
                method = "GET"
            calls.append({
                "method": method,
                "path": url_norm,
                "file": rel,
                "line": ln,
                "raw": url[:120],
            })
    # dedupe by method+path
    seen = {}
    deduped = []
    for c in calls:
        key = (c["method"], c["path"])
        if key not in seen:
            seen[key] = c
            deduped.append(c)
        else:
            # keep first occurrence
            pass
    return deduped, calls

def main():
    backend = extract_backend_endpoints()
    frontend_dedup, frontend_all = extract_frontend_calls()

    # Build sets using normalized paths for comparison
    backend_set = set()
    backend_map = {}  # norm_key -> original endpoint info
    for e in backend:
        key = (e["method"], e["norm_path"])
        backend_set.add(key)
        if key not in backend_map:
            backend_map[key] = e
    frontend_set = set()
    frontend_map = {}  # norm_key -> original call info
    for c in frontend_dedup:
        key = (c["method"], c["path"])
        frontend_set.add(key)
        if key not in frontend_map:
            frontend_map[key] = c

    a_class = frontend_set - backend_set  # frontend has, backend missing
    b_class = backend_set - frontend_set  # backend has, frontend missing

    print("=" * 80)
    print("后端端点总数:", len(backend))
    print("后端端点去重 (method+path):", len(backend_set))
    print("前端调用点总数(未去重):", len(frontend_all))
    print("前端调用点去重 (method+path):", len(frontend_dedup))
    print("A 类 (前端有/后端缺):", len(a_class))
    print("B 类 (后端有/前端缺):", len(b_class))
    print("=" * 80)

    print("\n===== 后端端点列表 (按 controller 分组) =====")
    from collections import defaultdict
    by_ctrl = defaultdict(list)
    for e in backend:
        by_ctrl[e["controller"]].append(e)
    for ctrl in sorted(by_ctrl.keys()):
        print(f"\n--- {ctrl} ({len(by_ctrl[ctrl])} endpoints) ---")
        for e in sorted(by_ctrl[ctrl], key=lambda x: (x["method"], x["path"])):
            print(f"  {e['method']:6s} {e['path']}  ({e['func']})  [{e['file']}:{e['line']}]")

    print("\n===== A 类: 前端有/后端缺 =====")
    for item in sorted(a_class):
        d = frontend_map.get(item, {})
        print(f"  {item[0]:6s} {item[1]}  <- {d.get('file','?')}:{d.get('line','?')}  raw={d.get('raw','?')}")

    print("\n===== B 类: 后端有/前端缺 =====")
    b_by_ctrl = defaultdict(list)
    for e in backend:
        if (e["method"], e["norm_path"]) in b_class:
            b_by_ctrl[e["controller"]].append(e)
    for ctrl in sorted(b_by_ctrl.keys()):
        print(f"\n--- {ctrl} ---")
        for e in sorted(b_by_ctrl[ctrl], key=lambda x: (x["method"], x["norm_path"])):
            print(f"  {e['method']:6s} {e['path']}  ({e['func']})  [{e['file']}:{e['line']}]")

    # Write JSON for further processing
    out = {
        "backend_count": len(backend),
        "backend_dedup_count": len(backend_set),
        "frontend_all_count": len(frontend_all),
        "frontend_dedup_count": len(frontend_dedup),
        "a_class_count": len(a_class),
        "b_class_count": len(b_class),
        "backend": backend,
        "frontend_dedup": frontend_dedup,
        "a_class": sorted([{"method": m, "path": p, "detail": frontend_map.get((m,p), {})} for m, p in a_class], key=lambda x: (x["method"], x["path"])),
        "b_class": sorted([{"method": m, "path": p, "detail": backend_map.get((m,p), {})} for m, p in b_class], key=lambda x: (x["method"], x["path"])),
    }
    with open(os.path.join(BASE, "audit_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n[JSON written to audit_result.json]")

if __name__ == "__main__":
    main()
