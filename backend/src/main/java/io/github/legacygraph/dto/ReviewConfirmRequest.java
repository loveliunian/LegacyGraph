package io.github.legacygraph.dto;

import lombok.Data;

@Data
public class ReviewConfirmRequest {

    private String targetType;

    private String targetId;

    private String reviewStatus;

    private String comment;
}
