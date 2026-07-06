package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.query.GraphifyQuestionRequest;
import io.github.legacygraph.query.GraphifyQuestionAnswer;
import io.github.legacygraph.query.GraphifyQuestionService;
import org.springframework.web.bind.annotation.*;

/**
 * Graphify RAG/MCP 查询控制器
 * 
 * <p>提供图谱问答接口，支持 Agent/RAG 集成。
 * 
 * @author LegacyGraph Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/lg/projects/{projectId}/graphify/questions")
public class GraphifyQuestionController {
    
    private final GraphifyQuestionService questionService;
    
    public GraphifyQuestionController(GraphifyQuestionService questionService) {
        this.questionService = questionService;
    }
    
    /**
     * 图谱问答接口
     * 
     * <p>POST /lg/projects/{projectId}/graphify/questions
     * 
     * @param projectId 项目 ID
     * @param request 问题请求
     * @return 问题回答
     */
    @PostMapping
    public Result<GraphifyQuestionAnswer> askQuestion(
            @PathVariable String projectId,
            @RequestBody GraphifyQuestionRequest request) {
        
        GraphifyQuestionAnswer answer = questionService.answer(request);
        return Result.ok(answer);
    }
}
