package io.github.legacygraph.service.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 缓存预热器 — 应用启动完成后预加载高频只读数据，消除首次请求冷启动延迟。
 *
 * <p>预热数据：
 * <ul>
 *   <li>系统配置（SysConfigService）— 全局配置项，读取频率极高</li>
 *   <li>数据字典（SysDictService）— 下拉选项、标签映射，几乎每次请求使用</li>
 *   <li>Prompt 模板（PromptTemplateService）— LLM 调用入口必查</li>
 * </ul>
 *
 * <p>所有预热操作 wrap 在 try/catch 中，预热失败不影响应用启动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmer {

    private final SysConfigService sysConfigService;
    private final SysDictService sysDictService;
    private final PromptTemplateService promptTemplateService;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        log.info("Starting cache warm-up...");
        long start = System.currentTimeMillis();

        safeWarm("sysConfig", () -> sysConfigService.getAllConfigMap());
        safeWarm("sysDict", () -> sysDictService.getAllItemMaps());
        safeWarm("promptTemplates", () -> promptTemplateService.listActive());

        long elapsed = System.currentTimeMillis() - start;
        log.info("Cache warm-up completed in {}ms", elapsed);
    }

    private void safeWarm(String name, Runnable loader) {
        try {
            loader.run();
            log.debug("Cache warm-up [{}] OK", name);
        } catch (Exception e) {
            log.warn("Cache warm-up [{}] failed (app will still start): {}", name, e.getMessage());
        }
    }
}
