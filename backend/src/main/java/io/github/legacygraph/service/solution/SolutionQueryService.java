package io.github.legacygraph.service.solution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.Solution;
import io.github.legacygraph.entity.SolutionStep;
import io.github.legacygraph.repository.SolutionRepository;
import io.github.legacygraph.repository.SolutionStepRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 方案查询服务 — 封装 Solution / SolutionStep 的读取逻辑，
 * 供 Agent 层使用，避免 Agent 直接依赖 Repository。
 */
@Service
public class SolutionQueryService {

    private final SolutionRepository solutionRepository;
    private final SolutionStepRepository stepRepository;

    public SolutionQueryService(SolutionRepository solutionRepository,
                                 SolutionStepRepository stepRepository) {
        this.solutionRepository = solutionRepository;
        this.stepRepository = stepRepository;
    }

    public Solution findById(String solutionId) {
        return solutionRepository.selectById(solutionId);
    }

    public List<SolutionStep> findSteps(String solutionId) {
        LambdaQueryWrapper<SolutionStep> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SolutionStep::getSolutionId, solutionId)
                .orderByAsc(SolutionStep::getStepIndex);
        return stepRepository.selectList(wrapper);
    }
}
