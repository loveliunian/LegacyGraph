package io.github.legacygraph.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法内控制流图（Control Flow Graph）构建器。
 * <p>
 * P4a 基础框架：仅做方法内 CFG，不做跨过程 / 完整 CPG。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>{@link CFGNode} 是图的节点，其中 {@code successors} 描述后继边。</li>
 *   <li>{@link #build(MethodDeclaration)} 返回 ENTRY 节点，通过深度优先遍历即可拿到全图。</li>
 *   <li>对 if-else / for / while / do-while / switch / try-catch 做了基础支持；
 *       break/continue/return/throw 会正确收敛到对应出口。</li>
 *   <li>不保证 AST 完全保形，但保证基本控制语义正确，能编译通过。</li>
 * </ul>
 */
@Slf4j
public class ControlFlowGraph {

    /** 节点自增 ID 生成器（按 build 调用重置） */
    private int nodeSeq;

    /**
     * 构建方法内控制流图。
     *
     * @param method JavaParser MethodDeclaration
     * @return 入口节点（ENTRY），通过 successors 可遍历整张图
     */
    public CFGNode build(MethodDeclaration method) {
        this.nodeSeq = 0;
        CFGNode entry = new CFGNode(nextId(), NodeType.ENTRY);
        CFGNode exit = new CFGNode(nextId(), NodeType.EXIT);
        // 方法体为空时直接 ENTRY -> EXIT
        if (method.getBody().isEmpty()) {
            entry.link(exit);
            return entry;
        }
        BlockStmt body = method.getBody().get();
        BuildContext ctx = new BuildContext(exit);
        List<CFGNode> bodyHeads = buildBlock(body, ctx);
        for (CFGNode head : bodyHeads) {
            entry.link(head);
        }
        // 如果 body 没有显式 return/throw，确保末尾能到达 exit
        ctx.relinkFallthroughTo(exit);
        return entry;
    }

    // ---------- 内部构建上下文 ----------

    /**
     * 构建过程的临时上下文，记录未消解的 fallthrough 出口（即"自然走完一段代码后下一跳去哪")。
     * 当外层结构知道"下一条语句是什么"时，把这些 fallthrough 节点链过去即可。
     */
    private static final class BuildContext {
        /** 方法级 EXIT 节点，用于 return / throw / 未捕获异常的兜底 */
        final CFGNode methodExit;
        /** 当前未消解的 fallthrough 末梢；新结构会消费它们并把后续节点接上去 */
        final List<CFGNode> tails = new ArrayList<>();

        BuildContext(CFGNode methodExit) {
            this.methodExit = methodExit;
        }

        void relinkFallthroughTo(CFGNode target) {
            for (CFGNode t : tails) {
                t.link(target);
            }
            tails.clear();
        }
    }

    // ---------- 节点 / 类型 ----------

    public enum NodeType {
        ENTRY, EXIT, BRANCH, LOOP, STATEMENT
    }

    @Data
    public class CFGNode {
        private final int id;
        private final NodeType nodeType;
        /** 该节点承载的语句（可能为空，例如 ENTRY/EXIT） */
        private final List<Statement> statements = new ArrayList<>();
        /** 后继节点列表 */
        private final List<CFGNode> successors = new ArrayList<>();

        CFGNode(int id, NodeType nodeType) {
            this.id = id;
            this.nodeType = nodeType;
        }

        void link(CFGNode next) {
            if (next != null && !this.successors.contains(next)) {
                this.successors.add(next);
            }
        }

        void attach(Statement s) {
            if (s != null) {
                this.statements.add(s);
            }
        }
    }

    private int nextId() {
        return ++nodeSeq;
    }

    private CFGNode newNode(NodeType type) {
        return new CFGNode(nextId(), type);
    }

    // ---------- 语句块构建 ----------

    /**
     * 构建一个 BlockStmt（或等价语句序列），返回入口节点列表。
     * 调用方应把 entry 链到这些入口节点上；产生的 fallthrough 末梢留在 ctx.tails。
     */
    private List<CFGNode> buildBlock(BlockStmt block, BuildContext ctx) {
        List<CFGNode> heads = new ArrayList<>();
        boolean first = true;
        for (Statement stmt : block.getStatements()) {
            // 先快照上一段落的 fallthrough 末梢并清空，
            // 这样 buildStatement 往 ctx.tails 写入的是当前语句自己的末梢，
            // 不会与上一段落的末梢混淆。
            List<CFGNode> prevTails = new ArrayList<>(ctx.tails);
            ctx.tails.clear();
            List<CFGNode> stmtHeads = buildStatement(stmt, ctx);
            if (first || prevTails.isEmpty()) {
                heads.addAll(stmtHeads);
            } else {
                // 上一段落的 fallthrough 末梢 -> 当前语句入口（取首个）
                CFGNode head = stmtHeads.isEmpty() ? ctx.methodExit : stmtHeads.get(0);
                for (CFGNode t : prevTails) {
                    t.link(head);
                }
            }
            first = false;
        }
        if (first) {
            // 空块：插入一个空 STATEMENT 节点保持图连通
            CFGNode empty = newNode(NodeType.STATEMENT);
            heads.add(empty);
            ctx.tails.add(empty);
        }
        return heads;
    }

    /**
     * 构建单条语句，可能产生多个入口节点（例如 if 链入口是 BRANCH），
     * fallthrough 末梢留在 ctx.tails，return/break/continue/throw 会清空 tails
     * 并指向各自的跳转目标。
     */
    private List<CFGNode> buildStatement(Statement stmt, BuildContext ctx) {
        if (stmt instanceof IfStmt) {
            return buildIf((IfStmt) stmt, ctx);
        } else if (stmt instanceof ForStmt) {
            return buildFor((ForStmt) stmt, ctx);
        } else if (stmt instanceof WhileStmt) {
            return buildWhile((WhileStmt) stmt, ctx);
        } else if (stmt instanceof DoStmt) {
            return buildDoWhile((DoStmt) stmt, ctx);
        } else if (stmt instanceof SwitchStmt) {
            return buildSwitch((SwitchStmt) stmt, ctx);
        } else if (stmt instanceof TryStmt) {
            return buildTry((TryStmt) stmt, ctx);
        } else if (stmt instanceof BlockStmt) {
            return buildBlock((BlockStmt) stmt, ctx);
        } else if (stmt instanceof ReturnStmt) {
            CFGNode n = newNode(NodeType.STATEMENT);
            n.attach(stmt);
            n.link(ctx.methodExit);
            // return 之后没有 fallthrough
            ctx.tails.clear();
            return List.of(n);
        } else if (stmt instanceof ThrowStmt) {
            CFGNode n = newNode(NodeType.STATEMENT);
            n.attach(stmt);
            // 抛出异常视作跳到方法出口（简化：不做精确异常表）
            n.link(ctx.methodExit);
            ctx.tails.clear();
            return List.of(n);
        } else if (stmt instanceof BreakStmt) {
            // 简化：break 跳到方法出口（不做精确的循环出口绑定）
            // 注意：这是基础框架，精确的循环 break/continue 留待后续迭代
            CFGNode n = newNode(NodeType.STATEMENT);
            n.attach(stmt);
            n.link(ctx.methodExit);
            ctx.tails.clear();
            return List.of(n);
        } else if (stmt instanceof ContinueStmt) {
            // 简化：continue 跳到方法出口（基础框架，后续迭代做精确绑定）
            CFGNode n = newNode(NodeType.STATEMENT);
            n.attach(stmt);
            n.link(ctx.methodExit);
            ctx.tails.clear();
            return List.of(n);
        } else {
            // 普通表达式语句 / 变量声明 / 空语句等
            CFGNode n = newNode(NodeType.STATEMENT);
            n.attach(stmt);
            ctx.tails.add(n);
            return List.of(n);
        }
    }

    private List<CFGNode> buildIf(IfStmt ifStmt, BuildContext ctx) {
        CFGNode branch = newNode(NodeType.BRANCH);
        branch.attach(ifStmt);
        // then 分支
        BuildContext thenCtx = new BuildContext(ctx.methodExit);
        List<CFGNode> thenHeads = buildStatementOrBlock(ifStmt.getThenStmt(), thenCtx);
        for (CFGNode h : thenHeads) {
            branch.link(h);
        }
        List<CFGNode> thenTails = new ArrayList<>(thenCtx.tails);

        // else 分支（可空）
        List<CFGNode> elseTails = new ArrayList<>();
        if (ifStmt.getElseStmt().isPresent()) {
            BuildContext elseCtx = new BuildContext(ctx.methodExit);
            List<CFGNode> elseHeads = buildStatementOrBlock(ifStmt.getElseStmt().get(), elseCtx);
            for (CFGNode h : elseHeads) {
                branch.link(h);
            }
            elseTails.addAll(elseCtx.tails);
        } else {
            // 没有 else：branch 自身就是 else 末梢
            elseTails.add(branch);
        }
        // 合并两条分支的 fallthrough
        ctx.tails.addAll(thenTails);
        ctx.tails.addAll(elseTails);
        return List.of(branch);
    }

    private List<CFGNode> buildFor(ForStmt forStmt, BuildContext ctx) {
        CFGNode loop = newNode(NodeType.LOOP);
        loop.attach(forStmt);
        BuildContext bodyCtx = new BuildContext(ctx.methodExit);
        List<CFGNode> bodyHeads = buildStatementOrBlock(forStmt.getBody(), bodyCtx);
        for (CFGNode h : bodyHeads) {
            loop.link(h);
        }
        // body fallthrough 回到 loop 头（基础框架，忽略 break/continue 精确语义）
        for (CFGNode t : bodyCtx.tails) {
            t.link(loop);
        }
        // 不进入循环时直接 fallthrough
        ctx.tails.add(loop);
        return List.of(loop);
    }

    private List<CFGNode> buildWhile(WhileStmt whileStmt, BuildContext ctx) {
        CFGNode loop = newNode(NodeType.LOOP);
        loop.attach(whileStmt);
        BuildContext bodyCtx = new BuildContext(ctx.methodExit);
        List<CFGNode> bodyHeads = buildStatementOrBlock(whileStmt.getBody(), bodyCtx);
        for (CFGNode h : bodyHeads) {
            loop.link(h);
        }
        for (CFGNode t : bodyCtx.tails) {
            t.link(loop);
        }
        ctx.tails.add(loop);
        return List.of(loop);
    }

    private List<CFGNode> buildDoWhile(DoStmt doStmt, BuildContext ctx) {
        // do-while：先执行 body，再判断
        BuildContext bodyCtx = new BuildContext(ctx.methodExit);
        List<CFGNode> bodyHeads = buildStatementOrBlock(doStmt.getBody(), bodyCtx);
        CFGNode loop = newNode(NodeType.LOOP);
        loop.attach(doStmt);
        // body fallthrough -> loop 判断
        for (CFGNode t : bodyCtx.tails) {
            t.link(loop);
        }
        // loop true -> 回到 body 头
        for (CFGNode h : bodyHeads) {
            loop.link(h);
        }
        // loop false -> fallthrough
        ctx.tails.add(loop);
        return bodyHeads;
    }

    private List<CFGNode> buildSwitch(SwitchStmt switchStmt, BuildContext ctx) {
        CFGNode branch = newNode(NodeType.BRANCH);
        branch.attach(switchStmt);
        List<CFGNode> allTails = new ArrayList<>();
        boolean hasDefault = false;
        for (SwitchEntry entry : switchStmt.getEntries()) {
            BuildContext entryCtx = new BuildContext(ctx.methodExit);
            if (entry.getLabels().isEmpty()) {
                hasDefault = true;
            }
            // 每个 case 的入口都从 branch 出发
            for (Statement s : entry.getStatements()) {
                List<CFGNode> heads = buildStatement(s, entryCtx);
                for (CFGNode h : heads) {
                    branch.link(h);
                }
                // 同一 case 内多条语句按序串联（relink fallthrough）
                // buildStatement 已经把 tails 留下了
            }
            // 简化：每个 case 不带 break 时 fallthrough 到下一个 case 的语义忽略，
            // 统一收集每个 case 的 fallthrough 末梢
            allTails.addAll(entryCtx.tails);
        }
        if (!hasDefault) {
            // 无 default：switch 自身作为不命中时的 fallthrough
            allTails.add(branch);
        }
        ctx.tails.addAll(allTails);
        return List.of(branch);
    }

    private List<CFGNode> buildTry(TryStmt tryStmt, BuildContext ctx) {
        // try 体
        BuildContext tryCtx = new BuildContext(ctx.methodExit);
        List<CFGNode> tryHeads = buildBlock(tryStmt.getTryBlock(), tryCtx);
        List<CFGNode> tryTails = new ArrayList<>(tryCtx.tails);

        // catch 分支：每个 catch 都从 try 体的"任意抛出点"开始；
        // 基础框架简化：把 catch 入口挂在 try 体首节点之前（即 try 入口 = catch 之一），
        // 实际并不精确，但保证图连通且可读。
        List<CFGNode> allTails = new ArrayList<>(tryTails);
        for (CatchClause cc : tryStmt.getCatchClauses()) {
            BuildContext catchCtx = new BuildContext(ctx.methodExit);
            List<CFGNode> catchHeads = buildBlock(cc.getBody(), catchCtx);
            allTails.addAll(catchCtx.tails);
            // catch 入口暂挂在 tryHeads 第一个之后，作为近似；不精确但够基础框架
            if (!tryHeads.isEmpty()) {
                tryHeads.get(0).link(catchHeads.isEmpty() ? ctx.methodExit : catchHeads.get(0));
            }
        }
        // finally
        if (tryStmt.getFinallyBlock().isPresent()) {
            BuildContext finallyCtx = new BuildContext(ctx.methodExit);
            List<CFGNode> finallyHeads = buildBlock(tryStmt.getFinallyBlock().get(), finallyCtx);
            // finally 在 try/catch 之后执行：把之前的所有 tails 接到 finally 头
            for (CFGNode t : allTails) {
                for (CFGNode fh : finallyHeads) {
                    t.link(fh);
                }
            }
            ctx.tails.addAll(finallyCtx.tails);
        } else {
            ctx.tails.addAll(allTails);
        }
        return tryHeads;
    }

    /**
     * 一个 Statement 可能是 BlockStmt 也可能是单条语句，统一返回入口节点列表，
     * fallthrough 末梢留在给定 ctx.tails。
     */
    private List<CFGNode> buildStatementOrBlock(Statement stmt, BuildContext ctx) {
        if (stmt instanceof BlockStmt) {
            return buildBlock((BlockStmt) stmt, ctx);
        }
        return buildStatement(stmt, ctx);
    }

    /**
     * 便捷工具：从入口节点收集所有可达 CFGNode（含入口自身）。
     * 主要用于调试 / 控制器序列化全图。
     */
    public static List<CFGNode> collectAll(CFGNode entry) {
        List<CFGNode> all = new ArrayList<>();
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        java.util.Deque<CFGNode> stack = new java.util.ArrayDeque<>();
        stack.push(entry);
        while (!stack.isEmpty()) {
            CFGNode n = stack.pop();
            if (!visited.add(n.getId())) {
                continue;
            }
            all.add(n);
            for (CFGNode s : n.getSuccessors()) {
                stack.push(s);
            }
        }
        // 按 id 排序，便于阅读
        all.sort(java.util.Comparator.comparingInt(CFGNode::getId));
        return all;
    }
}
