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
package org.apache.ibatis.scripting.defaults;

import java.util.HashMap;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

/**
 * Static SqlSource. It is faster than {@link DynamicSqlSource} because mappings are
 * calculated during startup.
 * 处理静态 SQL 语句
 * RawSqlSource 与 DynamicSqlSource 区别除了处理动静态 SQL 之外，两者解析 SQL 语句的
 * 时机也不一样，RawSqlSource 在 MyBatis 初始化时完成 SQL 语句的解析，而 DynamicSqlSource
 * 是在实际执行 SQL 语句之前解析
 *
 * @since 3.2.0
 * @author Eduardo Macarron
 */
public class RawSqlSource implements SqlSource {

    private final SqlSource sqlSource;

    public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, Class<?> parameterType) {
        this(configuration, getSql(configuration, rootSqlNode), parameterType);
    }

    public RawSqlSource(Configuration configuration, String sql, Class<?> parameterType) {
        // 通过 SqlSourceBuilder 完成占位符的解析和替换操作
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> clazz = parameterType == null ? Object.class : parameterType;
        // 返回 StaticSqlSource
        sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<>());
    }

    /**
     * 完成 SQL 语句的拼装和初步解析
     * @param configuration
     * @param rootSqlNode
     * @return
     */
    private static String getSql(Configuration configuration, SqlNode rootSqlNode) {
        DynamicContext context = new DynamicContext(configuration, null);
        rootSqlNode.apply(context);
        return context.getSql();
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        return sqlSource.getBoundSql(parameterObject);
    }

}
