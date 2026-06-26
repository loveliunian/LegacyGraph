package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.PromptRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromptRunRepository extends BaseMapper<PromptRun> {

    @Select("SELECT id FROM lg_prompt_run WHERE input_hash = #{inputHash} AND status = 'success' ORDER BY created_at DESC LIMIT 1")
    Long findCachedRunIdByInputHash(String inputHash);
}
