/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingjdbc.core.jdbc.core.statement;

import com.google.common.base.Optional;
import io.shardingjdbc.core.constant.SQLType;
import io.shardingjdbc.core.executor.type.statement.StatementExecutor;
import io.shardingjdbc.core.executor.type.statement.StatementUnit;
import io.shardingjdbc.core.jdbc.adapter.AbstractStatementAdapter;
import io.shardingjdbc.core.jdbc.core.connection.ShardingConnection;
import io.shardingjdbc.core.jdbc.core.resultset.GeneratedKeysResultSet;
import io.shardingjdbc.core.jdbc.core.resultset.ShardingResultSet;
import io.shardingjdbc.core.merger.DALMergeEngine;
import io.shardingjdbc.core.merger.MergeEngine;
import io.shardingjdbc.core.merger.SelectMergeEngine;
import io.shardingjdbc.core.parsing.parser.context.GeneratedKey;
import io.shardingjdbc.core.parsing.parser.sql.dal.DALStatement;
import io.shardingjdbc.core.parsing.parser.sql.dml.insert.InsertStatement;
import io.shardingjdbc.core.parsing.parser.sql.dql.DQLStatement;
import io.shardingjdbc.core.parsing.parser.sql.dql.select.SelectStatement;
import io.shardingjdbc.core.routing.SQLExecutionUnit;
import io.shardingjdbc.core.routing.SQLRouteResult;
import io.shardingjdbc.core.routing.StatementRoutingEngine;
import lombok.AccessLevel;
import lombok.Getter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Statement that support sharding.
 * 
 * @author gaohongtao
 * @author caohao
 * @author zhangliang
 */
@Getter
public class ShardingStatement extends AbstractStatementAdapter {
    
    private final ShardingConnection connection;
    
    private final int resultSetType;
    
    private final int resultSetConcurrency;
    
    private final int resultSetHoldability;
    
    private final Collection<Statement> routedStatements = new LinkedList<>();
    
    @Getter(AccessLevel.NONE)
    private boolean returnGeneratedKeys;
    
    @Getter(AccessLevel.NONE)
    private SQLRouteResult routeResult;
    
    @Getter(AccessLevel.NONE)
    private ResultSet currentResultSet;
    
