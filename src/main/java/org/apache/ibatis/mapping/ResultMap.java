/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * Mapper 配置文件中的每个 <resultMap> 节点都会被解析成一个 ResuleMap 对象，其中每个节点所定义
 * 的映射关系使用 ResultMapping 对象表示。例：
 *
 * <resultMap id="joinedPost" type="org.apache.ibatis.domain.blog.Post">
 *      <id property="id" column="post_id"/>
 *      <result property="subject" column="post_subject"/>
 *      <association property="author" resultMap="joinedAuthor"/>
 *      <collection property="comments" resultMap="joinedComment"/>
 *      <collection property="tags" resultMap="joinedTag"/>
 *      <discriminator javaType="int" column="draft">
 *          <case value="1" resultMap="draftPost"/>
 *      </discriminator>
 * </resultMap>
 * @author Clinton Begin
 */
public class ResultMap {
    private Configuration configuration;

    /** resultMap 节点的 id 属性*/
    private String id;

    /** resultMap 节点的 type 属性*/
    private Class<?> type;

    /** 记录了出 discriminator 节点之外的其他映射关系（即 ResultMapping 对象集合）*/
    private List<ResultMapping> resultMappings;

    /** 记录了映射关系中带有 ID 标志的映射关系，例如 id 节点和 constructor 节点的 idArg 子节点*/
    private List<ResultMapping> idResultMappings;

    /** 记录了映射关系中带有 Constructor 标志的映射关系，例如 constructor 所有子元素*/
    private List<ResultMapping> constructorResultMappings;

    /** 记录了映射关系中带有 property 标志的映射关系 todo：待验证*/
    private List<ResultMapping> propertyResultMappings;

    /** 记录所有映射关系中涉及 column 属性的集合*/
    private Set<String> mappedColumns;

    /** */
    private Set<String> mappedProperties;

    /** 鉴别器，对应 discriminator 节点*/
    private Discriminator discriminator;

    /** 是否含有嵌套的结果映射，如果某个映射关系中存在 resultMap 属性，且不存在 resultSet 属性，则为 true*/
    private boolean hasNestedResultMaps;

    /** 是否含有嵌套查询，如果某个属性映射存在 select 属性，则为 true*/
    private boolean hasNestedQueries;

    /** 是否开启自动映射*/
    private Boolean autoMapping;

    private ResultMap() {
    }

    public static class Builder {
        private static final Log log = LogFactory.getLog(Builder.class);

