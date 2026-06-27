package io.github.legacygraph.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.legacygraph.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReportRepository extends BaseMapper<Report> {

    @Select("SELECT * FROM lg_reports WHERE project_id = #{projectId} ORDER BY generated_at DESC")
    List<Report> findByProjectId(String projectId);
}
