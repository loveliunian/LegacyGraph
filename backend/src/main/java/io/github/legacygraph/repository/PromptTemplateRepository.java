package io.github.legacygraph.repository;

import io.github.legacygraph.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PromptTemplateRepository extends LegacyBaseMapper<PromptTemplate> {

    @Select("SELECT * FROM lg_prompt_template WHERE template_code = #{templateCode} AND is_active = true ORDER BY version DESC LIMIT 1")
    PromptTemplate findActiveByCode(String templateCode);

    @Select("SELECT * FROM lg_prompt_template WHERE scene = #{scene} AND is_active = true ORDER BY template_code, version DESC")
    List<PromptTemplate> findByScene(String scene);
}
