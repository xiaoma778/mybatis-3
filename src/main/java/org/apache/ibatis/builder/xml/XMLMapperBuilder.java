/**
 * Copyright 2009-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * 解析映射 Mapper 文件
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

    private final XPathParser parser;
    private final MapperBuilderAssistant builderAssistant;
    private final Map<String, XNode> sqlFragments;
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
            configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
            configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    /**
     * 解析映射 Mapper 配置文件
     */
    public void parse() {
        //判断是否已经加载过该映射文件
        if (!configuration.isResourceLoaded(resource)) {
            configurationElement(parser.evalNode("/mapper"));
            configuration.addLoadedResource(resource);
            //注册 Mapper 接口
            bindMapperForNamespace();
        }

        // 注：在调用 configurationElement() 方法解析映射配置文件时，是按照从文件头到文件尾的顺序解析的，但是
        // 有时候在解析一个节点时，会引用定义子该节点之后的、还未解析到的节点，这就会导致解析失败并抛出
        // IncompleteElementException 异常。所以才有了下面操作。

        // 处理 configurationElement 方法中解析失败的 <resultMap> 节点
        parsePendingResultMaps();
        // 处理 configurationElement 方法中解析失败的 <cache-ref> 节点
        parsePendingCacheRefs();
        // 处理 configurationElement 方法中解析失败的 SQL 节点
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析 Mapper 文件
     * @param context
     */
    private void configurationElement(XNode context) {
        try {
            //获取 <mapper> 节点的 namespace 属性
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.isEmpty()) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            //设置 MapperBuilderAssistant 的 currentNamespace 字段，记录当前命名空间
            builderAssistant.setCurrentNamespace(namespace);
            //解析 <cache-ref> 节点
            cacheRefElement(context.evalNode("cache-ref"));
            //解析 <cache> 节点
            cacheElement(context.evalNode("cache"));
            //解析 <parameterMap> 节点（该节点已废弃，不再推荐使用）
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            //解析 <resultMap> 节点
            resultMapElements(context.evalNodes("/mapper/resultMap"));
            //解析 <sql> 节点
            sqlElement(context.evalNodes("/mapper/sql"));
            //解析 <select>、<insert>、<update>、<delete> 等 SQL 节点
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    /**
     * 如果我们希望多个 namespace 共用一个二级缓存（即同一个 Cache 对象）则可以使用 cache-ref 节点来配置
     * 该方法就是对 cache-ref 节点进行解析
     * @param context
     */
    private void cacheRefElement(XNode context) {
        if (context != null) {
            //将当前 Mapper 配置文件的 namespace 与被引用的 Cache 所在的 namespace 之间的对应关系记录到
            //Configuration.cacheRefMap 集合中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            //创建 CacheRefResolver 对象
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                //解析 Cache 引用，该过程主要是设置 MapperBuilderAssistant 中的 currentCache 和 unresolvedCacheRef 字段
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                //如果解析过程出现异常，则添加到 configuration.incompleteCacheRefs 集合，稍后再解析
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    /**
     * 解析 cache 节点，例（具体 cache 节点信息请参考官网）：
     * 为每个 namespace 创建一个对应的 Cache 对象，并在 Configuration.caches 集合中记录 namespace 与 Cache 对象之间的对应关系
     * <cache type="org.apache.ibatis.submitted.global_variables.CustomCache" blocking="" eviction="org.apache.ibatis.cache.decorators.LruCache" flushInterval="" readOnly="" size="100">
     *      <property name="stringValue" value="${stringProperty}"/>
     *      <property name="integerValue" value="${integerProperty}"/>
     *      <property name="longValue" value="${longProperty}"/>
     * </cache>
     * @param context
     */
    private void cacheElement(XNode context) {
        if (context != null) {
            //获取 <cache> 节点的 type 属性，默认值是 PERPETUAL
            String type = context.getStringAttribute("type", "PERPETUAL");

            //查找 type 属性对应的 Cache 接口实现
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

            //获取 eviction 属性（缓存驱逐方式），默认值是 LRU
            String eviction = context.getStringAttribute("eviction", "LRU");

            //解析 eviction 属性指定的 Cache 装饰器类型
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

            //获取 <cache> 节点的 flushInterval（缓存刷新间隔）属性，默认值是 null
            Long flushInterval = context.getLongAttribute("flushInterval");

            //获取 <cache> 节点的 size（缓存大小）属性，默认值是 null
            Integer size = context.getIntAttribute("size");

            //获取 <cache> 节点的 readOnly 属性，默认值是 false
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);

            //获取 <cache> 节点的 blocking 属性，默认值是 false
            boolean blocking = context.getBooleanAttribute("blocking", false);

            //获取 <cache> 节点下的子节点，将用于初始化二级缓存
            Properties props = context.getChildrenAsProperties();

            //通过 MapperBuilderAssistant 创建 Cache 对象，并添加到 Configuration.caches 集合中保存
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    private void resultMapElements(List<XNode> list) {
        for (XNode resultMapNode : list) {
            try {
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) {
        return resultMapElement(resultMapNode, Collections.emptyList(), null);
    }

    /**
     * 解析 Mapper 配置文件中的全部 <resultMap> 节点
     * @param resultMapNode
     * @param additionalResultMappings
     * @param enclosingType
     * @return
     */
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        //获取 <resultMap> 节点的 type 属性，表示结果集将被映射成 type 指定类型的对象，注意其默认值
        String type = resultMapNode.getStringAttribute("type",
            resultMapNode.getStringAttribute("ofType",
                resultMapNode.getStringAttribute("resultType",
                    resultMapNode.getStringAttribute("javaType"))));
        //解析 type 类型
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;
        //该集合用于记录解析的结果
        List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
        //处理 <resultMap> 的子节点
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            //处理 <constructor> 节点
            if ("constructor".equals(resultChild.getName())) {
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                //处理 <discriminator> 节点
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                //处理 <id>、<result>、<association>、<collection> 等节点
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    //如果是 <id> 节点，则向 flags 集合中添加 ResultFlag.ID
                    flags.add(ResultFlag.ID);
                }
                //创建 ResultMapping 对象，并添加到 resultMappings 集合中保存
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        //获取 <resultMap> 的 id 属性，默认值会拼装所有父节点的 id 或 value 或 Property 属性值
        String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());
        //获取  <resultMap>  节点的 extends 属性，该属性指定了该 <resultMap> 节点的继承关系
        String extend = resultMapNode.getStringAttribute("extends");
        //读取 <resultMap> 节点的 autoMapping 属性，将该属性设置为 true，则启动自动映射功能，即自动查找与列名同名
        //的属性名，并调用 setter 方法。而设置为 false 后，则需要在 <resultMap> 节点内明确注明映射关系才会调用对应
        //的 setter 方法
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            //创建 ResultMap 对象，并添加到 Configuration.resultMaps 集合中，该集合是 StrictMap 类型
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    /**
     * 处理 Mapper 配置文件中 resultMap 节点中 constructor 子节点信息
     * @param resultChild
     * @param resultType
     * @param resultMappings
     */
    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
        // 获取 <constructor> 节点的子节点
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<>();
            // 添加 CONSTRUCTOR 标志
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                //对于 <idArg> 节点，添加 ID 标志
                flags.add(ResultFlag.ID);
            }
            //创建 ResultMapping 对象，并添加到 resultMappings 集合中
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    /**
     * 处理 Mapper 配置文件中 resultMap 节点中 discriminator 子节点信息
     * @param context
     * @param resultType
     * @param resultMappings
     * @return
     */
    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
        // 获取 column、javaType、jdbcType、typeHandler 属性
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

        // 处理 <discriminator> 节点的子节点
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            // 调用 processNestedResultMappings() 方法创建嵌套的 ResultMap 对象
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
            // 记录该列值月对应选择的 ResultMap 的 Id
            discriminatorMap.put(value, resultMap);
        }
        // 创建 Discriminator 对象
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    /**
     * 解析映射配置文件中定义的全部 sql 节点
     * @param list
     */
    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    /**
     * 解析映射配置文件中定义的全部 sql 节点
     * @param list
     * @param requiredDatabaseId
     */
    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        // 遍历 <sql> 节点
        for (XNode context : list) {
            // 获取 databaseId、id 属性
            String databaseId = context.getStringAttribute("databaseId");
            String id = context.getStringAttribute("id");
            // 为 id 添加命名空间
            id = builderAssistant.applyCurrentNamespace(id, false);
            // 检测 <sql> 的 databaseId 与当前 Configuration 中记录的 databaseId 是否一致
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            return requiredDatabaseId.equals(databaseId);
        }
        if (databaseId != null) {
            return false;
        }
        if (!this.sqlFragments.containsKey(id)) {
            return true;
        }
        // skip this fragment if there is a previous one with a not null databaseId
        XNode context = this.sqlFragments.get(id);
        return context.getStringAttribute("databaseId") == null;
    }

    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
        // 获取该节点的 property 的属性值
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        // 获取 column、javaType、jdbcType、select 等属性值
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        // 如果未指定 <association> 节点的 resultMap 属性，则是匿名的嵌套映射，需要通过
        // processNestedResultMappings() 方法解析该匿名的嵌套映射
        String nestedResultMap = context.getStringAttribute("resultMap", () ->
            processNestedResultMappings(context, Collections.emptyList(), resultType));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));

        // 解析 javaType、TypeHandler 和 jdbcType
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    /**
     * 处理 Mapper 配置文件中 resultMap 节点中 association 子节点信息
     * @param context
     * @param resultMappings
     * @param enclosingType
     * @return
     */
    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
        //只会处理 <association>、<collection>、<case> 三种节点
        if ("association".equals(context.getName())
            || "collection".equals(context.getName())
            || "case".equals(context.getName())) {
            //指定了 select 属性之后，不会生成嵌套的 ResultMap 对象
            if (context.getStringAttribute("select") == null) {
                validateCollection(context, enclosingType);
                // 创建 ResultMap 对象，并添加到 Configuration.resultMaps 集合中。注意，如果 <association>
                // 节点没有指明 id 属性的话，其 id 将由 XNode.getValueBasedIdentifier() 方法生成。
                // 生成格式为："mapper_resultMap[当前 <resultMap> 节点 id 属性值]_association[<association>节点的 property 值]"
                // 见 MyBatis 技术内幕-Page.192
                ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
            && context.getStringAttribute("javaType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                    "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
            }
        }
    }

    /**
     * 注册 Mapper 接口
     */
    private void bindMapperForNamespace() {
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    configuration.addLoadedResource("namespace:" + namespace);
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
