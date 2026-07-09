package io.github.legacygraph.extractors;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring XML Bean 依赖抽取器。
 * <p>
 * 解析 Spring XML 配置文件（如 applicationContext.xml, spring-*.xml），提取 {@code <bean>}
 * 元素中的依赖注入关系（property ref / constructor-arg ref）。
 * </p>
 *
 * <h3>抽取流程</h3>
 * <ol>
 *   <li>第一遍扫描：收集当前文件所有 {@code <bean id="..." class="...">} 定义，建立 id→class 映射</li>
 *   <li>第二遍扫描：解析每个 bean 的 {@code <property>} / {@code <constructor-arg>} 中的 ref 引用，
 *       若 ref 目标 bean 在当前文件中定义则填充 refBeanClass，否则 refBeanClass=null
 *       （交给 JavaMemberCallResolver 反向解析）</li>
 * </ol>
 */
@Slf4j
public class SpringXmlBeanExtractor {

    /**
     * Spring Bean 依赖关系。
     */
    @Data
    public static class SpringBeanDependency {
        /** 注入发起方 bean id */
        private final String beanId;
        /** 注入发起方 bean class（FQN） */
        private final String beanClass;
        /** 被引用的 bean id */
        private final String refBeanId;
        /** 被引用的 bean class（FQN），若 ref 目标不在当前文件中则为 null */
        private final String refBeanClass;
        /** 注入类型：property / constructor */
        private final String injectionType;
        /** 属性名（constructor 注入时为 null） */
        private final String propertyName;
    }

    /**
     * 从 Spring XML 配置文件抽取 Bean 依赖关系。
     */
    public List<SpringBeanDependency> extractFromFile(File xmlFile) {
        List<SpringBeanDependency> dependencies = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 禁用外部 DTD/实体加载：Spring XML 的 DOCTYPE 指向 springframework.org/dtd/...，
            // 离线或网络受限环境下会阻塞解析；同时防止 XXE 注入。与 MyBatisXmlExtractor 一致。
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            // 第一遍：收集当前文件所有 bean 定义 id→class
            Map<String, String> beanClassMap = collectBeanDefinitions(doc);

            // 第二遍：解析每个 bean 的 property / constructor-arg 引用
            NodeList beanNodes = doc.getElementsByTagName("bean");
            for (int i = 0; i < beanNodes.getLength(); i++) {
                Element beanEl = (Element) beanNodes.item(i);
                String beanId = beanEl.getAttribute("id");
                if (beanId == null || beanId.isEmpty()) {
                    // bean 没有 id（如内部 bean 或 name-only），跳过依赖提取
                    continue;
                }
                String beanClass = beanEl.getAttribute("class");
                collectPropertyRefs(beanEl, beanId, beanClass, beanClassMap, dependencies);
                collectConstructorArgRefs(beanEl, beanId, beanClass, beanClassMap, dependencies);
            }
        } catch (Exception e) {
            log.error("Failed to parse Spring XML: {}", xmlFile, e);
        }
        return dependencies;
    }

    /**
     * 收集所有 {@code <bean id="..." class="...">} 定义，返回 id → class 映射。
     */
    private Map<String, String> collectBeanDefinitions(Document doc) {
        Map<String, String> map = new LinkedHashMap<>();
        NodeList beanNodes = doc.getElementsByTagName("bean");
        for (int i = 0; i < beanNodes.getLength(); i++) {
            Element el = (Element) beanNodes.item(i);
            String id = el.getAttribute("id");
            String clazz = el.getAttribute("class");
            if (id != null && !id.isEmpty() && clazz != null && !clazz.isEmpty()) {
                map.put(id, clazz);
            }
        }
        return map;
    }

    /**
     * 解析 {@code <property name="xxx" ref="zzz"/>} 或
     * {@code <property name="xxx"><ref bean="zzz"/></property>}。
     * 仅处理 bean 的直接子元素，避免嵌套（内部）bean 的 property 误归属。
     */
    private void collectPropertyRefs(Element beanEl, String beanId, String beanClass,
                                      Map<String, String> beanClassMap,
                                      List<SpringBeanDependency> dependencies) {
        NodeList propNodes = beanEl.getElementsByTagName("property");
        for (int i = 0; i < propNodes.getLength(); i++) {
            Element propEl = (Element) propNodes.item(i);
            if (propEl.getParentNode() != beanEl) {
                continue;
            }
            String propName = propEl.getAttribute("name");
            String refId = resolveRef(propEl);
            if (refId == null || refId.isEmpty()) {
                continue;
            }
            dependencies.add(buildDependency(beanId, beanClass, refId, "property", propName, beanClassMap));
        }
    }

    /**
     * 解析 {@code <constructor-arg ref="zzz"/>} 或
     * {@code <constructor-arg><ref bean="zzz"/></constructor-arg>}。
     * 仅处理 bean 的直接子元素。
     */
    private void collectConstructorArgRefs(Element beanEl, String beanId, String beanClass,
                                            Map<String, String> beanClassMap,
                                            List<SpringBeanDependency> dependencies) {
        NodeList ctorNodes = beanEl.getElementsByTagName("constructor-arg");
        for (int i = 0; i < ctorNodes.getLength(); i++) {
            Element ctorEl = (Element) ctorNodes.item(i);
            if (ctorEl.getParentNode() != beanEl) {
                continue;
            }
            String refId = resolveRef(ctorEl);
            if (refId == null || refId.isEmpty()) {
                continue;
            }
            dependencies.add(buildDependency(beanId, beanClass, refId, "constructor", null, beanClassMap));
        }
    }

    /**
     * 从元素中解析 ref 目标 bean id：
     * 优先取 ref 属性（{@code <property ref="zzz"/>} / {@code <constructor-arg ref="zzz"/>}），
     * 否则查找子元素 {@code <ref bean="zzz"/>}。
     */
    private String resolveRef(Element el) {
        String refAttr = el.getAttribute("ref");
        if (refAttr != null && !refAttr.isEmpty()) {
            return refAttr;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "ref".equals(child.getNodeName())) {
                String beanRef = ((Element) child).getAttribute("bean");
                if (beanRef != null && !beanRef.isEmpty()) {
                    return beanRef;
                }
            }
        }
        return null;
    }

    /**
     * 构建依赖项，若 ref 目标 bean 在当前文件定义则填充 refBeanClass，否则为 null。
     */
    private SpringBeanDependency buildDependency(String beanId, String beanClass, String refBeanId,
                                                  String injectionType, String propertyName,
                                                  Map<String, String> beanClassMap) {
        String refBeanClass = beanClassMap.get(refBeanId);
        return new SpringBeanDependency(beanId, beanClass, refBeanId, refBeanClass, injectionType, propertyName);
    }
}
