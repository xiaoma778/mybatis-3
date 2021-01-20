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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 每个 ResultMapping 对象记录结果集中的一列与 JavaBean 中一个属性之间的映射关系
 * 在 <resuleMap> 节点下除了 <discriminator> 子节点的其他子节点，都会被解析成对应的
 * ResultMapping 对象。例：
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
 *
 * @author Clinton Begin
 */
public class ResultMapping {

    private Configuration configuration;
    /** 对应节点的 property 属性，表示的是与该列进行映射的属性*/
    private String property;
    /** 对应节点的 column 属性，表示的是从数据库中得到的列名或是列名的别名*/
    private String column;
    /** 对应节点的 javaType 属性，表示的是一个 JavaBean 的完全限定名，或一个类型别名*/
    private Class<?> javaType;
    /** 对应节点的 jdbcType 属性，表示的是进行映射的列的 JDBC 类型*/
    private JdbcType jdbcType;
    /** 对应节点的 typeHandler 属性，表示的是类型处理器，它会覆盖默认的类型处理器，后面会介绍该字段的作用*/
    private TypeHandler<?> typeHandler;

    /**
     * 对应节点的 resultMap 属性，该属性通过 id 引用了另一个 <resuleMap> 节点的定义，它负责将结果集中的
     * 一部分列映射成其他关联的结果对象。这样我们就可以通过 join 方式进行关联查询，然后直接映射成多个对象，
     * 并同时设置这些对象之间的组合关系
     * */
    private String nestedResultMapId;

    /**
     * 对应节点的 select 属性，该属性通过 id 引用了里一个 <select> 节点定义，它会把指定的列的值传入 select 属性
     * 指定的 select 语句中作为参数进行查询。使用 select 属性可能会导致 N + 1 问题，需要注意
     * */
    private String nestedQueryId;
    /** 对应节点的 notNullColumns 属性拆分后的结果*/
    private Set<String> notNullColumns;
    /** 对应节点的 columnPrefix 属性*/
    private String columnPrefix;
    /** 处理后的标志，标志共两个：id 和 constructor*/
    private List<ResultFlag> flags;
    /** 对应节点的 column 拆分后生成的结果，composites.size() > 0 会使 column 为 null*/
    private List<ResultMapping> composites;
    /** 对应节点的 resultSet 属性*/
    private String resultSet;
    /** 对应节点的 foreignColumn 属性*/
    private String foreignColumn;
    /** 对应节点的 fetchType 属性*/
    private boolean lazy;

    ResultMapping() {
    }

    public static class Builder {
        private ResultMapping resultMapping = new ResultMapping();

        public Builder(Configuration configuration, String property, String column, TypeHandler<?> typeHandler) {
            this(configuration, property);
            resultMapping.column = column;
            resultMapping.typeHandler = typeHandler;
        }

        public Builder(Configuration configuration, String property, String column, Class<?> javaType) {
            this(configuration, property);
            resultMapping.column = column;
            resultMapping.javaType = javaType;
        }

        public Builder(Configuration configuration, String property) {
            resultMapping.configuration = configuration;
            resultMapping.property = property;
            resultMapping.flags = new ArrayList<>();
            resultMapping.composites = new ArrayList<>();
            resultMapping.lazy = configuration.isLazyLoadingEnabled();
        }

        public Builder javaType(Class<?> javaType) {
            resultMapping.javaType = javaType;
            return this;
        }

        public Builder jdbcType(JdbcType jdbcType) {
            resultMapping.jdbcType = jdbcType;
            return this;
        }

        public Builder nestedResultMapId(String nestedResultMapId) {
            resultMapping.nestedResultMapId = nestedResultMapId;
            return this;
        }

        public Builder nestedQueryId(String nestedQueryId) {
            resultMapping.nestedQueryId = nestedQueryId;
            return this;
        }

        public Builder resultSet(String resultSet) {
            resultMapping.resultSet = resultSet;
            return this;
        }

        public Builder foreignColumn(String foreignColumn) {
            resultMapping.foreignColumn = foreignColumn;
            return this;
        }

        public Builder notNullColumns(Set<String> notNullColumns) {
            resultMapping.notNullColumns = notNullColumns;
            return this;
        }

        public Builder columnPrefix(String columnPrefix) {
            resultMapping.columnPrefix = columnPrefix;
            return this;
        }

        public Builder flags(List<ResultFlag> flags) {
            resultMapping.flags = flags;
            return this;
        }

        public Builder typeHandler(TypeHandler<?> typeHandler) {
            resultMapping.typeHandler = typeHandler;
            return this;
        }

