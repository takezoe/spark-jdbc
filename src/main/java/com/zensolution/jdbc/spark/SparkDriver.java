package com.zensolution.jdbc.spark;

import com.zensolution.jdbc.spark.internal.Versions;
import com.zensolution.jdbc.spark.internal.config.Config;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SparkDriver implements Driver {

    private static final Logger LOGGER = Logger.getLogger("com.zensolution.jdbc.spark");

    public final static String URL_PREFIX = "jdbc:spark:";

    static {
        try {
            register();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Unable to register com.zensolution.jdbc.spark JDBC driver", e);
        }
    }

    public static synchronized void register() throws SQLException {
        DriverManager.registerDriver(new SparkDriver());
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (info == null) {
            info = new Properties();
        }
        if (!acceptsURL(url)) {
            return null;
        }

        // get filepath from url
        String urlProperties = url.substring(URL_PREFIX.length());
        int questionIndex = urlProperties.indexOf('?');
        String master = questionIndex >= 0 ? urlProperties.substring(0, questionIndex) : urlProperties;
        if (master.startsWith("//")) {
            master = "spark:" + master;
        }
        if (questionIndex >= 0) {
            Properties prop = parseUrlIno(urlProperties.substring(questionIndex+1));
            info.putAll(prop);
        }

        // Load configuration file
        String path = info.getProperty("config");
        if (path == null || path.isEmpty()) {
            throw new SQLException("config must be specified");
        }
        Config config;
        try {
            config = Config.load(path);
        } catch (IOException e) {
            throw new SQLException(e);
        }

        LOGGER.log(Level.INFO, "SparkDriver:connect() - master: " + master + ", config: " + config);
        return new SparkConnection(master, config);
    }

    private Properties parseUrlIno(String urlProperties) throws SQLException {
        Properties info = new Properties();
        String[] split = urlProperties.split("&");
        for (int i = 0; i < split.length; i++) {
            String[] property = split[i].split("=");
            try {
                if (property.length == 2) {
                    String key = URLDecoder.decode(property[0], "UTF-8");
                    String value = URLDecoder.decode(property[1], "UTF-8");
                    info.setProperty(key, value);
                } else {
                    throw new SQLException("invalid Property: " + split[i]);
                }
            } catch (UnsupportedEncodingException e) {
                // we know UTF-8 is available
            }
        }
        return info;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        LOGGER.log(Level.FINE, "SparkDriver:accept() - url=" + url);
        return url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return Versions.Major;
    }

    @Override
    public int getMinorVersion() {
        return Versions.Minor;
    }

    @Override
    public boolean jdbcCompliant() {
        // This has to be false since we are not fully SQL-92 compliant
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getLogger(getClass().getPackage().getName());
    }

}
