package org.apache.ibatis.session;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.Before;
import org.junit.jupiter.api.Test;

/**
 * @Author: maqi
 * @Date: 2020-03-05 15:34
 * @Version 1.0
 */
public class JdbcTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/semi?useUnicode=true&characterEncoding=UTF-8";
    private static final String USERNAME = "root";
    private static final String PWD = "123456";

    private InnerSqlSession sqlSession = new InnerSqlSession();

    @Before
    public void init() {
        try {
            //Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            Class.forName("com.mysql.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private class SqlProxy implements InvocationHandler {

        private ResultSet resultSet;

        public SqlProxy(ResultSet resultSet) {
            this.resultSet = resultSet;
        }

        public ResultSet getProxy() {
            return (ResultSet)Proxy.newProxyInstance(resultSet.getClass().getClassLoader(), new Class[] {ResultSet.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(resultSet, args);
            if ("next".equals(method.getName())) {
                if (!(boolean)result) { resultSet.close(); }
            }
            return result;
        }
    }

    private interface Action {
        <T> T invoke(ResultSet resultSet) throws Exception;
    }

    private abstract class IteratorAction implements Action {
        private int rowCount;

        public int getRowCount() {
            return rowCount;
        }

        @Override
        public <T> T invoke(ResultSet resultSet) throws Exception {
            // todo：这里应该抛异常还是返回 null ？个人觉得应该抛异常，因为这已经是异常情况了，需要抛出去让人知晓
            if (resultSet == null) { throw new IllegalArgumentException("resultSet must not be null!"); }
            if (resultSet.isClosed()) { throw new IllegalArgumentException("resultSet must not be closed!"); }

            List<T> result = new ArrayList<>();
            // todo：这里要思考下，循环的行数是否应该要给个上限，不能一直循环下去，因为 MyBatis 里是有这个配置的（见 RowBounds 类）
            while (resultSet.next() && !resultSet.isClosed()) {
                rowCount++;
                Map<String, Object> row = assembleRow(resultSet);
                T t = iterator(row);
                // 添加 iterator() 处理后返回的数据
                result.add(t);
            }
            return (T)result;
        }

        /**
         * 拼装一行数据
         *
         * @param resultSet
         * @return
         * @throws Exception
         */
        private Map<String, Object> assembleRow(ResultSet resultSet) throws Exception {
            ResultSetMetaData metaData = resultSet.getMetaData();
            Map<String, Object> row = new HashMap<>(Math.max((int)(metaData.getColumnCount() / .75f) + 1, 16));
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                //System.out.println(metaData.getColumnType(i) + ":" + metaData.getColumnTypeName(i) + ":" + metaData.getColumnClassName(i));
                row.put(metaData.getColumnName(i), resultSet.getObject(i));
            }
            return row;
        }

        public abstract <T> T iterator(Map<String, Object> row) throws Exception;
    }

    private class InnerSqlSession {

        private SqlCommandType sqlCommandType;

        private <T> T operate(String sql, Action action) {
            Connection conn = null;
            Statement statement = null;
            try {
                conn = DriverManager.getConnection(URL, USERNAME, PWD);
                statement = conn.createStatement();
                //statement.setQueryTimeout(5);// 设置超时时间
                try {
                    ResultSet resultSet;
                    switch (sqlCommandType) {
                        case UPDATE:
                        case DELETE:
                        case INSERT: {
                            // 设置 autoGeneratedKeys 策略（Statement.RETURN_GENERATED_KEYS、Statement.NO_GENERATED_KEYS 只有返回、不返回这两种方式）
                            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
                            resultSet = statement.getGeneratedKeys();
                            break;
                        }
                        case SELECT: {
                            resultSet = statement.executeQuery(sql);
                            break;
                        }
                        default:
                            throw new Exception("Unknown execution method for: " + sqlCommandType);
                    }
                    return action.invoke(resultSet);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            } finally {
                try {
                    if (conn != null) { conn.close(); }
                    if (statement != null) { statement.close(); }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }

        void printSelect(String sql) {
            sqlCommandType = SqlCommandType.SELECT;
            operate(sql, new IteratorAction() {
                @Override
                public <T> T iterator(Map<String, Object> row) {
                    System.out.println(row);
                    return null;
                }
            });
        }

        Map<String, Object> selectOne(String sql) {
            sqlCommandType = SqlCommandType.SELECT;
            Object result = operate(sql, new IteratorAction() {
                @Override
                public <T> T iterator(Map<String, Object> row) throws Exception {
                    if (getRowCount() > 1) { throw new Exception("Expected one result (or null) to be returned by selectOne(), but found:"); }
                    return (T)row;
                }
            });
            if (result instanceof List) {
                return ((List)result).isEmpty() ? null : ((List<Map<String, Object>>)result).get(0);
            }
            return null;
        }

        List<Map<String, Object>> selectList(String sql) {
            sqlCommandType = SqlCommandType.SELECT;
            Object result = operate(sql, new IteratorAction() {
                @Override
                public <T> T iterator(Map<String, Object> row) throws Exception {
                    return (T)row;
                }
            });
            if (result instanceof List) {
                return ((List)result).isEmpty() ? null : (List<Map<String, Object>>)result;
            }
            return null;
        }

        int modify(String modifySql, SqlCommandType sqlCommandType) {
            this.sqlCommandType = sqlCommandType;
            return operate(modifySql, new Action() {
                @Override
                public <T> T invoke(ResultSet resultSet) throws Exception {
                    // resultSet.next() 方法只有 insert 才会返回 true,update、delete 都返回 false
                    while (resultSet.next()) {
                        System.out.println(String.format(
                            "columnCount:%s, columnName:%s, columnValue:%s, columnLabel:%s, columnType:%s, columnTypeName:%s, columnClassName:%s",
                            resultSet.getMetaData().getColumnCount(),
                            resultSet.getMetaData().getColumnName(1),
                            resultSet.getObject(1),// 该值
                            resultSet.getMetaData().getColumnLabel(1),
                            resultSet.getMetaData().getColumnType(1),
                            resultSet.getMetaData().getColumnTypeName(1),
                            resultSet.getMetaData().getColumnClassName(1)
                        ));
                    }
                    return (T)new Integer(resultSet.getStatement().getUpdateCount());
                }
            });
        }

        int insert(String insertSql) {
            return modify(insertSql, SqlCommandType.INSERT);
        }

        int update(String updateSql) {
            return modify(updateSql, SqlCommandType.UPDATE);
        }

        int delete(String deleteSql) {
            return modify(deleteSql, SqlCommandType.DELETE);
        }
    }

    @Test
    public void select() {
        sqlSession.printSelect("select * from prize where id in (5,6)");
        Map<String, Object> result = sqlSession.selectOne("select * from prize where id = 7");
        System.out.println(result);

        Map<String, Object> result1 = sqlSession.selectOne("select count(*) as cnt from prize");
        System.out.println(result1);

        List<Map<String, Object>> result2 = sqlSession.selectList("explain select * from prize where name like '%奖%'");
        System.out.println(result2);

        List<Map<String, Object>> result3 = sqlSession.selectList("explain select * from prize where id = 5");
        System.out.println(result3);

        System.out.println(sqlSession.insert("insert into prize(name, probability, stock, surplus_stock, is_valid, create_time) value('test', 0.1, 100, 200, 1, now())"));

        System.out.println(sqlSession.update("update prize set name = 'test1' where id in(5,6)"));
    }

    @Test
    public void aa() {

    }
}
