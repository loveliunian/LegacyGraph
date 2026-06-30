package io.github.legacygraph.extractors.adapter;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

/**
 * 源资产 — 表示一个待扫描的文件或资源。
 */
@Data
@Builder
public class SourceAsset {

    /** 文件路径 */
    private Path file;

    /** 相对路径 */
    private String relativePath;

    /** 文件类型 (java/xml/vue/md/sql 等) */
    private String fileType;

    /** 编程语言 */
    private String language;

    /** 框架 (spring/mybatis/vue 等) */
    private String framework;

    /** 资产大小（字节） */
    private long fileSize;

    public SourceAsset() {}

    public SourceAsset(Path file, String relativePath, String fileType,
                       String language, String framework, long fileSize) {
        this.file = file;
        this.relativePath = relativePath;
        this.fileType = fileType;
        this.language = language;
        this.framework = framework;
        this.fileSize = fileSize;
    }
}
