/*
 * Copyright 2018 NAVER Corp.
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

package com.navercorp.pinpoint.it.plugin.jdbc.mysql;

import com.navercorp.pinpoint.bootstrap.context.DatabaseInfo;
import com.navercorp.pinpoint.bootstrap.plugin.jdbc.DatabaseInfoAccessor;
import com.navercorp.pinpoint.it.plugin.utils.AgentPath;
import com.navercorp.pinpoint.it.plugin.utils.TestcontainersOption;
import com.navercorp.pinpoint.it.plugin.utils.jdbc.DriverProperties;
import com.navercorp.pinpoint.it.plugin.utils.jdbc.JDBCDriverClass;
import com.navercorp.pinpoint.it.plugin.utils.jdbc.JDBCTestConstants;
import com.navercorp.pinpoint.test.plugin.Dependency;
import com.navercorp.pinpoint.test.plugin.PinpointAgent;
import com.navercorp.pinpoint.test.plugin.PluginTest;
import com.navercorp.pinpoint.test.plugin.shared.SharedDependency;
import com.navercorp.pinpoint.test.plugin.shared.SharedTestLifeCycleClass;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.navercorp.pinpoint.it.plugin.utils.jdbc.JdbcUtils.doInTransaction;
import static com.navercorp.pinpoint.it.plugin.utils.jdbc.JdbcUtils.fetchResultSet;

/**
 * @author emeroad
 */
@PluginTest
@PinpointAgent(AgentPath.PATH)
@Dependency({"mysql:mysql-connector-java:[6.min,6.max]", JDBCTestConstants.VERSION})
@SharedDependency({"mysql:mysql-connector-java:8.0.28", JDBCTestConstants.VERSION, TestcontainersOption.MYSQLDB})
@SharedTestLifeCycleClass(MySqlServer5.class)
public class MySqlConnection_6_X_IT extends MySql_IT_Base {

    private final Logger logger = LogManager.getLogger(this.getClass());

    private final JDBCDriverClass driverClass = new MySql6JDBCDriverClass();

    @Override
    protected JDBCDriverClass getJDBCDriverClass() {
        return driverClass;
    }

    @Test
    public void testModify() throws Exception {

        DriverProperties driverProperties = getDriverProperties();
        final Connection con = getConnection(driverProperties);
        try (Connection connection = con) {

            logger.info("Connection class name:{}", connection.getClass().getName());
            logger.info("Connection class cl:{}", connection.getClass().getClassLoader());

            DatabaseInfo url = ((DatabaseInfoAccessor) connection)._$PINPOINT$_getDatabaseInfo();
            Assertions.assertNotNull(url);

            statement(connection);

            preparedStatement(connection);

            preparedStatement2(connection);

            preparedStatement3(connection);
        }

        DatabaseInfo clearUrl = ((DatabaseInfoAccessor) con)._$PINPOINT$_getDatabaseInfo();
        Assertions.assertNull(clearUrl);
    }

    private void statement(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select 1");
        }
    }


    private void preparedStatement(Connection connection) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("select 1")) {
            logger.info("PreparedStatement className:{}", preparedStatement.getClass().getName());
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                fetchResultSet(resultSet);
            }
        }
    }

    private void preparedStatement2(Connection connection) throws SQLException {
         try (PreparedStatement preparedStatement = connection.prepareStatement("select * from member where id = ?")) {
             preparedStatement.setInt(1, 1);
             try (ResultSet resultSet = preparedStatement.executeQuery()) {
                 fetchResultSet(resultSet);
             }
         }
    }

    private void preparedStatement3(Connection connection) throws SQLException {
        doInTransaction(connection, () -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement("select * from member where id = ? or id = ?  or id = ?")) {
                preparedStatement.setInt(1, 1);
                preparedStatement.setInt(2, 2);
                preparedStatement.setString(3, "3");
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    fetchResultSet(resultSet);
                }
            }
        });
    }
}
