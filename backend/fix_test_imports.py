#!/usr/bin/env python3
"""批量修复测试文件的 import 路径"""

import os
import re
from pathlib import Path
from collections import defaultdict

BACKEND_DIR = Path("/Users/huymac/工作/数智/LegacyGraph/backend")
MAIN_SRC = BACKEND_DIR / "src/main/java/io/github/legacygraph"
TEST_SRC = BACKEND_DIR / "src/test/java/io/github/legacygraph"

def find_class_location(class_name):
    """查找类在主源码中的实际位置"""
    for java_file in MAIN_SRC.rglob(f"{class_name}.java"):
        rel_path = java_file.relative_to(MAIN_SRC)
        package_path = str(rel_path.parent).replace('/', '.')
        if package_path == '.':
            package_path = ''
        return package_path
    return None

def fix_imports_in_file(test_file):
    """修复单个测试文件的 import 语句"""
    with open(test_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    
    # 匹配 import 语句
    import_pattern = re.compile(r'import\s+io\.github\.legacygraph\.([^.]+(?:\.[^.]+)*?)\.([A-Z][a-zA-Z0-9]+);')
    
    replacements = []
    for match in import_pattern.finditer(content):
        current_pkg = match.group(1)
        class_name = match.group(2)
        
        # 查找实际位置
        actual_pkg = find_class_location(class_name)
        
        if actual_pkg is not None and actual_pkg != current_pkg:
            old_import = match.group(0)
            if actual_pkg:
                new_import = f"import io.github.legacygraph.{actual_pkg}.{class_name};"
            else:
                new_import = f"import io.github.legacygraph.{class_name};"
            replacements.append((old_import, new_import))
    
    # 应用替换
    for old, new in replacements:
        content = content.replace(old, new)
    
    if content != original:
        with open(test_file, 'w', encoding='utf-8') as f:
            f.write(content)
        return len(replacements)
    return 0

def main():
    total_fixed = 0
    files_fixed = []
    
    for test_file in TEST_SRC.rglob("*.java"):
        fixed = fix_imports_in_file(test_file)
        if fixed > 0:
            total_fixed += fixed
            files_fixed.append((test_file.name, fixed))
    
    print(f"修复了 {total_fixed} 个 import 语句，涉及 {len(files_fixed)} 个文件:")
    for fname, count in sorted(files_fixed, key=lambda x: -x[1])[:20]:
        print(f"  {fname}: {count} 个")
    if len(files_fixed) > 20:
        print(f"  ... 还有 {len(files_fixed) - 20} 个文件")

if __name__ == "__main__":
    main()
