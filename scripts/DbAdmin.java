import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 受控本地运维入口；连接口令只从文件读取，不接受命令行口令。 */
public class DbAdmin {
  static Connection connect(String file) throws Exception {
    Properties p = new Properties();
    try (var in = Files.newInputStream(Path.of(file))) { p.load(in); }
    return DriverManager.getConnection(p.getProperty("spring.datasource.url"),
        p.getProperty("spring.datasource.username"), p.getProperty("spring.datasource.password"));
  }

  static Map<String, String> schema(Connection c) throws Exception {
    var result = new TreeMap<String, String>();
    var names = new ArrayList<String>();
    try (var s = c.createStatement(); var r = s.executeQuery("SHOW TABLES")) {
      while (r.next()) if (!r.getString(1).equals("flyway_schema_history")) names.add(r.getString(1));
    }
    for (String name : names) {
      if (!name.matches("[a-z_]+")) throw new IllegalStateException("Unexpected table name");
      try (var s = c.createStatement(); var r = s.executeQuery("SHOW CREATE TABLE `" + name + "`")) {
        r.next(); result.put(name, r.getString(2).replaceAll(" AUTO_INCREMENT=\\d+", ""));
      }
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException("Usage: DbAdmin <config-file> inspect|compare|bind-chef [arguments]");
    try (var c = connect(args[0])) {
      switch (args[1]) {
        case "inspect" -> {
          for (var name : schema(c).keySet()) {
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM `" + name + "`")) {
              r.next(); System.out.println(name + " rows=" + r.getLong(1));
            }
          }
        }
        case "compare" -> {
          try (var reference = connect(args[2])) {
            var actual = schema(c); var expected = schema(reference);
            if (!actual.equals(expected)) {
              var names = new TreeSet<>(actual.keySet()); names.addAll(expected.keySet());
              for (var name : names) if (!Objects.equals(actual.get(name), expected.get(name))) System.out.println("DIFF " + name);
              throw new IllegalStateException("Schema differs; do not baseline automatically");
            }
            System.out.println("MATCH " + actual.size() + " tables: columns, indexes, constraints and comments");
          }
        }
        case "bind-chef" -> {
          long restaurant = Long.parseLong(args[2]), user = Long.parseLong(args[3]); c.setAutoCommit(false);
          try {
            try (var s = c.prepareStatement("SELECT id FROM app_user WHERE id=? AND status='ACTIVE' FOR UPDATE")) {
              s.setLong(1, user); try (var r = s.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Active user not found"); }
            }
            try (var s = c.prepareStatement("UPDATE restaurant SET chef_user_id=?,version=version+1 WHERE id=? AND chef_user_id IS NULL AND status='ACTIVE'")) {
              s.setLong(1, user); s.setLong(2, restaurant);
              if (s.executeUpdate() != 1) throw new IllegalStateException("Restaurant missing or already bound; no change made");
            }
            try (var s = c.prepareStatement("INSERT INTO biz_operation_log(business_type,business_id,operation_type,summary_json) VALUES('CORE_RESTAURANT',?,'BIND_CHEF',JSON_OBJECT('chefUserId',?))")) {
              s.setLong(1, restaurant); s.setLong(2, user); s.executeUpdate();
            }
            c.commit(); System.out.println("Chef bound to restaurant " + restaurant);
          } catch (Exception e) { c.rollback(); throw e; }
        }
        default -> throw new IllegalArgumentException("Unknown command");
      }
    }
  }
}
