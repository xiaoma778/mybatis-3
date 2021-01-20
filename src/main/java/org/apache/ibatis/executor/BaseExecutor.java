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
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 主要提供了 缓存管理 和 事务管理 的基本功能
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    /** 用来实现事务的提交、回滚和关闭操作*/
    protected Transaction transaction;
    protected Executor wrapper;

    /** 延迟加载队列*/
    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    /** 一级缓存，用于缓存该 Executor 对象查询结果集映射得到的结果对象*/
    protected PerpetualCache localCache;
    /** 一级缓存，用于缓存输出类型的参数*/
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    /** 用来记录嵌套查询的层数*/
    protected int queryStack;
    private boolean closed;

    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this;
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return doFlushStatements(isRollBack);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    /**
     * 该方法会根据 CacheKey 对象查询一级缓存，命中则返回对象，未命中则查询数据库，之后再将结果保存到
     * 一级缓存中。该方法会根据 MappedStatement.flushCacheRequired 属性 和 Configuration.localCacheScope
     * 配置决定是否清空一级缓存。另，每次调用 update()、commit()、rollback() 方法时也会清空缓存
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param key
     * @param boundSql
     * @param <E>
     * @return
     * @throws SQLException
     */
    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        // 检测 Executor 是否已经关闭
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }

        // 非嵌套查询，并且 <select> 节点配置的 flushCache 属性为 true 时，才会清空一级缓存
        // flushCache 配置项是影响一级缓存中结果对象存活时长的第一个方面
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        List<E> list;
        try {
            queryStack++; // 增加查询层数
            // 查询一级缓存
            list = resultHandler == null ? (List<E>)localCache.getObject(key) : null;
            if (list != null) {
                // 针对存储过程调用的处理，其功能是：在一级缓存命中时，获取缓存中保存的输出类型参数，
                // 并设置到用户传入的实参（parameter）对象中
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                // 其中会调用 doQuery() 方法完成数据库查询，并得到映射后的结果对象，doQuery() 方法是一个
                // 抽象方法，由 BaseExecutor 子类具体实现
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            queryStack--; // 当前查询完成，查询层数减少
        }
        if (queryStack == 0) {
            // 延迟加载相关
            // 在最外层的查询结束时，所有嵌套查询也已经完成，相关缓存项也已经完全加载，所以在这里可以触发
            // DeferredLoad 加载一级缓存中记录的嵌套查询的结果对象
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            deferredLoads.clear(); // 加载完成后，清空 deferredLoads 集合
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                // 根据 configuration.localCacheScope 配置决定是否清空一级缓存，该配置是影响一级缓存
                // 中结果对象存活时长的第二个方面
                clearLocalCache();
            }
        }
        return list;
    }

    /**
     * 该方法的主要功能也是查询数据库，但它不会直接将结果集映射为结果对象，而是将结果集封装成 Cursor 对象并返回，待
     * 用户遍历 Cursor 是才真正完成结果集的映射操作，另外该方法是直接调用 doQueryCursor() 这个基本方法实现的，并
     * 不会想 query() 方法那样使用查询一级缓存
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param <E>
     * @return
     * @throws SQLException
     */
    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        if (deferredLoad.canLoad()) {
            deferredLoad.load();// 一级缓存有记录
        } else {
            // 将 DeferredLoad 对象添加到 deferredLoads 队列中，待整个外层查询结束后，再加载该对象结果
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    /**
     * 创建一级缓存的 CacheKey 对象，该对象由以下这 4~6 部分构成：
     * 1.MappedStatement.id
     * 2.RowBounds.offset
     * 3.RowBounds.limi
     * 4.SQL 语句（包含 "?" 占位符）
     * 5.用户传递的实参（如果有实参的话）
     * 6.Environment.id（如果 Configuration.environment 不为 null 的话）
     *
     * @param ms
     * @param parameterObject
     * @param rowBounds
     * @param boundSql
     * @return
     */
    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        // 检测 Executor 是否已经关闭
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(ms.getId()); // 将 MappedStatement 的 id 添加到 CacheKey 对象中
        cacheKey.update(rowBounds.getOffset()); // 将 offset 添加到 CacheKey 对象中
        cacheKey.update(rowBounds.getLimit()); // 将 limit 添加到 CacheKey 对象中
        cacheKey.update(boundSql.getSql()); // 将 SQL 语句添加到 CacheKey 对象中

        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic
        // 获取用户传入的实参，并添加到 CacheKey 对象中
        for (ParameterMapping parameterMapping : parameterMappings) {
            // 过滤掉输出类型的参数
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                // 将实参添加到 CacheKey 对象中
                cacheKey.update(value);
            }
        }
        // 如果 Environment 的 id 不为空，则将其添加到 CacheKey 中
        if (configuration.getEnvironment() != null) {
            // issue #176
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    /**
     * 提交事务
     * @param required
     * @throws SQLException
     */
    @Override
    public void commit(boolean required) throws SQLException {
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        clearLocalCache(); // 清空一级缓存
        flushStatements(); // 执行缓存的 SQL 语句，其中调用了 flushStatements(false) 方法
        if (required) { // 根据 required 参数决定是否提交事务
            transaction.commit();
        }
    }

    /**
     * 回滚事务
     * @param required
     * @throws SQLException
     */
    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache(); // 清空一级缓存
                flushStatements(true); // 执行缓存的 SQL 语句
            } finally {
                if (required) { // 根据 required 参数决定是否回滚事务
                    transaction.rollback();
                }
            }
        }
    }

    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    protected abstract int doUpdate(MappedStatement ms, Object parameter)
        throws SQLException;

    /**
     * 批量执行 SQL 语句
     * @param isRollback 是否回滚【ture:回滚（不执行 Executor 中缓存的SQL语句），false：不回滚（执行 Executor中缓存的SQL语句）】
     * @return
     * @throws SQLException
     */
    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
        throws SQLException;

    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
        throws SQLException;

    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
        throws SQLException;

    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Apply a transaction timeout.
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @since 3.4.0
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        localCache.putObject(key, EXECUTION_PLACEHOLDER); // 在缓存中添加占位符
        try {
            // 调用 doQuery() 方法（抽象方法），完成数据库查询操作，并返回结果对象
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            localCache.removeObject(key); // 删除占位符
        }
        localCache.putObject(key, list); // 将真正的结果对象添加到一级缓存中
        if (ms.getStatementType() == StatementType.CALLABLE) { // 是否为存储过程调用
            localOutputParameterCache.putObject(key, parameter); // 缓存输出类型的参数
        }
        return list;
    }

    protected Connection getConnection(Log statementLog) throws SQLException {
        Connection connection = transaction.getConnection();
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    /**
     * 负责从 BaseExecutor.loadCache 缓存中延迟加载结果对象
     */
    private static class DeferredLoad {

        /** 外层对象对应的 MetaObject 对象*/
        private final MetaObject resultObject;
        /** 延迟加载的属性名称*/
        private final String property;
        /** 延迟加载的属性的类型*/
        private final Class<?> targetType;
        /** 延迟加载的结果对象在一级缓存中相应的 CacheKey 对象*/
        private final CacheKey key;
        /** 一级缓存，与 BaseExecutor.loadCache 字段指向同一 PerpetualCache 对象*/
        private final PerpetualCache localCache;

        private final ObjectFactory objectFactory;
        /** 负责结果对象的类型转换*/
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        /**
         * 检测缓存项是否已经"完全加载"到了缓存中
         * "完全加载"含义：
         * BaseExecutor.queryFromDatabase() 方法中，开始查询调用 doQuery() 方法查询数据库之前，会先在
         * loadCache 中添加占位符，待查询完成之后，才将真正的结果对象放到 loadCache 中缓存，此时该缓存才
         * 算"完全加载"
         * @return
         */
        public boolean canLoad() {
            // 检测缓存是否存在指定的结果对象、检测是否为占位符
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        public void load() {
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
            // 从缓存中查询指定的结果对象
            List<Object> list = (List<Object>)localCache.getObject(key);
            // 将缓存的结果对象转换成指定类型
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            // 设置到外层对象的对应属性
            resultObject.setValue(property, value);
        }

    }

}
