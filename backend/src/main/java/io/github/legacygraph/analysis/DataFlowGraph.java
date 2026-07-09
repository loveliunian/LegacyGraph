package io.github.legacygraph.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方法内数据流图（Data Flow Graph）分析器。
 * <p>
 * P4a 基础框架：基于 CFG 思路做最简单的"变量赋值链"追踪。
 * <p>
 * 当前实现的覆盖范围（有意保持简单）：
 * <ul>
 *   <li>{@code ASSIGN}：形如 {@code a = expr} / {@code a = b.getX()} / {@code a = b + c}，
 *       会从 RHS 中抽取出所有 NameExpr 作为 fromVar，写入到 LHS（toVar）。</li>
 *   <li>{@code PARAM}：方法形参作为初始定义点，fromVar 固定为 {@link #PARAM_SOURCE}。</li>
 *   <li>{@code RETURN}：{@code return expr}，把 expr 中出现的变量流向 {@link #RETURN_SINK}。</li>
 * </ul>
 * <p>
 * 不做：过程间数据流、数组/字段精确别名、def-use 链路可达性分析、
 * 循环内的迭代数据流。这些属于 P4a 后续迭代范围。
 */
@Slf4j
public class DataFlowGraph {

    /** 方法入参的虚拟来源（用于 PARAM 边） */
    public static final String PARAM_SOURCE = "<PARAM>";
    /** 方法返回的虚拟汇点（用于 RETURN 边） */
    public static final String RETURN_SINK = "<RETURN>";

    /**
     * 分析方法内的数据流。
     *
     * @param method     JavaParser MethodDeclaration
     * @param varToType  变量名 → 类型名（可空，仅作元信息，当前实现不依赖它）
     * @return 数据流边列表
     */
    public List<DataFlowEdge> analyze(MethodDeclaration method, Map<String, String> varToType) {
        List<DataFlowEdge> edges = new ArrayList<>();
        if (method == null) {
            return edges;
        }

        // 1) 入参：每个参数一条 PARAM 边
        for (Parameter p : method.getParameters()) {
            int line = p.getBegin().map(b -> b.line).orElse(0);
            edges.add(new DataFlowEdge(PARAM_SOURCE, p.getNameAsString(), Operation.PARAM, line));
        }

        // 2) 方法体内的赋值与返回
        method.getBody().ifPresent(body -> {
            // 赋值：a = expr
            body.findAll(AssignExpr.class).forEach(assign -> {
                int line = assign.getBegin().map(b -> b.line).orElse(0);
                String toVar = varNameOf(assign.getTarget());
                if (toVar == null) {
                    return; // 目标不是简单变量（如 this.x = ...），跳过
                }
                List<String> fromVars = extractSourceVars(assign.getValue());
                if (fromVars.isEmpty()) {
                    // 没有可识别的源变量，仍记一条占位边，便于下游可视化
                    edges.add(new DataFlowEdge("<LITERAL>", toVar, Operation.ASSIGN, line));
                } else {
                    for (String from : fromVars) {
                        edges.add(new DataFlowEdge(from, toVar, Operation.ASSIGN, line));
                    }
                }
            });

            // 变量声明带初始化：Type a = expr;
            body.findAll(VariableDeclarationExpr.class).forEach(vde -> {
                vde.getVariables().forEach(v -> {
                    if (!v.getInitializer().isPresent()) {
                        return;
                    }
                    int line = v.getBegin().map(b -> b.line).orElse(0);
                    String toVar = v.getNameAsString();
                    List<String> fromVars = extractSourceVars(v.getInitializer().get());
                    if (fromVars.isEmpty()) {
                        edges.add(new DataFlowEdge("<LITERAL>", toVar, Operation.ASSIGN, line));
                    } else {
                        for (String from : fromVars) {
                            edges.add(new DataFlowEdge(from, toVar, Operation.ASSIGN, line));
                        }
                    }
                });
            });

            // 返回：return expr
            body.findAll(ReturnStmt.class).forEach(ret -> {
                int line = ret.getBegin().map(b -> b.line).orElse(0);
                if (ret.getExpression().isEmpty()) {
                    return;
                }
                List<String> fromVars = extractSourceVars(ret.getExpression().get());
                if (fromVars.isEmpty()) {
                    edges.add(new DataFlowEdge("<LITERAL>", RETURN_SINK, Operation.RETURN, line));
                } else {
                    for (String from : fromVars) {
                        edges.add(new DataFlowEdge(from, RETURN_SINK, Operation.RETURN, line));
                    }
                }
            });
        });

        return edges;
    }

    /**
     * 从表达式中抽取所有源变量名（NameExpr 的 name）。
     * 忽略字段访问（a.b 取 b 而非 a.b），简化为只取最左侧的接收者。
     */
    private List<String> extractSourceVars(Expression expr) {
        List<String> names = new ArrayList<>();
        expr.findAll(NameExpr.class).forEach(ne -> names.add(ne.getNameAsString()));
        return names;
    }

    /**
     * 取一个表达式作为变量名时是否为简单变量。仅支持 NameExpr，
     * 其余（FieldAccessExpr / ArrayAccessExpr 等）返回 null。
     */
    private String varNameOf(Expression expr) {
        if (expr instanceof NameExpr) {
            return ((NameExpr) expr).getNameAsString();
        }
        return null;
    }

    // ---------- 边 / 操作类型 ----------

    public enum Operation {
        /** 赋值：from → to */
        ASSIGN,
        /** 入参定义：<PARAM> → paramName */
        PARAM,
        /** 返回：from → <RETURN> */
        RETURN
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataFlowEdge {
        /** 源变量名（或虚拟标记 {@link #PARAM_SOURCE} / {@link #RETURN_SINK} / {@code <LITERAL>}） */
        private String fromVar;
        /** 目标变量名（或虚拟标记） */
        private String toVar;
        /** 操作类型 */
        private Operation operation;
        /** 所在源码行号（0 表示未知） */
        private Integer lineNumber;
    }
}
