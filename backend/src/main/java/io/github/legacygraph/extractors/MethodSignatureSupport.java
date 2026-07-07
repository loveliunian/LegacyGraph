package io.github.legacygraph.extractors;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.stream.Collectors;

/**
 * 统一生成 Method nodeKey 中的方法签名部分，与 JavaStructureExtractor 保持一致。
 */
final class MethodSignatureSupport {

    private MethodSignatureSupport() {
    }

    static String build(MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(parameter -> normalizeTypeName(parameter.getType().asString()))
                .collect(Collectors.joining(", "));
        return method.getNameAsString() + "(" + params + ")";
    }

    static String normalizeTypeName(String type) {
        if (type == null || type.isBlank()) {
            return type;
        }
        int genericIdx = type.indexOf('<');
        if (genericIdx > 0) {
            type = type.substring(0, genericIdx);
        }
        int dotIdx = type.lastIndexOf('.');
        if (dotIdx > 0) {
            type = type.substring(dotIdx + 1);
        }
        return type;
    }
}