    public ShardingStatement(final ShardingConnection connection) {
        this(connection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingStatement(final ShardingConnection connection, final int resultSetType, final int resultSetConcurrency) {
        this(connection, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }
    
    public ShardingStatement(final ShardingConnection connection, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        super(Statement.class);
        this.connection = connection;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
    }
    
    @Override
    public ResultSet executeQuery(final String sql) throws SQLException {
        ResultSet result;
        try {
            List<ResultSet> resultSets = generateExecutor(sql).executeQuery();
            MergeEngine mergeEngine;
            if (routeResult.getSqlStatement() instanceof SelectStatement) {
                mergeEngine = new SelectMergeEngine(resultSets, (SelectStatement) routeResult.getSqlStatement());
            } else if (routeResult.getSqlStatement() instanceof DALStatement) {
                mergeEngine = new DALMergeEngine(connection.getShardingContext().getShardingRule(), resultSets, (DALStatement) routeResult.getSqlStatement());
            } else {
                throw new UnsupportedOperationException(String.format("Cannot support type '%s'", routeResult.getSqlStatement().getType()));
            }
            result = new ShardingResultSet(resultSets, mergeEngine.merge(), this);
        } finally {
            currentResultSet = null;
        }
        currentResultSet = result;
        return result;
    }
    
    @Override
    public int executeUpdate(final String sql) throws SQLException {
        try {
            return generateExecutor(sql).executeUpdate();
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) throws SQLException {
        if (RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            returnGeneratedKeys = true;
        }
        try {
            return generateExecutor(sql).executeUpdate(autoGeneratedKeys);
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) throws SQLException {
        returnGeneratedKeys = true;
        try {
            return generateExecutor(sql).executeUpdate(columnIndexes);
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public int executeUpdate(final String sql, final String[] columnNames) throws SQLException {
        returnGeneratedKeys = true;
        try {
            return generateExecutor(sql).executeUpdate(columnNames);
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public boolean execute(final String sql) throws SQLException {
        try {
            return generateExecutor(sql).execute();
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) throws SQLException {
        if (RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            returnGeneratedKeys = true;
        }
        try {
            return generateExecutor(sql).execute(autoGeneratedKeys);
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public boolean execute(final String sql, final int[] columnIndexes) throws SQLException {
        returnGeneratedKeys = true;
        try {
            return generateExecutor(sql).execute(columnIndexes);
        } finally {
            currentResultSet = null;
        }
    }
    
    @Override
    public boolean execute(final String sql, final String[] columnNames) throws SQLException {
        returnGeneratedKeys = true;
        try {
            return generateExecutor(sql).execute(columnNames);
        } finally {
            currentResultSet = null;
        }
    }
    
    private StatementExecutor generateExecutor(final String sql) throws SQLException {
        clearPrevious();
        routeResult = new StatementRoutingEngine(connection.getShardingContext()).route(sql);
        Collection<StatementUnit> statementUnits = new LinkedList<>();
        for (SQLExecutionUnit each : routeResult.getExecutionUnits()) {
            Collection<Connection> connections;
            SQLType sqlType = routeResult.getSqlStatement().getType();
            if (SQLType.DDL == sqlType) {
                connections = connection.getConnectionsForDDL(each.getDataSource());
            } else {
                connections = Collections.singletonList(connection.getConnection(each.getDataSource(), routeResult.getSqlStatement().getType()));
            }
            for (Connection connection : connections) {
                Statement statement = connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
                replayMethodsInvocation(statement);
                statementUnits.add(new StatementUnit(each, statement));
                routedStatements.add(statement);
            }
        }
        return new StatementExecutor(connection.getShardingContext().getExecutorEngine(), routeResult.getSqlStatement().getType(), statementUnits);
    }
    
    private void clearPrevious() throws SQLException {
        for (Statement each : routedStatements) {
            each.close();
        }
        routedStatements.clear();
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        if (returnGeneratedKeys && generatedKey.isPresent()) {
            return new GeneratedKeysResultSet(routeResult.getGeneratedKeys().iterator(), generatedKey.get().getColumn(), this);
        }
        if (1 == getRoutedStatements().size()) {
            return getRoutedStatements().iterator().next().getGeneratedKeys();
        }
        return new GeneratedKeysResultSet();
    }
    
    private Optional<GeneratedKey> getGeneratedKey() {
        if (null != routeResult && routeResult.getSqlStatement() instanceof InsertStatement) {
            return Optional.fromNullable(((InsertStatement) routeResult.getSqlStatement()).getGeneratedKey());
        }
        return Optional.absent();
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        if (1 == routedStatements.size() && routeResult.getSqlStatement() instanceof DQLStatement) {
            currentResultSet = routedStatements.iterator().next().getResultSet();
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(routedStatements.size());
        for (Statement each : routedStatements) {
            resultSets.add(each.getResultSet());
        }
        MergeEngine mergeEngine = null;
        if (routeResult.getSqlStatement() instanceof SelectStatement) {
            mergeEngine = new SelectMergeEngine(resultSets, (SelectStatement) routeResult.getSqlStatement());
        } else if (routeResult.getSqlStatement() instanceof DALStatement && !resultSets.isEmpty()) {
            mergeEngine = new DALMergeEngine(connection.getShardingContext().getShardingRule(), resultSets, (DALStatement) routeResult.getSqlStatement());
        }
        if (null != mergeEngine) {
            currentResultSet = new ShardingResultSet(resultSets, mergeEngine.merge(), this);
        }
        return currentResultSet;
    }
}
