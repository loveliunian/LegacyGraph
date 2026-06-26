package io.github.legacygraph.common;

import lombok.Data;

/**
 * 分页查询参数
 */
@Data
public class PageQuery {

    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String keyword;
}