        public Builder composites(List<ResultMapping> composites) {
            resultMapping.composites = composites;
            return this;
        }

        public Builder lazy(boolean lazy) {
            resultMapping.lazy = lazy;
            return this;
        }

        public ResultMapping build() {
            // lock down collections
            resultMapping.flags = Collections.unmodifiableList(resultMapping.flags);
            resultMapping.composites = Collections.unmodifiableList(resultMapping.composites);
            resolveTypeHandler();
            validate();
            return resultMapping;
        }

        private void validate() {
            // Issue #697: cannot define both nestedQueryId and nestedResultMapId
            if (resultMapping.nestedQueryId != null && resultMapping.nestedResultMapId != null) {
                throw new IllegalStateException("Cannot define both nestedQueryId and nestedResultMapId in property " + resultMapping.property);
            }
            // Issue #5: there should be no mappings without typehandler
            if (resultMapping.nestedQueryId == null && resultMapping.nestedResultMapId == null && resultMapping.typeHandler == null) {
                throw new IllegalStateException("No typehandler found for property " + resultMapping.property);
            }
            // Issue #4 and GH #39: column is optional only in nested resultmaps but not in the rest
            if (resultMapping.nestedResultMapId == null && resultMapping.column == null && resultMapping.composites.isEmpty()) {
                throw new IllegalStateException("Mapping is missing column attribute for property " + resultMapping.property);
            }
            if (resultMapping.getResultSet() != null) {
                int numColumns = 0;
                if (resultMapping.column != null) {
                    numColumns = resultMapping.column.split(",").length;
                }
                int numForeignColumns = 0;
                if (resultMapping.foreignColumn != null) {
                    numForeignColumns = resultMapping.foreignColumn.split(",").length;
                }
                if (numColumns != numForeignColumns) {
                    throw new IllegalStateException("There should be the same number of columns and foreignColumns in property " + resultMapping.property);
                }
            }
        }

        private void resolveTypeHandler() {
            if (resultMapping.typeHandler == null && resultMapping.javaType != null) {
                Configuration configuration = resultMapping.configuration;
                TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
                resultMapping.typeHandler = typeHandlerRegistry.getTypeHandler(resultMapping.javaType, resultMapping.jdbcType);
            }
        }

        public Builder column(String column) {
            resultMapping.column = column;
            return this;
        }
    }

    public String getProperty() {
        return property;
    }

    public String getColumn() {
        return column;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public JdbcType getJdbcType() {
        return jdbcType;
    }

    public TypeHandler<?> getTypeHandler() {
        return typeHandler;
    }

    public String getNestedResultMapId() {
        return nestedResultMapId;
    }

    public String getNestedQueryId() {
        return nestedQueryId;
    }

    public Set<String> getNotNullColumns() {
        return notNullColumns;
    }

    public String getColumnPrefix() {
        return columnPrefix;
    }

    public List<ResultFlag> getFlags() {
        return flags;
    }

    public List<ResultMapping> getComposites() {
        return composites;
    }

    public boolean isCompositeResult() {
        return this.composites != null && !this.composites.isEmpty();
    }

    public String getResultSet() {
        return this.resultSet;
    }

    public String getForeignColumn() {
        return foreignColumn;
    }

    public void setForeignColumn(String foreignColumn) {
        this.foreignColumn = foreignColumn;
    }

    public boolean isLazy() {
        return lazy;
    }

    public void setLazy(boolean lazy) {
        this.lazy = lazy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ResultMapping that = (ResultMapping)o;

        return property != null && property.equals(that.property);
    }

    @Override
    public int hashCode() {
        if (property != null) {
            return property.hashCode();
        } else if (column != null) {
            return column.hashCode();
        } else {
            return 0;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ResultMapping{");
        //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
        sb.append("property='").append(property).append('\'');
        sb.append(", column='").append(column).append('\'');
        sb.append(", javaType=").append(javaType);
        sb.append(", jdbcType=").append(jdbcType);
        //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
        sb.append(", nestedResultMapId='").append(nestedResultMapId).append('\'');
        sb.append(", nestedQueryId='").append(nestedQueryId).append('\'');
        sb.append(", notNullColumns=").append(notNullColumns);
        sb.append(", columnPrefix='").append(columnPrefix).append('\'');
        sb.append(", flags=").append(flags);
        sb.append(", composites=").append(composites);
        sb.append(", resultSet='").append(resultSet).append('\'');
        sb.append(", foreignColumn='").append(foreignColumn).append('\'');
        sb.append(", lazy=").append(lazy);
        sb.append('}');
        return sb.toString();
    }

}
