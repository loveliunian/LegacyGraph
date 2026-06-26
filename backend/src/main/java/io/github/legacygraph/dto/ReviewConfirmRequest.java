package io.github.legacygraph.dto;

import lombok.Data;

/**
 * 人工确认请求DTO
 */
@Data
public class ReviewConfirmRequest {

    private String targetType; // NODE / EDGE
    private String targetId;
    private String reviewStatus; // CONFIRMED / REJECTED
    private String comment;
}
