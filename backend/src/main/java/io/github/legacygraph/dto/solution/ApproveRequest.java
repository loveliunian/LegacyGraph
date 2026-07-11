package io.github.legacygraph.dto.solution;

import lombok.Data;

@Data
public class ApproveRequest {

    private String reviewer;

    private String decision;

    private String comment;
}
