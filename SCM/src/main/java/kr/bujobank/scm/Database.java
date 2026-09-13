package kr.bujobank.scm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class Database {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(Database.class.getName());
    private final java.util.concurrent.atomic.AtomicBoolean credentialsLogged = new java.util.concurrent.atomic.AtomicBoolean();
    private final Properties config = new Properties();
    public Database() {
        config.putAll(loadProfile());
        String path = System.getenv("SCM_CONFIG");
        if (path != null && !path.trim().isEmpty()) {
            try (Reader in = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) { config.load(in); }
            catch (IOException e) { throw new IllegalStateException("SCM_CONFIG 파일을 읽을 수 없습니다.", e); }
        }
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }
    static Properties loadProfile() {
        Properties build = new Properties();
        loadResource(build, "/scm-build.properties");
        String profile = build.getProperty("scm.profile", "").trim();
        if (!"dev".equals(profile) && !"prod".equals(profile)) {
            throw new IllegalStateException("Maven 프로필 -Pdev 또는 -Pprod로 빌드해야 합니다.");
        }
        Properties properties = new Properties();
        loadResource(properties, "/application.properties");
        loadResource(properties, "/application-" + profile + ".properties");
        return properties;
    }
    private static void loadResource(Properties properties, String path) {
        try (InputStream stream = Database.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException(path + " 파일이 없습니다.");
            try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) { properties.load(in); }
        } catch (IOException e) { throw new IllegalStateException(path + " 파일을 읽을 수 없습니다.", e); }
    }
    public String setting(String key, String fallback) { String env = System.getenv(key); return env == null ? config.getProperty(key, fallback) : env; }
    public Connection open() throws SQLException {
        String url = setting("SCM_DB_URL", "");
        String user = setting("SCM_DB_USER", "");
        String password = setting("SCM_DB_PASSWORD", "");
        if (Boolean.parseBoolean(setting("SCM_DB_LOG_CREDENTIALS", "false")) && credentialsLogged.compareAndSet(false, true)) {
            LOG.warning("SCM DB temporary diagnostic: url=[" + logValue(url) + "] user=[" + logValue(user)
                + "] password=[" + logValue(password) + "] passwordLength=" + password.length()
                + ". Disable SCM_DB_LOG_CREDENTIALS after diagnosis.");
        }
        if (!url.startsWith("jdbc:mariadb:")) throw new SQLException("SCM_DB_URL MariaDB 연결 설정이 필요합니다.");
        Connection c = DriverManager.getConnection(url, user, password);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (Statement s = c.createStatement()) { s.execute("SET time_zone = '+09:00'"); }
        return c;
    }
    private static String logValue(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
    public void initialize() throws SQLException, IOException {
        if (!Boolean.parseBoolean(setting("SCM_DB_INIT", "false"))) return;
        try (Connection c = open(); InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("schema.sql missing");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            for (String sql : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split(";")) if (!sql.trim().isEmpty()) try (Statement s = c.createStatement()) { s.execute(sql); }
            if (number(one(c,"SELECT COUNT(*) n FROM users"), "n") == 0) {
                String password = setting("SCM_BOOTSTRAP_PASSWORD", "");
                Passwords.validate(password);
                execute(c, "INSERT INTO users(username,password_hash,name,role) VALUES(?,?,?,'SUPER')", setting("SCM_BOOTSTRAP_USERNAME", "admin"), Passwords.hash(password), "최고 관리자");
            }
        }
    }
    public interface Work<T> { T run(Connection c) throws Exception; }
    public <T> T transaction(Work<T> work) throws Exception {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try { T value = work.run(c); c.commit(); return value; }
            catch (Exception e) { c.rollback(); throw e; }
        }
    }
    public static List<Map<String,Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, args); ResultSet rs = s.executeQuery()) {
            List<Map<String,Object>> rows = new ArrayList<>(); ResultSetMetaData meta = rs.getMetaData();
            while(rs.next()) { Map<String,Object> row = new LinkedHashMap<>(); for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),rs.getObject(i)); rows.add(row); }
            return rows;
        }
    }
    public static Map<String,Object> one(Connection c, String sql, Object... args) throws SQLException { List<Map<String,Object>> found=rows(c,sql,args); return found.isEmpty()?null:found.get(0); }
    public static long insert(Connection c,String sql,Object... args) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) { bind(s,args);s.executeUpdate();try(ResultSet keys=s.getGeneratedKeys()){if(keys.next())return keys.getLong(1);throw new SQLException("생성된 키가 없습니다.");} }
    }
    public static int execute(Connection c,String sql,Object... args) throws SQLException {try(PreparedStatement s=prepare(c,sql,args)){return s.executeUpdate();}}
    private static PreparedStatement prepare(Connection c,String sql,Object... args) throws SQLException {PreparedStatement s=c.prepareStatement(sql);bind(s,args);return s;}
    private static void bind(PreparedStatement s,Object... args) throws SQLException {for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    public static long number(Map<String,Object> row,String key) {Object n=row==null?null:row.get(key);return n==null?0:((Number)n).longValue();}
    public static boolean bool(Map<String,Object> row,String key) {Object n=row.get(key);return Boolean.TRUE.equals(n)||(n instanceof Number&&((Number)n).intValue()!=0);}
}
