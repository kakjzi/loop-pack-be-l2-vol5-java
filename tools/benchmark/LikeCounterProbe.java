import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Fixed local DB, read-only, alternating SQL comparison. Not an HTTP/RPS benchmark. */
public class LikeCounterProbe {
    static final String[] METHODS = {"findLowestPriceByBrandId", "findLowestPrice", "findLatestByBrandId",
            "findLatest", "findMostLikedByBrandId", "findMostLiked"};
    static final String[] PHASES = {"count", "counter"};
    static Path output;

    static List<String> rows(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(60);
            try (ResultSet rs = s.executeQuery(sql)) {
                List<String> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> fields = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        fields.add(Objects.toString(rs.getString(i), "NULL"));
                    }
                    result.add(String.join("\t", fields));
                }
                return result;
            }
        }
    }

    static void write(String name, List<String> data) throws Exception {
        Files.write(output.resolve(name), data);
    }

    static String query(Path path, long offset) throws Exception {
        String sql = Files.readString(path).strip().replaceFirst(";\\s*$", "").strip();
        String lower = sql.toLowerCase(Locale.ROOT);
        if ((!lower.startsWith("select ") && !lower.startsWith("with ")) || sql.contains(";")) {
            throw new IllegalArgumentException("Expected single SELECT: " + path);
        }
        return sql.replaceAll("(?i)offset\\s+0\\b", "offset " + offset);
    }

    static Map<String, Long> counters(List<String> data) {
        Map<String, Long> result = new TreeMap<>();
        for (String row : data) {
            String[] parts = row.split("\t");
            result.put(parts[0], Long.parseLong(parts[1]));
        }
        return result;
    }

    static void handler(Connection c, String name, String phase, String sql,
                        List<String> expected, List<String> csv) throws Exception {
        String id = rows(c, "select connection_id()").getFirst();
        List<String> before = rows(c, "show session status like 'Handler_read%'");
        List<String> actual = rows(c, sql);
        List<String> after = rows(c, "show session status like 'Handler_read%'");
        if (!actual.equals(expected) || !id.equals(rows(c, "select connection_id()").getFirst())) {
            throw new AssertionError("Handler result/connection differs: " + name);
        }
        Map<String, Long> old = counters(before);
        for (var entry : counters(after).entrySet()) {
            csv.add(name + "," + phase + "," + entry.getKey() + "," + (entry.getValue() - old.get(entry.getKey())));
        }
        List<String> raw = new ArrayList<>(List.of("connection_id\t" + id, "__BEFORE__"));
        raw.addAll(before); raw.add("__RESULT__"); raw.addAll(actual); raw.add("__AFTER__"); raw.addAll(after);
        write(name + "-" + phase + "-handler.tsv", raw);
    }

    public static void main(String[] args) throws Exception {
        boolean priceControl = args.length == 3 && args[2].equals("--price-control");
        if (args.length != 2 && !priceControl) {
            throw new IllegalArgumentException("Usage: input-root new-output-dir [--price-control]");
        }
        Path input = Path.of(args[0]);
        output = Path.of(args[1]);
        Files.createDirectory(output);
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:13306/loopers_offset_bench", "application", "application")) {
            c.setReadOnly(true);
            String env = "select database(),@@version,@@transaction_read_only,@@sql_mode,"
                    + "(select count(*) from products),(select count(*) from product_likes),"
                    + "(select count(*) from users),(select count(*) from product_like_counts),"
                    + "(select sum(like_count) from product_like_counts)";
            List<String> before = rows(c, env);
            write("environment-before.tsv", before);
            String[] parts = before.getFirst().split("\t");
            if (!parts[0].equals("loopers_offset_bench") || !parts[4].equals("1000000")
                    || !parts[5].equals("5000000") || !parts[8].equals("5000000")
                    || !parts[2].equals("1") || !parts[6].equals("150004") || !parts[7].equals("1000000")) {
                throw new AssertionError("Expected approved 1m products/5m likes/counter sum");
            }
            List<String> ddl = new ArrayList<>();
            for (String table : List.of("products", "product_likes", "product_like_counts")) {
                ddl.addAll(rows(c, "show create table " + table));
            }
            write("ddl-before.tsv", ddl);
            List<String> times = new ArrayList<>(List.of("scenario,phase,sample,elapsed_ms"));
            List<String> handlers = new ArrayList<>(List.of("scenario,phase,counter,delta"));
            for (int i = 0; i < 3; i++) handler(c, "control-" + i, "select1", "select 1", List.of("1"), handlers);
            int scenarios = 0;
            for (String method : METHODS) {
                if (priceControl && !method.startsWith("findLowestPrice")) continue;
                for (boolean middle : new boolean[]{false, true}) {
                    long offset = middle ? (method.endsWith("ByBrandId") ? 250_000 : 500_000) : 0;
                    String scenario = method + "-offset-" + offset;
                    scenarios++;
                    String[] sql = {query(input.resolve("count/" + method + ".sql"), offset),
                            query(input.resolve("counter/" + method + ".sql"), offset)};
                    List<String> expected = rows(c, sql[0]);
                    if (expected.size() != 20 || expected.stream().anyMatch(row -> row.split("\\t", -1).length != 6)) {
                        throw new AssertionError("Expected full 20-row/6-column page: " + scenario);
                    }
                    for (int phase = 0; phase < 2; phase++) {
                        if (!rows(c, sql[phase]).equals(expected)) throw new AssertionError("Result differs: " + scenario);
                        write(scenario + "-" + PHASES[phase] + ".sql", List.of(sql[phase] + ";"));
                        write(scenario + "-" + PHASES[phase] + "-result.tsv", expected);
                    }
                    for (int sample = -2; sample < 7; sample++) {
                        for (int turn = 0; turn < 2; turn++) {
                            int phase = Math.floorMod(sample + turn, 2);
                            long start = System.nanoTime();
                            List<String> actual = rows(c, sql[phase]);
                            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                            if (!actual.equals(expected)) throw new AssertionError("Timed result differs: " + scenario);
                            if (sample >= 0) times.add(scenario + "," + PHASES[phase] + "," + (sample + 1) + "," + elapsed);
                        }
                    }
                    for (int phase = 0; phase < 2; phase++) {
                        write(scenario + "-" + PHASES[phase] + "-explain.tsv", rows(c, "explain " + sql[phase]));
                        write(scenario + "-" + PHASES[phase] + "-analyze.txt", rows(c, "explain analyze " + sql[phase]));
                        handler(c, scenario, PHASES[phase], sql[phase], expected, handlers);
                    }
                    write("latencies.csv", times); write("handler-deltas.csv", handlers);
                    System.out.println("Verified and measured " + scenario);
                }
            }
            List<String> after = rows(c, env);
            write("environment-after.tsv", after);
            List<String> finalDdl = new ArrayList<>();
            for (String table : List.of("products", "product_likes", "product_like_counts")) {
                finalDdl.addAll(rows(c, "show create table " + table));
            }
            write("ddl-after.tsv", finalDdl);
            if (!before.equals(after) || !ddl.equals(finalDdl)) throw new AssertionError("Data counts/schema changed");
            write("completed.txt", List.of(scenarios + " scenarios, 2 phases; 7 timed samples after 2 warmups per SQL.",
                    "All ordered 20-row/6-column results match; counts/sum/DDL unchanged.",
                    "Read-only sequential JDBC, no HTTP/total COUNT/Redis/RPS or write performance measurement."));
        }
    }
}
