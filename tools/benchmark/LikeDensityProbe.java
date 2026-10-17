import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Read-only SQL samples before/after adding popular-product likes. Not HTTP or concurrent load.
public class LikeDensityProbe {
    static final String[] METHODS = {"single-popular-count", "brand-price", "brand-likes", "all-likes"};
    static Path output;
    static Path input;

    static List<String> rows(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            try (ResultSet result = statement.executeQuery(sql)) {
                List<String> rows = new ArrayList<>();
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        values.add(Objects.toString(result.getString(i), "NULL"));
                    }
                    rows.add(String.join("\t", values));
                }
                return rows;
            }
        }
    }

    static String query(String name) throws Exception {
        String sql = Files.readString(input.resolve(name + ".sql")).strip();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
        String lower = sql.toLowerCase(Locale.ROOT);
        if ((!lower.startsWith("select ") && !lower.startsWith("with ")) || sql.contains(";")) {
            throw new IllegalArgumentException("Expected one SELECT/CTE: " + name);
        }
        return sql;
    }

    static void write(String name, List<String> lines) throws Exception {
        Files.write(output.resolve(name), lines);
    }

    static Map<String, Long> counters(List<String> rows) {
        Map<String, Long> result = new TreeMap<>();
        for (String row : rows) {
            String[] fields = row.split("\t");
            result.put(fields[0], Long.parseLong(fields[1]));
        }
        return result;
    }

    static void handler(Connection connection, String phase, String sql, List<String> expected,
                        List<String> csv) throws Exception {
        for (int sample = 1; sample <= 3; sample++) {
            String idBefore = rows(connection, "SELECT CONNECTION_ID()").getFirst();
            List<String> before = rows(connection, "SHOW SESSION STATUS LIKE 'Handler_read%'");
            List<String> actual = rows(connection, sql);
            List<String> after = rows(connection, "SHOW SESSION STATUS LIKE 'Handler_read%'");
            String idAfter = rows(connection, "SELECT CONNECTION_ID()").getFirst();
            if (!actual.equals(expected) || !idBefore.equals(idAfter)) {
                throw new AssertionError("Handler result/connection differs");
            }
            Map<String, Long> old = counters(before);
            Map<String, Long> current = counters(after);
            for (String key : old.keySet()) {
                csv.add(phase + "," + sample + "," + key + "," + (current.get(key) - old.get(key)));
            }
            List<String> raw = new ArrayList<>(List.of("connection_before\t" + idBefore, "__BEFORE__"));
            raw.addAll(before);
            raw.add("__RESULT__"); raw.addAll(actual);
            raw.add("__AFTER__"); raw.addAll(after);
            raw.add("connection_after\t" + idAfter);
            write(phase + "-handler-" + sample + ".tsv", raw);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: sql-dir new-output-dir");
        }
        input = Path.of(args[0]);
        output = Path.of(args[1]);
        Files.createDirectory(output);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:13306/loopers_offset_bench", "application", "application")) {
            connection.setReadOnly(true);
            String environment = "SELECT DATABASE(),@@version,@@transaction_read_only,"
                    + "(SELECT count(*) FROM products),(SELECT count(*) FROM product_likes),"
                    + "(SELECT count(*) FROM users)";
            List<String> before = rows(connection, environment);
            write("environment-before.tsv", before);
            List<String> ddl = new ArrayList<>(rows(connection, "SHOW CREATE TABLE products"));
            ddl.addAll(rows(connection, "SHOW CREATE TABLE product_likes"));
            write("ddl-before.tsv", ddl);
            List<String> times = new ArrayList<>(List.of("query,sample,elapsed_ms"));
            List<String> handlers = new ArrayList<>(List.of("query,sample,counter,delta"));
            handler(connection, "observer-control", "SELECT 1", List.of("1"), handlers);
            for (String name : METHODS) {
                String sql = query(name);
                List<String> expected = rows(connection, sql);
                write(name + ".sql", List.of(sql + ";"));
                write(name + "-result.tsv", expected);
                boolean small = name.equals("single-popular-count") || name.equals("brand-price");
                int warmups = small ? 5 : 2;
                int samples = small ? 30 : 5;
                for (int sample = -warmups; sample < samples; sample++) {
                    long start = System.nanoTime();
                    List<String> actual = rows(connection, sql);
                    double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                    if (!actual.equals(expected)) throw new AssertionError(name + " result changed within phase");
                    if (sample >= 0) times.add(name + "," + (sample + 1) + "," + elapsed);
                }
                write(name + "-explain.tsv", rows(connection, "EXPLAIN " + sql));
                write(name + "-analyze.txt", rows(connection, "EXPLAIN ANALYZE " + sql));
                handler(connection, name, sql, expected, handlers);
                write("latencies.csv", times);
                write("handler-deltas.csv", handlers);
                System.out.println("Measured " + name);
            }
            List<String> after = rows(connection, environment);
            write("environment-after.tsv", after);
            List<String> afterDdl = new ArrayList<>(rows(connection, "SHOW CREATE TABLE products"));
            afterDdl.addAll(rows(connection, "SHOW CREATE TABLE product_likes"));
            write("ddl-after.tsv", afterDdl);
            if (!before.equals(after) || !ddl.equals(afterDdl)) {
                throw new AssertionError("Dataset counts or DDL changed during the read-only phase");
            }
            write("completed.txt", List.of("Read-only sequential JDBC, not HTTP or concurrent load.",
                    "Query timeout=60s; counts and DDL unchanged during this phase.",
                    "Each timed result equals its phase baseline; before/after data results may differ."));
        }
    }
}
