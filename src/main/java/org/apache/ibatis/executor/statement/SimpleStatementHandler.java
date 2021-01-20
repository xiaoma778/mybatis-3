/**
 * Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 该类底层是由 java.sql.Statement 对象来完成数据库的相关操作，所以 SQL 语句中不能存在占位符<br />
 * 所以相应的 parameterize() 就是个空实现
 * @author Clinton Begin
 */
public class SimpleStatementHandler extends BaseStatementHandler {

    public SimpleStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
    }

    /**
     * 执行 insert、update 或 delete 等类型的 SQL 语句，并且会根据配置的 KeyGenerator 获取数据库生成的主键
     * @param statement
     * @return
     * @throws SQLException
     */
    @Override
    public int update(Statement statement) throws SQLException {
        String sql = boundSql.getSql(); // 获取 SQL 语句
        Object parameterObject = boundSql.getParameterObject(); // 获取用户传入的实参
        // 配置 KeyGenerator 对象
        KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
        int rows;
        if (keyGenerator instanceof Jdbc3KeyGenerator) {
            statement.execute(sql, Statement.RETURN_GENERATED_KEYS); // 执行 SQL 语句
            rows = statement.getUpdateCount(); // 获取受影响的行数
            // 将数据库生成的主键添加到 parameterObject 中
            keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
        } else if (keyGenerator instanceof SelectKeyGenerator) {
            statement.execute(sql); // 执行 SQL 语句
            rows = statement.getUpdateCount(); // 获取受影响的行数
            // 执行 <selectKey> 节点中配置的 SQL 语句获取数据库生成的主键，并添加到 parameterObject 中
            keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
        } else {
            statement.execute(sql);
            rows = statement.getUpdateCount();
        }
        return rows;
    }

    @Override
    public void batch(Statement statement) throws SQLException {
        String sql = boundSql.getSql();
        statement.addBatch(sql);
    }

    @Override
    public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
        String sql = boundSql.getSql(); // 获取 SQL 语句
        statement.execute(sql); // 执行 SQL 语句
        return resultSetHandler.handleResultSets(statement); // 映射结果集
    }

    @Override
    public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
        String sql = boundSql.getSql();
        statement.execute(sql);
        return resultSetHandler.handleCursorResultSets(statement);
    }

    @Override
    protected Statement instantiateStatement(Connection connection) throws SQLException {
        if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
            return connection.createStatement();
        } else {
            // 设置结果集是否可以滚动及其游标是否可以上下移动，设置结果集是否可更新
            return connection.createStatement(mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
        }
    }

    @Override
    public void parameterize(Statement statement) {
        // N/A
    }

}
