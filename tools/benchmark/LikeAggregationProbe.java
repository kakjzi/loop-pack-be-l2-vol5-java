import java.nio.file.*;
import java.sql.*;
import java.util.*;

// One-off read-only SQL comparison. Not an HTTP or throughput benchmark.
public class LikeAggregationProbe {
    static final String[] METHODS = {"findLowestPriceByBrandId", "findLowestPrice",
            "findLatestByBrandId", "findLatest", "findMostLikedByBrandId", "findMostLiked"};
    static final String[] PHASES = {"before", "after"};
    static Path output;
    static Path[] inputs;

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

    static String query(int phase, String name) throws Exception {
        String sql = Files.readString(inputs[phase].resolve(name + ".sql")).strip();
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
            write(phase + "-price-handler-" + sample + ".tsv", raw);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: before-sql-dir after-sql-dir new-output-dir");
        }
        inputs = new Path[] {Path.of(args[0]), Path.of(args[1])};
        output = Path.of(args[2]);
        Files.createDirectory(output);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:13306/loopers_offset_bench", "application", "application")) {
            connection.setReadOnly(true);
            String environment = "SELECT DATABASE(),@@version,@@sql_mode,@@transaction_read_only,"
                    + "(SELECT count(*) FROM products),(SELECT count(*) FROM product_likes)";
            List<String> beforeEnvironment = rows(connection, environment);
            write("environment-before.tsv", beforeEnvironment);
            List<String> ddl = new ArrayList<>(rows(connection, "SHOW CREATE TABLE products"));
            ddl.addAll(rows(connection, "SHOW CREATE TABLE product_likes"));
            write("ddl-before.tsv", ddl);
            List<String> times = new ArrayList<>(List.of("method,phase,sample,elapsed_ms"));
            List<String> handlers = new ArrayList<>(List.of("phase,sample,counter,delta"));
            for (String name : METHODS) {
                String[] sql = {query(0, name), query(1, name)};
                List<String> expected = rows(connection, sql[0]);
                for (int phase = 0; phase < 2; phase++) {
                    write(PHASES[phase] + "-" + name + ".sql", List.of(sql[phase] + ";"));
                    List<String> actual = rows(connection, sql[phase]);
                    if (!actual.equals(expected)) throw new AssertionError(name + " result differs");
                    write(PHASES[phase] + "-" + name + "-result.tsv", actual);
                }
                boolean fixedPrice = name.equals("findLowestPriceByBrandId");
                int warmups = fixedPrice ? 5 : 1;
                int samples = fixedPrice ? 30 : 3;
                for (int sample = -warmups; sample < samples; sample++) {
                    // Alternate the leading phase to reduce a fixed-order advantage.
                    for (int turn = 0; turn < 2; turn++) {
                        int phase = Math.floorMod(sample + turn, 2);
                        long start = System.nanoTime();
                        List<String> actual = rows(connection, sql[phase]);
                        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                        if (!actual.equals(expected)) throw new AssertionError(name + " result differs");
                        if (sample >= 0) times.add(name + "," + PHASES[phase] + "," + (sample + 1) + "," + elapsed);
                    }
                }
                for (int phase = 0; phase < 2; phase++) {
                    write(PHASES[phase] + "-" + name + "-explain.tsv", rows(connection, "EXPLAIN " + sql[phase]));
                    write(PHASES[phase] + "-" + name + "-analyze.txt", rows(connection, "EXPLAIN ANALYZE " + sql[phase]));
                    if (fixedPrice) handler(connection, PHASES[phase], sql[phase], expected, handlers);
                }
                write("latencies.csv", times);
                write("handler-deltas.csv", handlers);
                System.out.println("Verified and measured " + name);
            }
            List<String> afterEnvironment = rows(connection, environment);
            write("environment-after.tsv", afterEnvironment);
            List<String> afterDdl = new ArrayList<>(rows(connection, "SHOW CREATE TABLE products"));
            afterDdl.addAll(rows(connection, "SHOW CREATE TABLE product_likes"));
            write("ddl-after.tsv", afterDdl);
            if (!beforeEnvironment.equals(afterEnvironment) || !ddl.equals(afterDdl)) {
                throw new AssertionError("Dataset counts/schema changed");
            }
            write("completed.txt", List.of("All six list queries returned the same ordered six-column rows.",
                    "Read-only JDBC connection; no HTTP, Page total COUNT, Redis or throughput measurement.",
                    "Data counts and DDL unchanged; this is not a full dataset checksum."));
        }
    }
}
