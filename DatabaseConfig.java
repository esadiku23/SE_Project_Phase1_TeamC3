package al.albus.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConfig {
    private static final String URL      = "jdbc:mysql://localhost:3306/busdb";
    private static final String USER     = "root";       // your MySQL user
    private static final String PASSWORD = "";  // common default
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}