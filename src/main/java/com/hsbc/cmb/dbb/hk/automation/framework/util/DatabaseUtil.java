package com.hsbc.cmb.dbb.hk.automation.framework.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/**
 * 数据库工具类
 * 提供统一的数据库连接和操作方法，支持多种数据库类型和连接池
 */
public class DatabaseUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseUtil.class);
    
    // 数据库类型枚举
    public enum DatabaseType {
        MYSQL("com.mysql.cj.jdbc.Driver"),
        POSTGRESQL("org.postgresql.Driver"),
        ORACLE("oracle.jdbc.driver.OracleDriver"),
        SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver"),
        DB2("com.ibm.db2.jcc.DB2Driver");
        
        private final String driverClass;
        
        DatabaseType(String driverClass) {
            this.driverClass = driverClass;
        }
        
        public String getDriverClass() {
            return driverClass;
        }
    }
    
    // 连接池配置
    private static HikariDataSource dataSource;
    private static DatabaseType databaseType;
    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;
    private static boolean useConnectionPool = false;
    
    // 事务管理
    private static final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> isInTransaction = new ThreadLocal<>();
    
    // 数据库连接池配置
    private static final int DEFAULT_POOL_SIZE = 10;
    private static int maxPoolSize = DEFAULT_POOL_SIZE;
    private static int minIdle = 5;
    private static int connectionTimeout = 30000;
    private static int idleTimeout = 600000;
    private static int maxLifetime = 1800000;
    
    /**
     * 初始化数据库配置
     * @param type 数据库类型
     * @param url 数据库URL
     * @param user 数据库用户名
     * @param password 数据库密码
     * @param usePool 是否使用连接池
     */
    public static void initializeDatabaseConfig(DatabaseType type, String url, String user, String password, boolean usePool) {
        databaseType = type;
        dbUrl = url;
        dbUser = user;
        dbPassword = password;
        useConnectionPool = usePool;
        
        if (useConnectionPool) {
            initializeConnectionPool();
        }
        
        logger.info("Database configuration initialized: type={}, url={}, user={}, pool={}", 
            databaseType, maskPasswordInUrl(dbUrl), maskPassword(dbUser), useConnectionPool);
    }
    
    /**
     * 初始化连接池
     */
    private static void initializeConnectionPool() {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(dbUrl);
            config.setUsername(dbUser);
            config.setPassword(dbPassword);
            config.setDriverClassName(databaseType.getDriverClass());
            config.setMaximumPoolSize(maxPoolSize);
            config.setMinimumIdle(minIdle);
            config.setConnectionTimeout(connectionTimeout);
            config.setIdleTimeout(idleTimeout);
            config.setMaxLifetime(maxLifetime);
            config.setPoolName("DatabasePool-" + databaseType.name());
            
            // 数据库特定配置
            switch (databaseType) {
                case MYSQL:
                    config.addDataSourceProperty("cachePrepStmts", "true");
                    config.addDataSourceProperty("prepStmtCacheSize", "250");
                    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                    config.addDataSourceProperty("useSSL", "false");
                    config.addDataSourceProperty("serverTimezone", "UTC");
                    break;
                case POSTGRESQL:
                    config.addDataSourceProperty("ssl", "false");
                    config.addDataSourceProperty("sslmode", "disable");
                    break;
                case ORACLE:
                    config.addDataSourceProperty("oracle.jdbc.timezoneAsRegion", "false");
                    break;
                case SQLSERVER:
                    config.addDataSourceProperty("encrypt", "false");
                    config.addDataSourceProperty("trustServerCertificate", "true");
                    break;
                case DB2:
                    config.addDataSourceProperty("driverType", "4");
                    break;
            }
            
            dataSource = new HikariDataSource(config);
            logger.info("Connection pool initialized successfully with {} max connections", maxPoolSize);
            
        } catch (Exception e) {
            logger.error("Failed to initialize connection pool", e);
            throw new RuntimeException("Failed to initialize connection pool", e);
        }
    }
    
    /**
     * 获取数据库连接
     * @return 数据库连接对象
     * @throws SQLException 如果连接失败
     */
    public static Connection getConnection() throws SQLException {
        // 如果在事务中，返回事务连接
        if (isInTransaction.get() != null && isInTransaction.get()) {
            Connection conn = transactionConnection.get();
            if (conn == null || conn.isClosed()) {
                conn = getNewConnection();
                transactionConnection.set(conn);
            }
            return conn;
        }
        
        // 使用连接池
        if (useConnectionPool && dataSource != null) {
            return dataSource.getConnection();
        }
        
        // 不使用连接池
        return getNewConnection();
    }
    
    /**
     * 获取新连接（不使用连接池）
     * @return 新的数据库连接
     * @throws SQLException 如果连接失败
     */
    private static Connection getNewConnection() throws SQLException {
        try {
            Class.forName(databaseType.getDriverClass());
            Properties props = new Properties();
            props.setProperty("user", dbUser);
            props.setProperty("password", dbPassword);
            
            // 数据库特定属性
            switch (databaseType) {
                case MYSQL:
                    props.setProperty("useSSL", "false");
                    props.setProperty("serverTimezone", "UTC");
                    break;
                case POSTGRESQL:
                    props.setProperty("ssl", "false");
                    props.setProperty("sslmode", "disable");
                    break;
                case ORACLE:
                    props.setProperty("oracle.jdbc.timezoneAsRegion", "false");
                    break;
                case SQLSERVER:
                    props.setProperty("encrypt", "false");
                    props.setProperty("trustServerCertificate", "true");
                    break;
            }
            
            Connection connection = DriverManager.getConnection(dbUrl, props);
            logger.debug("New database connection established successfully");
            return connection;
        } catch (ClassNotFoundException e) {
            logger.error("JDBC driver not found: {}", databaseType.getDriverClass(), e);
            throw new SQLException("JDBC driver not found: " + databaseType.getDriverClass(), e);
        } catch (SQLException e) {
            logger.error("Failed to establish database connection to: {}", maskPasswordInUrl(dbUrl), e);
            throw e;
        }
    }
    
    /**
     * 开始事务
     * @throws SQLException 如果事务开始失败
     */
    public static void beginTransaction() throws SQLException {
        Connection connection = getConnection();
        connection.setAutoCommit(false);
        transactionConnection.set(connection);
        isInTransaction.set(true);
        logger.debug("Transaction started");
    }
    
    /**
     * 提交事务
     * @throws SQLException 如果事务提交失败
     */
    public static void commitTransaction() throws SQLException {
        Connection connection = transactionConnection.get();
        if (connection != null && !connection.isClosed() && isInTransaction.get() != null && isInTransaction.get()) {
            try {
                connection.commit();
                logger.debug("Transaction committed successfully");
            } finally {
                closeTransactionConnection();
            }
        }
    }
    
    /**
     * 回滚事务
     * @throws SQLException 如果事务回滚失败
     */
    public static void rollbackTransaction() throws SQLException {
        Connection connection = transactionConnection.get();
        if (connection != null && !connection.isClosed() && isInTransaction.get() != null && isInTransaction.get()) {
            try {
                connection.rollback();
                logger.debug("Transaction rolled back");
            } finally {
                closeTransactionConnection();
            }
        }
    }
    
    /**
     * 关闭事务连接
     */
    private static void closeTransactionConnection() {
        Connection connection = transactionConnection.get();
        if (connection != null) {
            try {
                connection.close();
                logger.debug("Transaction connection closed");
            } catch (SQLException e) {
                logger.warn("Failed to close transaction connection", e);
            } finally {
                transactionConnection.remove();
                isInTransaction.remove();
            }
        }
    }
    
    /**
     * 执行查询操作
     * @param sql 查询SQL语句
     * @param params 查询参数
     * @return 查询结果列表
     * @throws SQLException 如果查询失败
     */
    public static List<Map<String, Object>> executeQuery(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> resultList = new ArrayList<>();
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        
        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            
            // 设置查询参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
            }
            
            logger.debug("Executing query: {}", sql);
            resultSet = statement.executeQuery();
            
            // 处理结果集
            while (resultSet.next()) {
                Map<String, Object> row = new HashMap<>();
                int columnCount = resultSet.getMetaData().getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = resultSet.getMetaData().getColumnName(i);
                    row.put(columnName, resultSet.getObject(i));
                }
                resultList.add(row);
            }
            
            logger.debug("Query executed successfully, returned {} rows", resultList.size());
            return resultList;
            
        } catch (SQLException e) {
            logger.error("Failed to execute query: {}", sql, e);
            throw e;
        } finally {
            closeResources(resultSet, statement, connection);
        }
    }
    
    /**
     * 执行更新操作（INSERT, UPDATE, DELETE）
     * @param sql 更新SQL语句
     * @param params 更新参数
     * @return 影响的行数
     * @throws SQLException 如果更新失败
     */
    public static int executeUpdate(String sql, Object... params) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        
        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            
            // 设置更新参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
            }
            
            logger.debug("Executing update: {}", sql);
            int affectedRows = statement.executeUpdate();
            
            logger.debug("Update executed successfully, affected {} rows", affectedRows);
            return affectedRows;
            
        } catch (SQLException e) {
            logger.error("Failed to execute update: {}", sql, e);
            throw e;
        } finally {
            closeResources(null, statement, connection);
        }
    }
    
    /**
     * 执行批量更新操作
     * @param sql 更新SQL语句
     * @param paramsList 参数列表
     * @return 影响的行数数组
     * @throws SQLException 如果批量更新失败
     */
    public static int[] executeBatchUpdate(String sql, List<Object[]> paramsList) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        
        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            
            // 添加批处理参数
            if (paramsList != null) {
                for (Object[] params : paramsList) {
                    for (int i = 0; i < params.length; i++) {
                        statement.setObject(i + 1, params[i]);
                    }
                    statement.addBatch();
                }
            }
            
            logger.debug("Executing batch update: {}, batch size: {}", sql, paramsList != null ? paramsList.size() : 0);
            int[] affectedRows = statement.executeBatch();
            
            logger.debug("Batch update executed successfully, affected rows: {}", affectedRows.length);
            return affectedRows;
            
        } catch (SQLException e) {
            logger.error("Failed to execute batch update: {}", sql, e);
            throw e;
        } finally {
            closeResources(null, statement, connection);
        }
    }
    
    /**
     * 执行SQL脚本文件
     * @param script SQL脚本内容
     * @throws SQLException 如果执行失败
     */
    public static void executeScript(String script) throws SQLException {
        Connection connection = null;
        Statement statement = null;
        
        try {
            connection = getConnection();
            statement = connection.createStatement();
            
            // 分割SQL脚本为单独的语句
            String[] sqlStatements = script.split(";");
            for (String sql : sqlStatements) {
                String trimmedSql = sql.trim();
                if (!trimmedSql.isEmpty()) {
                    logger.debug("Executing script statement: {}", trimmedSql);
                    statement.execute(trimmedSql);
                }
            }
            
            logger.debug("Script executed successfully");
            
        } catch (SQLException e) {
            logger.error("Failed to execute script", e);
            throw e;
        } finally {
            closeResources(null, statement, connection);
        }
    }
    
    /**
     * 调用存储过程
     * @param procedureName 存储过程名
     * @param params 参数
     * @return 结果集（如果有）
     * @throws SQLException 如果调用失败
     */
    public static List<Map<String, Object>> callProcedure(String procedureName, Object... params) throws SQLException {
        List<Map<String, Object>> resultList = new ArrayList<>();
        Connection connection = null;
        CallableStatement statement = null;
        ResultSet resultSet = null;
        
        try {
            connection = getConnection();
            StringBuilder sql = new StringBuilder("{call ").append(procedureName).append("(");
            
            if (params != null && params.length > 0) {
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sql.append(",");
                    }
                    sql.append("?");
                }
            }
            sql.append(")}");
            
            statement = connection.prepareCall(sql.toString());
            
            // 设置存储过程参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
            }
            
            logger.debug("Calling stored procedure: {}", procedureName);
            boolean hasResultSet = statement.execute();
            
            // 处理结果集
            while (hasResultSet || statement.getUpdateCount() != -1) {
                if (hasResultSet) {
                    resultSet = statement.getResultSet();
                    while (resultSet.next()) {
                        Map<String, Object> row = new HashMap<>();
                        int columnCount = resultSet.getMetaData().getColumnCount();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = resultSet.getMetaData().getColumnName(i);
                            row.put(columnName, resultSet.getObject(i));
                        }
                        resultList.add(row);
                    }
                }
                hasResultSet = statement.getMoreResults();
            }
            
            logger.debug("Stored procedure executed successfully, returned {} rows", resultList.size());
            return resultList;
            
        } catch (SQLException e) {
            logger.error("Failed to call stored procedure: {}", procedureName, e);
            throw e;
        } finally {
            closeResources(resultSet, statement, connection);
        }
    }
    
    /**
     * 导出数据到CSV文件
     * @param sql 查询SQL
     * @param filePath CSV文件路径
     * @param params 查询参数
     * @throws SQLException 如果导出失败
     */
    public static void exportToCSV(String sql, String filePath, Object... params) throws SQLException {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        
        try {
            connection = getConnection();
            statement = connection.prepareStatement(sql);
            
            // 设置查询参数
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
            }
            
            logger.debug("Exporting data to CSV: {}", filePath);
            resultSet = statement.executeQuery();
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                // 写入标题行
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        writer.write(",");
                    }
                    writer.write("\"" + metaData.getColumnName(i) + "\"");
                }
                writer.newLine();
                
                // 写入数据行
                while (resultSet.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        if (i > 1) {
                            writer.write(",");
                        }
                        String value = resultSet.getString(i);
                        writer.write("\"" + (value != null ? value.replace("\"", "\"\"") : "") + "\"");
                    }
                    writer.newLine();
                }
                
                logger.debug("Data exported to CSV successfully: {}", filePath);
            }
            
        } catch (IOException e) {
            logger.error("Failed to export data to CSV: {}", filePath, e);
            throw new SQLException("Failed to export data to CSV", e);
        } catch (SQLException e) {
            logger.error("Failed to execute export query: {}", sql, e);
            throw e;
        } finally {
            closeResources(resultSet, statement, connection);
        }
    }
    
    /**
     * 获取数据库元数据
     * @return 数据库元数据对象
     * @throws SQLException 如果获取失败
     */
    public static DatabaseMetaData getDatabaseMetaData() throws SQLException {
        Connection connection = getConnection();
        return connection.getMetaData();
    }
    
    /**
     * 验证表是否存在
     * @param tableName 表名
     * @return true 如果表存在，false 如果不存在
     * @throws SQLException 如果验证失败
     */
    public static boolean tableExists(String tableName) throws SQLException {
        DatabaseMetaData metaData = getDatabaseMetaData();
        try (ResultSet resultSet = metaData.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }
    
    /**
     * 获取表的所有列信息
     * @param tableName 表名
     * @return 列信息列表
     * @throws SQLException 如果获取失败
     */
    public static List<Map<String, Object>> getTableColumns(String tableName) throws SQLException {
        List<Map<String, Object>> columns = new ArrayList<>();
        DatabaseMetaData metaData = getDatabaseMetaData();
        
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, null)) {
            while (resultSet.next()) {
                Map<String, Object> column = new HashMap<>();
                column.put("TABLE_NAME", resultSet.getString("TABLE_NAME"));
                column.put("COLUMN_NAME", resultSet.getString("COLUMN_NAME"));
                column.put("DATA_TYPE", resultSet.getInt("DATA_TYPE"));
                column.put("TYPE_NAME", resultSet.getString("TYPE_NAME"));
                column.put("COLUMN_SIZE", resultSet.getInt("COLUMN_SIZE"));
                column.put("NULLABLE", resultSet.getInt("NULLABLE"));
                column.put("REMARKS", resultSet.getString("REMARKS"));
                columns.add(column);
            }
        }
        
        logger.debug("Retrieved {} columns for table: {}", columns.size(), tableName);
        return columns;
    }
    
    /**
     * 验证数据是否存在
     * @param tableName 表名
     * @param whereClause WHERE条件
     * @param params 查询参数
     * @return true 如果数据存在，false 如果不存在
     * @throws SQLException 如果验证失败
     */
    public static boolean dataExists(String tableName, String whereClause, Object... params) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM " + tableName;
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql += " WHERE " + whereClause;
        }
        
        List<Map<String, Object>> results = executeQuery(sql, params);
        if (!results.isEmpty() && results.get(0).containsKey("count")) {
            Long count = (Long) results.get(0).get("count");
            return count > 0;
        }
        return false;
    }
    
    /**
     * 验证数据是否符合预期
     * @param tableName 表名
     * @param whereClause WHERE条件
     * @param expectedCount 预期数据行数
     * @param params 查询参数
     * @return true 如果数据符合预期，false 如果不符合
     * @throws SQLException 如果验证失败
     */
    public static boolean validateData(String tableName, String whereClause, long expectedCount, Object... params) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM " + tableName;
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql += " WHERE " + whereClause;
        }
        
        List<Map<String, Object>> results = executeQuery(sql, params);
        if (!results.isEmpty() && results.get(0).containsKey("count")) {
            Long count = (Long) results.get(0).get("count");
            boolean isValid = count == expectedCount;
            logger.debug("Data validation - Expected: {}, Actual: {}, Result: {}", expectedCount, count, isValid);
            return isValid;
        }
        return false;
    }
    
    /**
     * 关闭数据库资源
     * @param resultSet 结果集
     * @param statement 语句
     * @param connection 连接
     */
    private static void closeResources(ResultSet resultSet, Statement statement, Connection connection) {
        try {
            if (resultSet != null) {
                resultSet.close();
            }
        } catch (SQLException e) {
            logger.warn("Failed to close ResultSet", e);
        }
        
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException e) {
            logger.warn("Failed to close Statement", e);
        }
        
        try {
            // 如果不是事务连接，则关闭连接
            if (connection != null && !isInTransaction.get()) {
                if (useConnectionPool) {
                    connection.close();
                    logger.debug("Connection returned to pool");
                } else if (!connection.isClosed()) {
                    connection.close();
                    logger.debug("Database connection closed");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to close Connection", e);
        }
    }
    
    /**
     * 验证数据库连接
     * @return true 如果连接成功，false 如果连接失败
     */
    public static boolean validateConnection() {
        try {
            Connection connection = getConnection();
            boolean isValid = connection != null && !connection.isClosed();
            
            if (isValid) {
                closeResources(null, null, connection);
            }
            
            return isValid;
        } catch (SQLException e) {
            logger.error("Database connection validation failed", e);
            return false;
        }
    }
    
    /**
     * 获取数据库连接状态
     * @return 连接状态信息
     */
    public static String getConnectionStatus() {
        try {
            Connection connection = getConnection();
            if (connection != null && !connection.isClosed()) {
                return String.format("Connected to %s database: %s, User: %s, Pool: %s", 
                    databaseType, maskPasswordInUrl(dbUrl), maskPassword(dbUser), useConnectionPool);
            } else {
                return "Database connection is not established";
            }
        } catch (SQLException e) {
            return String.format("Failed to get connection: %s", e.getMessage());
        } finally {
            try {
                if (dataSource != null) {
                    dataSource.getConnection().close();
                }
            } catch (SQLException e) {
                // 忽略关闭异常
            }
        }
    }
    
    /**
     * 获取数据库信息
     * @return 数据库信息映射
     * @throws SQLException 如果获取失败
     */
    public static Map<String, String> getDatabaseInfo() throws SQLException {
        Map<String, String> dbInfo = new HashMap<>();
        DatabaseMetaData metaData = getDatabaseMetaData();
        
        dbInfo.put("URL", metaData.getURL());
        dbInfo.put("User", metaData.getUserName());
        dbInfo.put("Database Product Name", metaData.getDatabaseProductName());
        dbInfo.put("Database Product Version", metaData.getDatabaseProductVersion());
        dbInfo.put("Driver Name", metaData.getDriverName());
        dbInfo.put("Driver Version", metaData.getDriverVersion());
        dbInfo.put("Database Major Version", String.valueOf(metaData.getDatabaseMajorVersion()));
        dbInfo.put("Database Minor Version", String.valueOf(metaData.getDatabaseMinorVersion()));
        
        return dbInfo;
    }
    
    /**
     * 设置连接池大小
     * @param maxPoolSize 最大连接数
     * @param minIdle 最小空闲连接数
     */
    public static void setPoolSize(int maxPoolSize, int minIdle) {
        DatabaseUtil.maxPoolSize = maxPoolSize;
        DatabaseUtil.minIdle = minIdle;
        
        if (dataSource != null) {
            dataSource.setMaximumPoolSize(maxPoolSize);
            dataSource.setMinimumIdle(minIdle);
            logger.info("Connection pool size updated: max={}, min={}", maxPoolSize, minIdle);
        }
    }
    
    /**
     * 设置连接超时时间
     * @param connectionTimeout 连接超时（毫秒）
     * @param idleTimeout 空闲超时（毫秒）
     * @param maxLifetime 最大生命周期（毫秒）
     */
    public static void setConnectionTimeout(int connectionTimeout, int idleTimeout, int maxLifetime) {
        DatabaseUtil.connectionTimeout = connectionTimeout;
        DatabaseUtil.idleTimeout = idleTimeout;
        DatabaseUtil.maxLifetime = maxLifetime;
        
        if (dataSource != null) {
            dataSource.setConnectionTimeout(connectionTimeout);
            dataSource.setIdleTimeout(idleTimeout);
            dataSource.setMaxLifetime(maxLifetime);
            logger.info("Connection timeout settings updated: conn={}, idle={}, lifetime={}", 
                connectionTimeout, idleTimeout, maxLifetime);
        }
    }
    
    /**
     * 关闭所有连接池连接
     */
    public static void shutdownConnectionPool() {
        if (dataSource != null) {
            dataSource.close();
            logger.info("Connection pool shutdown completed");
        }
    }
    
    /**
     * 掩码URL中的密码部分
     * @param url 数据库URL
     * @return 掩码后的URL
     */
    private static String maskPasswordInUrl(String url) {
        if (url == null) {
            return "null";
        }
        return url.replaceAll("password=[^&]*", "password=******");
    }
    
    /**
     * 掩码密码
     * @param password 密码
     * @return 掩码后的密码
     */
    private static String maskPassword(String password) {
        return password != null ? "******" : "null";
    }
}