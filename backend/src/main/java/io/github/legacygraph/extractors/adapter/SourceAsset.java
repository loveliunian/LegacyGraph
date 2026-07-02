package io.github.legacygraph.extractors.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * 源资产 — 表示一个待扫描的文件或资源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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


}