        private ResultMap resultMap = new ResultMap();

        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
            this(configuration, id, type, resultMappings, null);
        }

        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
            resultMap.configuration = configuration;
            resultMap.id = id;
            resultMap.type = type;
            resultMap.resultMappings = resultMappings;
            resultMap.autoMapping = autoMapping;
        }

        public Builder discriminator(Discriminator discriminator) {
            resultMap.discriminator = discriminator;
            return this;
        }

        public Class<?> type() {
            return resultMap.type;
        }

        public ResultMap build() {
            if (resultMap.id == null) {
                throw new IllegalArgumentException("ResultMaps must have an id");
            }
            resultMap.mappedColumns = new HashSet<>();
            resultMap.mappedProperties = new HashSet<>();
            resultMap.idResultMappings = new ArrayList<>();
            resultMap.constructorResultMappings = new ArrayList<>();
            resultMap.propertyResultMappings = new ArrayList<>();
            final List<String> constructorArgNames = new ArrayList<>();
            for (ResultMapping resultMapping : resultMap.resultMappings) {
                resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
                resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
                final String column = resultMapping.getColumn();
                if (column != null) {
                    resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
                } else if (resultMapping.isCompositeResult()) {
                    for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
                        final String compositeColumn = compositeResultMapping.getColumn();
                        if (compositeColumn != null) {
                            resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
                        }
                    }
                }
                final String property = resultMapping.getProperty();
                if (property != null) {
                    resultMap.mappedProperties.add(property);
                }
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    resultMap.constructorResultMappings.add(resultMapping);
                    if (resultMapping.getProperty() != null) {
                        constructorArgNames.add(resultMapping.getProperty());
                    }
                } else {
                    resultMap.propertyResultMappings.add(resultMapping);
                }
                if (resultMapping.getFlags().contains(ResultFlag.ID)) {
                    resultMap.idResultMappings.add(resultMapping);
                }
            }
            if (resultMap.idResultMappings.isEmpty()) {
                resultMap.idResultMappings.addAll(resultMap.resultMappings);
            }
            if (!constructorArgNames.isEmpty()) {
                final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
                if (actualArgNames == null) {
                    throw new BuilderException("Error in result map '" + resultMap.id
                        + "'. Failed to find a constructor in '"
                        + resultMap.getType().getName() + "' by arg names " + constructorArgNames
                        + ". There might be more info in debug log.");
                }
                resultMap.constructorResultMappings.sort((o1, o2) -> {
                    int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
                    int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
                    return paramIdx1 - paramIdx2;
                });
            }
            // lock down collections
            resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
            resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
            resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
            resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
            resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
            return resultMap;
        }

        private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
            Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (constructorArgNames.size() == paramTypes.length) {
                    List<String> paramNames = getArgNames(constructor);
                    if (constructorArgNames.containsAll(paramNames)
                        && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
                        return paramNames;
                    }
                }
            }
            return null;
        }

        private boolean argTypesMatch(final List<String> constructorArgNames,
                                      Class<?>[] paramTypes, List<String> paramNames) {
            for (int i = 0; i < constructorArgNames.size(); i++) {
                Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
                Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
                if (!actualType.equals(specifiedType)) {
                    if (log.isDebugEnabled()) {
                        log.debug("While building result map '" + resultMap.id
                            + "', found a constructor with arg names " + constructorArgNames
                            + ", but the type of '" + constructorArgNames.get(i)
                            + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                            + actualType.getName() + "]");
                    }
                    return false;
                }
            }
            return true;
        }

        private List<String> getArgNames(Constructor<?> constructor) {
            List<String> paramNames = new ArrayList<>();
            List<String> actualParamNames = null;
            final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
            int paramCount = paramAnnotations.length;
            for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
                String name = null;
                for (Annotation annotation : paramAnnotations[paramIndex]) {
                    if (annotation instanceof Param) {
                        name = ((Param)annotation).value();
                        break;
                    }
                }
                if (name == null && resultMap.configuration.isUseActualParamName()) {
                    if (actualParamNames == null) {
                        actualParamNames = ParamNameUtil.getParamNames(constructor);
                    }
                    if (actualParamNames.size() > paramIndex) {
                        name = actualParamNames.get(paramIndex);
                    }
                }
                paramNames.add(name != null ? name : "arg" + paramIndex);
            }
            return paramNames;
        }
    }

    public String getId() {
        return id;
    }

    public boolean hasNestedResultMaps() {
        return hasNestedResultMaps;
    }

    public boolean hasNestedQueries() {
        return hasNestedQueries;
    }

    public Class<?> getType() {
        return type;
    }

    public List<ResultMapping> getResultMappings() {
        return resultMappings;
    }

    public List<ResultMapping> getConstructorResultMappings() {
        return constructorResultMappings;
    }

    public List<ResultMapping> getPropertyResultMappings() {
        return propertyResultMappings;
    }

    public List<ResultMapping> getIdResultMappings() {
        return idResultMappings;
    }

    public Set<String> getMappedColumns() {
        return mappedColumns;
    }

    public Set<String> getMappedProperties() {
        return mappedProperties;
    }

    public Discriminator getDiscriminator() {
        return discriminator;
    }

    public void forceNestedResultMaps() {
        hasNestedResultMaps = true;
    }

    public Boolean getAutoMapping() {
        return autoMapping;
    }

}
