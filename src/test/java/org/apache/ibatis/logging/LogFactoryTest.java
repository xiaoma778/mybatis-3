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
package org.apache.ibatis.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BoundBlogMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.domain.blog.mappers.AuthorMapper;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl;
import org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl;
import org.apache.ibatis.logging.log4j.Log4jImpl;
import org.apache.ibatis.logging.log4j2.Log4j2Impl;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

class LogFactoryTest {

    @Test
    void shouldUseCommonsLogging() {
        LogFactory.useCommonsLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), JakartaCommonsLoggingImpl.class.getName());
    }

    @Test
    void shouldUseLog4J() {
        LogFactory.useLog4JLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), Log4jImpl.class.getName());
    }

    @Test
    void shouldUseLog4J2() {
        LogFactory.useLog4J2Logging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), Log4j2Impl.class.getName());
    }

    @Test
    void shouldUseJdKLogging() {
        LogFactory.useJdkLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), Jdk14LoggingImpl.class.getName());
    }

    @Test
    void shouldUseSlf4j() {
        LogFactory.useSlf4jLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), Slf4jImpl.class.getName());
    }

    @Test
    void shouldUseStdOut() {
        LogFactory.useStdOutLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), StdOutImpl.class.getName());
    }

    @Test
    void shouldUseNoLogging() {
        LogFactory.useNoLogging();
        Log log = LogFactory.getLog(Object.class);
        logSomething(log);
        assertEquals(log.getClass().getName(), NoLoggingImpl.class.getName());
    }

    @Test
    void shouldReadLogImplFromSettings() throws Exception {
        try (Reader reader = Resources.getResourceAsReader("org/apache/ibatis/logging/mybatis-config.xml")) {
            new SqlSessionFactoryBuilder().build(reader);
        }

        Log log = LogFactory.getLog(Object.class);
        log.debug("Debug message.");
        assertEquals(log.getClass().getName(), NoLoggingImpl.class.getName());
    }

    private void logSomething(Log log) {
        log.warn("Warning message.");
        log.debug("Debug message.");
        log.error("Error message.");
        log.error("Error with Exception.", new Exception("Test exception."));
    }

    class ProxyHandler<T> implements InvocationHandler {

        private T t;

        public ProxyHandler(T t) {
            this.t = t;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            System.out.println("before!");
            Object result = method.invoke(t, args);
            System.out.println("after!");
            return result;
        }

        public T newInstance() {
            return (T)Proxy.newProxyInstance(
                t.getClass().getClassLoader(),
                //Thread.currentThread().getContextClassLoader(),
                new Class[] {Log.class},
                //t.getClass().getInterfaces()
                this
            );
        }
    }

    class MapperProxyHandler<T> implements InvocationHandler {
        private Class<T> mapperInterface;

        private SqlSession mockSqlSession = new SqlSession() {
            @Override
            public <T> T selectOne(String statement) {
                return null;
            }

            @Override
            public <T> T selectOne(String statement, Object parameter) {
                System.out.println("select * from blog where id = " + parameter);
                return null;
            }

            @Override
            public <E> List<E> selectList(String statement) {
                return null;
            }

            @Override
            public <E> List<E> selectList(String statement, Object parameter) {
                return null;
            }

            @Override
            public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
                return null;
            }

            @Override
            public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
                return null;
            }

            @Override
            public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
                return null;
            }

            @Override
            public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
                return null;
            }

            @Override
            public <T> Cursor<T> selectCursor(String statement) {
                return null;
            }

            @Override
            public <T> Cursor<T> selectCursor(String statement, Object parameter) {
                return null;
            }

            @Override
            public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
                return null;
            }

            @Override
            public void select(String statement, Object parameter, ResultHandler handler) {

            }

            @Override
            public void select(String statement, ResultHandler handler) {

            }

            @Override
            public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {

            }

            @Override
            public int insert(String statement) {
                return 0;
            }

            @Override
            public int insert(String statement, Object parameter) {
                return 0;
            }

            @Override
            public int update(String statement) {
                return 0;
            }

            @Override
            public int update(String statement, Object parameter) {
                return 0;
            }

            @Override
            public int delete(String statement) {
                return 0;
            }

            @Override
            public int delete(String statement, Object parameter) {
                return 0;
            }

            @Override
            public void commit() {

            }

            @Override
            public void commit(boolean force) {

            }

            @Override
            public void rollback() {

            }

            @Override
            public void rollback(boolean force) {

            }

            @Override
            public List<BatchResult> flushStatements() {
                return null;
            }

            @Override
            public void close() {

            }

            @Override
            public void clearCache() {

            }

            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public <T> T getMapper(Class<T> type) {
                return null;
            }

            @Override
            public Connection getConnection() {
                return null;
            }
        };

        public MapperProxyHandler(Class<T> mapperInterface) {
            this.mapperInterface = mapperInterface;
            parseMapper(mapperInterface);
        }

        public T getProxy() {
            return (T)Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] {mapperInterface}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                //如果目标方法继承自 Object，则直接调用目标方法
                if (Object.class.equals(method.getDeclaringClass())) {
                    return method.invoke(this, args);
                } else {
                    // MyBatis 动态代理最终就是解析 Mapper 接口和 xml 或者 注解之间的关系，找到要执行的 sql，并将相关参数设置进去之后，
                    // 通过 SqlSession 操作数据库再将操作结果返回回去。所以 MyBatis 中的 Mapper 接口不需要实现类
                    String statementId = mapperInterface.getName() + "." + method.getName();
                    System.out.println(String.format("sql 解析结果：%s", statementId));
                    //MappedStatement ms = configuration.getMappedStatement(statementId);
                    //String sql = ms.getSqlSource().getBoundSql(args).getSql();
                    //System.out.println(sql);
                    //configuration.getMapper()
                    Object object = mockSqlSession.selectOne(statementId, args.length == 1 ? args[0] : args);
                    System.out.println("sql 执行完毕！");
                    return object;
                }
            } catch (Throwable t) {
                throw new Throwable();
            }
        }

        Configuration configuration = new Configuration();

        private void parseMapper(Class<T> mapperInterface) {
            //String resource = "org/apache/ibatis/binding/BoundBlogMapper.xml";
            String resource = mapperInterface.getName().replace(".", "/") + ".xml";
            try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
                XMLMapperBuilder builder = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                builder.parse();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void proxy() {
        Log log = new Log() {
            @Override
            public boolean isDebugEnabled() {
                System.out.println("isDebugEnabled invoked!");
                return false;
            }

            @Override
            public boolean isTraceEnabled() {
                return false;
            }

            @Override
            public void error(String s, Throwable e) {

            }

            @Override
            public void error(String s) {

            }

            @Override
            public void debug(String s) {

            }

            @Override
            public void trace(String s) {

            }

            @Override
            public void warn(String s) {

            }
        };
        Log logProxy = new ProxyHandler<>(log).newInstance();
        logProxy.isDebugEnabled();

        System.out.println();

        AuthorMapper mapper = new MapperProxyHandler<>(AuthorMapper.class).getProxy();
        mapper.selectAuthor(3);
    }

}
