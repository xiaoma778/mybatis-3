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
package org.apache.ibatis.executor.resultset;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERRED = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;
    /** 用户指定用于处理结果集的 ResultHandler 对象*/
    private final ResultHandler<?> resultHandler;
    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;
    private final ReflectorFactory reflectorFactory;

    // nested resultmaps
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
    private final Map<String, Object> ancestorObjects = new HashMap<>();
    private Object previousRowValue;

    // multiple resultsets
    /** key：是 ResultSet 名称，value ：ResultMapping 对象*/
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

    // Cached Automappings
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

    // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
    private boolean useConstructorMappings;

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

    private static class UnMappedColumnAutoMapping {
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    handleRefCursorOutputParameter((ResultSet)cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            if (this.resultHandler == null) {
                final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
                metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
            } else {
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    //
    // HANDLE RESULT SETS
    //
    /**
     * 通过 select 语句查询数据库得到的结果集由该方法进行处理，该方法不仅可以处理
     * Statement、PreparedStatement 产生的结果集，还可以处理 CallableStatement
     * 调用存储过程产生的多结果集
     * @param stmt
     * @return
     * @throws SQLException
     */
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        // 该集合用于保存映射结果集得到的结果对象
        final List<Object> multipleResults = new ArrayList<>();

        int resultSetCount = 0;
        // 获取第一个 ResultSet 对象，正如前面所说，可能存在多个 ResultSet，这里只获取第一个 ResultSet
        ResultSetWrapper rsw = getFirstResultSet(stmt);

        // 获取 MappedStatement.resultMaps 集合，Mapper 映射文件中的 <resultMap> 节点会被解析成
        // ResultMap 对象，保存到 MappedStatement.resultMaps 集合中，如果 SQL 节点能够产生多个
        // ResultSet，那么我们可以在 SQL 节点的 resultMap 属性中配置多个 <resultMap> 节点的 id，它
        // 们之间通过 "," 分隔，实现对多个结果集的映射
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        int resultMapCount = resultMaps.size();

        // 如果结果集不为空，则 resultMaps 集合不能为空，否则抛出异常
        validateResultMapsCount(rsw, resultMapCount);

        // 遍历 resultMaps 集合
        while (rsw != null && resultMapCount > resultSetCount) {// ---(1)
            // 获取该结果集对应的 ResultMap 对象
            ResultMap resultMap = resultMaps.get(resultSetCount);
            // 根据 ResultMap 中定义的映射规则对 ResultSet 进行映射，并将映射的结果对象添加到 multipleResults 集合中保存
            handleResultSet(rsw, resultMap, multipleResults, null);

            // 获取一下个结果集
            rsw = getNextResultSet(stmt);
            // 清空 nestedResultObjects 集合
            cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        // 获取 mappedStatement.resultSets 属性。该属性仅对多结果集的情况适用，该属性将列出语句执行后
        // 返回的结果集，并给每个结果集一个名称，名称是逗号分隔的
        // 这里会根据 ResultSet 的名称处理嵌套映射
        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {// ---(2)
                // 根据 resultSet 的名称，获取未处理的 ResultMapping
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    // 根据 ResultMap 对象映射结果集
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                // 获取下一个结果集
                rsw = getNextResultSet(stmt);
                // 清空 nestedResultObjects 集合
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        return collapseSingleResultList(multipleResults);
    }

    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
    }

    /**
     * 获取第一个 ResultSetWrapper（ResultSet 包装类） 对象
     * @param stmt
     * @return
     * @throws SQLException
     */
    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            // move forward to get the first resultset in case the driver
            // doesn't return the resultset as the first result (HSQLDB 2.1)
            // 检测是否还有待处理的 ResultSet
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                // 没有待处理的 ResultSet
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultset
                    break;
                }
            }
        }
        // 将结果集封装成 ResultSetWrapper 对象
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    /**
     * 获取下一个 ResultSetWrapper（ResultSet 包装类） 对象
     * @param stmt
     * @return
     */
    private ResultSetWrapper getNextResultSet(Statement stmt) {
        // Making this method tolerant of bad JDBC drivers
        try {
            // 检测 JDBC 是否支持多结果集
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // Crazy Standard JDBC way of determining if there are more results
                // 检测是否还有待处理的结果集，若存在，则封装成 ResultSetWrapper 对象并返回
                if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
                    ResultSet rs = stmt.getResultSet();
                    if (rs == null) {
                        return getNextResultSet(stmt);
                    } else {
                        return new ResultSetWrapper(rs, configuration);
                    }
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
    }

    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    /**
     * 完成对单个 ResultSet 对象的封装
     * @param rsw
     * @param resultMap
     * @param multipleResults   该集合用于保存映射结果集得到的结果对象
     * @param parentMapping
     * @throws SQLException
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            // 处理多结果集中的嵌套映射
            if (parentMapping != null) {
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    // 如果用户未指定处理映射结果对象的 ResultHandler 对象，则使用 DefaultResultHandler
                    // 作为默认的 DefaultResultHandler 对象
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);

                    //对 ResultSet 进行映射，并将映射得到的结果对象添加到 DefaultResultHandler 对象中
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);

                    //将 DefaultResultHandler 中保存的结果对象添加到 multipleResults 集合中
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    // 使用用户指定的 ResultHandler 对象处理结果对象
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>)multipleResults.get(0) : multipleResults;
    }

    //
    // HANDLE ROWS FOR SIMPLE RESULTMAP
    //

    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        if (resultMap.hasNestedResultMaps()) {
            ensureNoRowBounds();
            checkResultHandler();
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                + "Use safeResultHandlerEnabled=false setting to bypass this check "
                + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    /**
     * 执行流程大致如下：
     * 1.调用 skipRows() 方法，根据 RowBounds 中的 offset 值定位到指定的记录行<br />
     * 2.调用 shouldProcessMoreRows() 方法，检测是否还有需要映射的记录<br />
     * 3.通过 resolveDiscriminatedResultMap() 方法，确定映射使用的 ResultMap 对象<br />
     * 4.调用 getRowValue() 方法对 ResultSet 中的一行记录进行映射：<br />
     *  a) 通过 createResultObject() 方法创建映射后的结果对象<br />
     *  b) 通过 shouldApplyAutomaticMappings() 方法判断是否开启了自动映射功能<br />
     *  c) 通过 applyAutomaticMappings() 方法自动映射 ResultMap 中未明确映射的列<br />
     *  d) 通过 applyPropertyMappings() 方法映射 ResultMap 中明确映射列，到这里该<br />
     *     行记录的数据已经完全映射到了结果对象的相应属性中<br />
     * 5.调用 storeObject() 方法保存映射得到的结果对象
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
        throws SQLException {
        // 获取上下文对象
        DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
        ResultSet resultSet = rsw.getResultSet();
        // 步骤1：根据 RowBounds 中的 offset 定位到指定的记录
        skipRows(resultSet, rowBounds);

        // 步骤2：检测已经处理的行数是否已经达到上限（RowBounds.limit）以及 ResultSet 中是否还有可处理的记录
        while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
            // 步骤3：根据该行记录以及 ResultMap.discriminator，决定映射使用的 ResultMap
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);

            // 步骤4：根据最终确定的 ResultMap 对 ResultSet 中的该行记录进行映射，得到映射后的结果对象
            Object rowValue = getRowValue(rsw, discriminatedResultMap, null);

            // 步骤5：将映射创建的结果对象添加到 ResultHandler.list 中保存
            storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
    }

    /**
     * 保存映射结果
     * @param resultHandler
     * @param resultContext
     * @param rowValue
     * @param parentMapping
     * @param rs
     * @throws SQLException
     */
    private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        if (parentMapping != null) {
            // 嵌套查询或嵌套映射，将结果对象保存到父对象对应的属性中
            linkToParents(rs, parentMapping, rowValue);
        } else {
            // 普通映射，将结果对象保存到 ResultHandler 中
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    /**
     *
     * @param resultHandler
     * @param resultContext
     * @param rowValue
     */
    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
        // 递增 DefaultResultContext.resultCount，该值用与检测处理的记录行数是否已经达到上
        // 限（在 RowBounds.limit 字段中记录了该上限）。
        // 之后将结果对象保存到 DefaultResultContext.resultObject 字段中
        resultContext.nextResultObject(rowValue);
        // 将结果对象添加到 ResultHandler.resultList 中保存
        ((ResultHandler<Object>)resultHandler).handleResult(resultContext);
    }

    /**
     * 检测是否能够对后续的记录进行映射操作
     * @param context
     * @param rowBounds
     * @return
     */
    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
        // 一个是检测 DefaultResultContext.stopped 字段，
        // 另一个是检测映射行数是否达到了 RowBounds.limit 的限制
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    /**
     * 根据 RowBounds.offset 字段的值定位到指定的记录
     * @param rs
     * @param rowBounds
     * @throws SQLException
     */
    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        // 根据 ResultSet 的类型进行定位
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                // 直接定位到 offset 指定的记录
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            // 通过多次调用 ResultSet.next() 方法移动到指定的记录
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                if (!rs.next()) {
                    break;
                }
            }
        }
    }

    //
    // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
    //

    /**
     * 完成对结果集的映射，步骤如下： todo ???<br />
     * 1.根据 ResultMap 指定的类型创建对应的结果对象，以及对应的 MetaObject 对象<br />
     * 2.根据配置信息，决定是否自动映射 ResultMap 中未明确映射的列<br />
     * 3.根据 ResultMap 映射明确指定的属性和列<br />
     * 4.返回映射得到的结果对象
     * @param rsw
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        // ResultLoaderMap 与延迟加载
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();

        // 步骤1：创建该行记录映射之后得到的结果对象，该结果对象的类型由 <resultMap> 节点的 type 属性指定
        Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
        if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            // 创建上述结果对象相应的 MetaObject 对象
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            // 成功映射任意属性，则 foundValues 为 true，否则为 false
            boolean foundValues = this.useConstructorMappings;
            // 检测是否需要进行自动映射
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                // 步骤2：自动映射 ResultMap 中未明确指定的列
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
            }
            // 步骤3：映射 ResultMap 中明确指定需要映射的列
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            // 步骤4：如果没有成功映射任何属性，则根据 mybatis-config.xml 中的
            // <returnInstanceForEmptyRow> 配置决定返回空的结果对象还是返回 null
            rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
        }
        return rowValue;
    }

    /**
     * 检测是否开启了自动映射的功能
     * 控制自动映射功能的开关有下面两个：
     * 1.在 ResultMap 中明确地配置了 autoMapping 属性，则优先根据该属性的值决定是否开启自动映射功能
     * 2.如果没有配置 autoMapping 属性，则在根据 mybatis-config.xml 中 setting 节点中配置的
     *   autoMappintBehavior 值（默认为 PARTIAL）决定是否开启自动映射功能。该值用于指定 MyBatis 应
     *   如何自动映射列到字段或属性。NONE：表示取消自动映射；PARTIAL ：只会自动映射没有定义嵌套映射的
     *   ResultSet；FULL：会自动映射任意复杂的 ResultSet（无论是否嵌套）
     * @param resultMap
     * @param isNested
     * @return
     */
    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        if (resultMap.getAutoMapping() != null) {
            return resultMap.getAutoMapping();//获取 ResultSet 中的 autoMapping 属性值
        } else {
            if (isNested) {// 检测是否为嵌套查询或是嵌套映射
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    //
    // PROPERTY MAPPINGS
    //

    /**
     * 处理 ResultMap 中明确需要进行映射的列，该方法中涉及延迟加载、嵌套映射等内容
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        // 获取该 ResultMap 中明确需要进行映射的列名集合
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;

        // 获取 ResultMap.propertyResultMappings 集合，其中记录了映射使用的所有 ResultMapping 对象
        // 该集合的填充过程，可以看下 MyBatis 的初始化过程
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();

        for (ResultMapping propertyMapping : propertyMappings) {
            // 处理列前缀
            String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // the user added a column attribute to a nested result map, ignore it
                // 该属性需要使用一个嵌套 ResultMap 进行映射，忽略 column 属性
                column = null;
            }

            // 下面的逻辑主要处理三种场景：
            // 场景1：column 是 "{prop1=col1,prop2=col2}" 这种形式的，一般与嵌套查询配合使用，表示
            //      将 col1 和 col2 的列值传递给内层嵌套查询作为参数
            // 场景2：基本类型的属性映射
            // 场景3：多结果集的场景处理，该属性来自另一个结果集
            if (propertyMapping.isCompositeResult()// ---场景1
                || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))// ---场景2
                || propertyMapping.getResultSet() != null) {// ---场景3

                // 通过 getPropertyMappingValue() 方法完成映射，并得到属性值
                // 真正的映射操作就是在这里完成的！
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                // issue #541 make property optional
                // 获取属性名称
                final String property = propertyMapping.getProperty();
                if (property == null) {
                    continue;
                } else if (value == DEFERRED) {
                    // DEFERRED 表示的是占位符对象，在后面介绍 ResultLoader 和 DeferredLoad 时，会详细介绍延迟加载的原理和实现
                    foundValues = true;
                    continue;
                }
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    // 设置属性值
                    metaObject.setValue(property, value);
                }
            }
        }
        return foundValues;
    }

    /**
     * 映射 ResultMap 中明确需要进行映射的列，其实底层就是调的 typeHandler.getResult() 方法来获取属性值的
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        // 嵌套查询
        if (propertyMapping.getNestedQueryId() != null) {
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {// 多结果集处理
            addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
            return DEFERRED;// 返回占位符对象
        } else {
            // 获取 ResultMapping 中记录的 TypeHandler 对象
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            // 使用 TypeHandler 对象获取属性值
            return typeHandler.getResult(rs, column);
        }
    }

    /**
     * 负责为未映射的列查找对应的属性，并将两者关联起来封装成 UnMappedColumnAutoMapping 对象
     * 该方法产生的 UnMappedColumnAutoMapping 对象集合会缓存在 autoMappingsCache 字段中，
     * 其中的 key 由 ResultMap 的 id 与列前缀构成。在 UnMappedColumnAutoMapping 对象中记录
     * 了未映射的列名、对应属性名称、TypeHandler 对象等信息
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        // 自动映射的缓存 key
        final String mapKey = resultMap.getId() + ":" + columnPrefix;
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);

        if (autoMapping == null) {// autoMappingsCache 缓存未命中
            autoMapping = new ArrayList<>();
            // 从 ResultSetWrapper 中获取未映射的列名集合
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            for (String columnName : unmappedColumnNames) {
                String propertyName = columnName;// 生成属性名称

                // 如果列名以列前缀开头，则属性名称为列名去除前缀删除的部分。如果明确指定了列前缀，但列名
                // 没有以列前缀开头，则跳过该列处理后面的列
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    // When columnPrefix is specified,
                    // ignore columns without the prefix.
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        continue;
                    }
                }

                // 在结果对象中查找指定的属性名
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());

                // 检测是否存在该属性的 setter 方法，注意：如果是 MapWrapper，一直返回 true
                if (property != null && metaObject.hasSetter(property)) {
                    if (resultMap.getMappedProperties().contains(property)) {
                        continue;
                    }
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        // 查找对应的 TypeHandler 对象
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        // 创建 UnMappedColumnAutoMapping 对象，并添加到 autoMapping 集合中
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    configuration.getAutoMappingUnknownColumnBehavior()
                        .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }
            // 将 autoMapping 添加到缓存中保存
            autoMappingsCache.put(mapKey, autoMapping);
        }
        return autoMapping;
    }

    /**
     * 自动映射 ResultMap 中未明确映射的列
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        // 获取 ResultSet 中存在，但 ResultMap 中没有明确映射的列所对应的 UnMappedColumnAutoMapping
        // 集合，如果 ResultMap 中设置的 resultType 为 java.util.HashMap 的话，则全部的列都会在这里
        // 获取到
        List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;
        if (!autoMapping.isEmpty()) {
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                // 使用 TypeHandler 获取自动映射的列值
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    // 将自动映射的属性值设置到结果对象中
                    metaObject.setValue(mapping.property, value);
                }
            }
        }
        return foundValues;
    }

    // MULTIPLE RESULT SETS

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
        // issue #255
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    //
    // INSTANTIATION & CONSTRUCTOR MAPPING
    //

    /**
     * 创建数据库记录映射得到的结果对象，该结果对象的类型由 <resultMap> 节点的 type 属性指定
     * 该方法会根据结果集的列数、ResultMap.constructorResultMappings 集合等信息，选择不同
     * 的方式创建结果对象
     *
     * @param rsw
     * @param resultMap
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        // 标识是否使用构造函数创建该结果对象
        this.useConstructorMappings = false; // reset previous mapping result
        // 记录构造函数的参数类型
        final List<Class<?>> constructorArgTypes = new ArrayList<>();
        // 记录构造函数的实参
        final List<Object> constructorArgs = new ArrayList<>();
        // 创建该行记录对应的结果对象，该方法是该步骤的核心
        Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // issue gcode #109 && issue #149
                // 如果包含嵌套查询，且配置了延迟加载，则使用 ProxyFactory 创建代理对象
                // 默认使用的是 JavassistProxyFactory
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        // 记录是否使用构造器创建对象
        this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
        return resultObject;
    }

    /**
     * 创建结果对象的真正实现，该结果对象的类型由 <resultMap> 节点的 type 属性指定
     * @param rsw
     * @param resultMap
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
        throws SQLException {
        // 获取 ResultMap 中记录的 type 属性，也就是该行记录最终映射成的结果对象类型
        final Class<?> resultType = resultMap.getType();
        // 创建该类型对应的 MetaClass 对象
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        // 获取 ResultMap 中记录的 <constructor> 节点信息，如果该集合不为空，则可以通过该集合确定
        // 相应 Java 类中的唯一构造函数
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

        // 创建结果对象分为下面 4 种场景
        // 场景1：结果集只有一列，且存在 TypeHandler 对象可以将该列转换成 resultType 类型的值
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            // 先查找相应的 TypeHandler 对象，再使用 TypeHandler 对象将该记录转换成 Java 类型的值
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            // 场景2：ResultMap 中记录了  <constructor> 节点的信息，则通过反射方式调用构造方法，创景结果对象
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // 场景3：使用默认的无参构造函数，则直接使用 ObjectFactory 创景对象
            return objectFactory.create(resultType);
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            // 场景4：通过自动映射的方式查找合适的构造方法并创建结果对象
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
        }
        // 初始化失败，抛出异常
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 根据 constructor 节点配置，选择合适的构造方法创建结果对象，其中也会涉及嵌套查询和嵌套映射的处理
     * @param rsw
     * @param resultType
     * @param constructorMappings
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     */
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        // 遍历 constructorMappings 集合，该过程中会使用 constructorArgTypes 集合记录构造参数类型，
        // 使用 constructorArgTypes 集合记录构造函数实参
        for (ResultMapping constructorMapping : constructorMappings) {
            // 获取当前构造参数的类型
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                if (constructorMapping.getNestedQueryId() != null) {
                    // 存在嵌套查询，需要处理该查询，然后才能得到实参
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    // 存在嵌套映射，需要处理该映射，才能得到实参
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
                } else {
                    // 直接获取该列的值，然后经过 TypeHandler 对象的转换，得到构造函数的实参
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException | SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);// 记录当前构造函参数的类型
            constructorArgs.add(value);// 记录当前构造参数的实际值
            foundValues = value != null || foundValues;
        }
        // 通过 ObjectFactory 调用匹配的构造函数，创建结果对象
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * 根据 ResultSetWrapper.classNames 集合查找合适的构造函数，并创建结果对象
     * @param rsw
     * @param resultType
     * @param constructorArgTypes
     * @param constructorArgs
     * @return
     * @throws SQLException
     */
    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
        final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
        final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
        if (defaultConstructor != null) {
            return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
        } else {
            for (Constructor<?> constructor : constructors) {
                if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
                    return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
                }
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    /**
     * 根据构造方法的参数类型与 ResultSet 中列所对应的 Java 类型相匹配的构造方法，并创建结果对象
     * @param rsw
     * @param resultType
     * @param constructorArgTypes
     * @param constructorArgs
     * @param constructor
     * @return
     * @throws SQLException
     */
    private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
        boolean foundValues = false;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            // 获取构造函数的参数类型
            Class<?> parameterType = constructor.getParameterTypes()[i];
            String columnName = rsw.getColumnNames().get(i);// ResultSet 中的列名

            // 查找对应的 TypeHandler，并获取该列的值
            TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
            Object value = typeHandler.getResult(rsw.getResultSet(), columnName);

            // 记录构造函数的参数类型和参数值
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues; // 更新 foundValues 值
        }
        // 使用 ObjectFactory 调用对应的构造方法，创建结果对象
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * 查找默认构造函数
     * @param constructors
     * @return
     */
    private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
        if (constructors.length == 1) {
            return constructors[0];
        }

        for (final Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
                return constructor;
            }
        }
        return null;
    }

    private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length != jdbcTypes.size()) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    //
    // NESTED QUERY
    //

    /**
     * 获取嵌套查询的构造方法参数值
     * @param rs
     * @param constructorMapping
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        // 获取嵌套查询的 id 以及对应的 MappedStatement 对象
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        // 获取传递给嵌套查询的参数值
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            // 获取嵌套查询对应的 BoundSql 对象和相应的 CacheKey 对象
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            // 获取嵌套查询结果集经过映射后的目标类型
            final Class<?> targetType = constructorMapping.getJavaType();
            // 创建 ResultLoader 对象，并调用 loadResult() 方法执行嵌套查询，得到相应的构造方法参数值
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            value = resultLoader.loadResult();
        }
        return value;
    }

    /**
     * 如果开启了延迟加载功能，则创建相应的 ResultLoader 对象并返回 DEFERRED 这个标识对象
     * 如果未开启延迟加载功能，则直接执行嵌套查询，并返回结果对象
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
        throws SQLException {
        // 获取嵌套查询的 id 和对应的 MappedStatement 对象
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        // 获取传递给嵌套查询的参数类型和参数值
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            // 获取嵌套查询对应的 BoundSql 对象和相应的 CacheKey 对象
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            // 获取嵌套查询结果经过映射后的目标类型
            final Class<?> targetType = propertyMapping.getJavaType();
            // 检测缓存中是否存在该嵌套查询的结果对象
            if (executor.isCached(nestedQuery, key)) {
                // 创建 DeferredLoad 对象，并通过 DeferredLoad 对象从缓存中加载结果对象
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERRED;// 返回 DEFERRED 标识（是一个特殊的标识对象）
            } else {
                // 创建嵌套查询相应的 ResultLoader 对象
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    // 如果该属性配置了延迟加载，则将其添加到 ResultLoaderMap 中，等待真正使用时再执行嵌套
                    // 查询并得到结果对象
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERRED;// 返回 DEFERRED 标识
                } else {
                    // 没有配置延迟加载，则直接调用 ResultLoader.loadResult() 方法执行嵌套查询，并映射
                    // 得到结果对象
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<>();
        } else if (ParamMap.class.equals(parameterType)) {
            return new HashMap<>(); // issue #649
        } else {
            return objectFactory.create(parameterType);
        }
    }

    //
    // DISCRIMINATOR
    //

    /**
     * 根据 ResultMap 对象中记录的 Discriminator 以及参与映射的列值，选择映射操作最终使用的
     * ResultMap 对象，这个选择过程可能嵌套多层。例：
     * 现在要映射的 ResultSet 有 col1 ~ col4 这 4 列，其中有一行记录的 4 列值分别是[1,2,3,4]，
     * 映射使用的 resultMap 节点是 result1。
     *
     * 数据库记录如下：
     * +--------+--------+--------+--------+
     * |  col1  |  col2  |  col3  |  col4  |
     * +--------+--------+--------+--------+
     * |    1   |    2   |    3   |    4   |
     * +--------+--------+--------+--------+
     *
     * Mapper 文件中 ResultMap 配置如下：
     * <resultMap id="result1" type="A">
     *      <discriminator javaType="int" column="col2">
     *          <case value="2" resultMap="result2"></case>
     *          <case value="5" resultMap="result2"></case>
     *      </discriminator>
     *      <result property="col1" column="col1" />
     * </resultMap>
     *
     * <resultMap id="result2" type="SubA" extends="result1">
     *      <discriminator javaType="int" column="col3">
     *          <case value="3" resultMap="result3"></case>
     *          <case value="4" resultMap="result4"></case>
     *      </discriminator>
     *      <result property="col2" column="col2" />
     * </resultMap>
     *
     * <resultMap id="result3" type="SSubA" extends="result2">
     *      <result property="col3" column="col3" />
     *      <result property="col4" column="col4" />
     * </resultMap>
     *
     * 通过该方法选择最终使用的 ResultMap 对象的过程如下：
     * 1.结果集按照 result1 进行映射，该行记录 col2 列值为 2，根据 discriminator 节点配置，会选择使用 result2 对该记录进行映射<br />
     * 2.又因为该行记录的 col3 列值为 3，最终选择 result3 对该行记录进行映射，所以该行记录的映射结果是 SSubA 对象
     * @param rs
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        // 记录已经处理过的 ResultMap 的 id
        Set<String> pastDiscriminators = new HashSet<>();
        // 获取 ResultMap 中的 Discriminator 对象。
        // 注：<discriminator> 节点对应生成的是 Discriminator 对象并记录到
        // ResultMap.discriminator 字段中，而不是生成 ResultMapping 对象
        Discriminator discriminator = resultMap.getDiscriminator();
        while (discriminator != null) {
            // 获取记录中对应列的值，其中会使用相应的 TypeHandler 对象将该列值转换成 Java 类型
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            // 根据该列值获取对应的 ResultMap 的 id，如示例中的 result2
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                // 根据上述步骤获取的 id，查找相应的 ResultMap 对象
                resultMap = configuration.getResultMap(discriminatedMapId);
                // 记录当前 Discriminator 对象
                Discriminator lastDiscriminator = discriminator;
                // 获取 ResultMap 对象中的 Discriminator
                discriminator = resultMap.getDiscriminator();
                // 检测 Discriminator 是否出现了环形引用
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        // 该 ResultMap 对象为映射最终使用的 ResultMap
        return resultMap;
    }

    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    //
    // HANDLE NESTED RESULT MAPS
    //

    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
        ResultSet resultSet = rsw.getResultSet();
        skipRows(resultSet, rowBounds);
        Object rowValue = previousRowValue;
        while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                if (partialObject == null) {
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    //
    // GET VALUE FROM ROW FOR NESTED RESULT MAP
    //

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object rowValue = partialObject;
        if (rowValue != null) {
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            putAncestor(rowValue, resultMapId);
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(rowValue);
                boolean foundValues = this.useConstructorMappings;
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                putAncestor(rowValue, resultMapId);
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                nestedResultObjects.put(combinedKey, rowValue);
            }
        }
        return rowValue;
    }

    private void putAncestor(Object resultObject, String resultMapId) {
        ancestorObjects.put(resultMapId, resultObject);
    }

    //
    // NESTED RESULT MAP (JOIN MAPPING)
    //
    /**
     * 处理嵌套映射<br />
     * 该方法会遍历 ResultMap.propertyResultMappings 集合中记录的 ResultMapping 对象，并处理其中的嵌套映射
     * 处理步骤如下：<br />
     * 1.获取 ResultMapping.nestedResultMapId 字段值，该值不为空则表示存在相应的嵌套映射要处理。同时还会检测
     *      ResultMapping.resultSet 字段，它指定了要映射的结果集名称，该属性的映射会在 handleResultSets()
     *      方法中完成<br />
     * 2.通过 resolveDiscriminatedResultMap() 方法确定嵌套映射使用的 ResultMap 对象<br />
     * 3.处理循环引用的场景<br />
     *      a)如果不存在循环引用的情况，则继续后面的映射流程<br />
     *      b)如果存在循环引用，则不再创建新的嵌套对象，而是重用之前的对象<br />
     * 4.通过 createRowKey() 方法为嵌套对象创建 CacheKey。该过程除了根据嵌套对象的信息创建 CacheKey，还会与外层
     *      对象的 CacheKey 合并，得到全局唯一的 CacheKey 对象<br />
     * 5.如果外层对象中用于记录当前嵌套对象的属性为 Collection 类型，且未初始化，则会通过
     *      instantiateCollectionPropertyIfAppropriate() 方法初始化该集合对象<br />
     * 6.根据 association、collection 等节点的 notNullColumn 属性，检测结果集中相应列是否为空<br />
     * 7.调用 getRowValue() 方法完成嵌套映射，并生成嵌套对象。嵌套映射可以嵌套多层，也就可以产生多层递归。<br />
     * 8.通过 linkObjects() 方法，将步骤 7 找中得到的嵌套对象保存到外层对象中
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param parentPrefix
     * @param parentRowKey
     * @param newObject
     * @return
     */
    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        // 遍历全部 ResultMapping 对象，处理其中的嵌套映射
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            // 步骤1：检测 nestedResultMapId 和 resultSet 两个字段的值
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    // 获取列前缀
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    // 步骤2：确定嵌套映射使用的 ResultMap 对象
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    // 步骤3：处理循环引用的情况
                    if (resultMapping.getColumnPrefix() == null) {
                        // try to fill circular reference only when columnPrefix
                        // is not specified for the nested result map (issue #215)
                        Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                        if (ancestorObject != null) {
                            if (newObject) {
                                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
                            }
                            // 若是循环引用，则不用执行下面的路径创建新对象，而是重用之前的对象
                            continue;
                        }
                    }
                    // 步骤4：为嵌套对象创建 CacheKey 对象，接着使用 combineKeys() 方法将其与外层对象的
                    // CacheKey 合并，最终得到嵌套对象的真正 CacheKey，此时可以认为该 CacheKey 全局唯一
                    final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                    final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);

                    // 查找 nestedResultObjects 集合中是否有相同的 key 的嵌套对象
                    Object rowValue = nestedResultObjects.get(combinedKey);
                    boolean knownValue = rowValue != null;

                    // 步骤5：初始化外层对象中 Collection 类型的属性
                    instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory

                    // 步骤6：根据 notNullColumn 属性检测结果集中的空值
                    if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
                        // 步骤7：完成嵌套映射，并生成嵌套对象
                        rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);

                        // 注意："!knownValue" 这个条件，当嵌套对象已存在于 nestedResultObjects 集合中时，
                        // 说明相关列已经映射成了嵌套对象。现假设对象 A 中有 b1 和 b2 两个属性都指向了对象 B
                        // 且这两个属性都是由同一 ResultMap 进行映射的。在对一行记录进行映射时，首先映射的 b1
                        // 属性会生成 B 对象且成功赋值，而 b2 属性则为 null
                        if (rowValue != null && !knownValue) {
                            // 步骤8：将步骤 7 中得到的嵌套对象保存到外层对象的相应属性中
                            linkObjects(metaObject, resultMapping, rowValue);
                            foundValues = true;
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            ResultSet rs = rsw.getResultSet();
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    return true;
                }
            }
            return false;
        } else if (columnPrefix != null) {
            for (String columnName : rsw.getColumnNames()) {
                if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    //
    // UNIQUE RESULT KEY
    //

    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.isEmpty()) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                createRowKeyForMap(rsw, cacheKey);
            } else {
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    /**
     * 合并 rowKey 与 parentRowKey
     * @param rowKey
     * @param parentRowKey
     * @return
     */
    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            // 与外层对象的 CacheKey 合并，形成嵌套对象最终的 CacheKey
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.isEmpty()) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                // 递归调用 createRowKeyForMappedProperties() 方法处理嵌套映射直到最内层不嵌套为止
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                    prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null || configuration.isReturnInstanceForEmptyRow()) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        // 检查外层对象的指定属性是否为 Collection 类型，如果是且未初始化，则初始化该集合属性并返回
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        // 根据属性是否为集合类型，调用 MetaObject 的相应方法，将嵌套对象记录到外层对象的相应属性中
        if (collectionProperty != null) {
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        // 获取指定的属性名称和当前属性值
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {// 检测该属性是否已初始化
            Class<?> type = resultMapping.getJavaType();// 获取属性的 Java 类型
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {// 指定属性为集合类型
                    // 通过 ObjectFactory 创建该类型的集合对象，并进行相应设置
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;// 指定属性是集合类型且已经初始化，则返回该属性值
        }
        return null;
    }

    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
