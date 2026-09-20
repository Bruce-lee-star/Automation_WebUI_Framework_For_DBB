import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 删除 Checkstyle UnusedImports 标记的未用 import。
 * 读取 checkstyle-result.xml，按每条 UnusedImportsCheck 报错里的 FQN 删除对应 import 行。
 * 用法: java UnusedImportRemover <checkstyle-result.xml> [--dry]
 */
public class UnusedImportRemover {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) { System.err.println("usage: UnusedImportRemover <checkstyle-result.xml> [--dry]"); System.exit(2); }
        Path xml = Paths.get(args[0]).toAbsolutePath();
        boolean dry = args.length > 1 && args[1].equals("--dry");
        String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        // 解析 <file name="..."> ... <error ... source="...UnusedImportsCheck" message="... - FQN 。" .../>
        Map<String, List<String>> byFile = new LinkedHashMap<>();
        String[] lines = content.split("\n", -1);
        String curFile = null;
        for (String ln : lines) {
            int fi = ln.indexOf("<file name=\"");
            if (fi >= 0) {
                int s = fi + "<file name=\"".length();
                int e = ln.indexOf('\"', s);
                curFile = ln.substring(s, e);
                byFile.computeIfAbsent(curFile, k -> new ArrayList<>());
            }
            if (ln.contains("UnusedImportsCheck")) {
                int mi = ln.indexOf("message=\"");
                if (mi >= 0) {
                    int s = mi + "message=\"".length();
                    int e = ln.indexOf('\"', s);
                    if (e >= 0) {
                        String msg = ln.substring(s, e);
                        String fqn = extractFqn(msg);
                        if (fqn != null && curFile != null) {
                            byFile.get(curFile).add(fqn);
                        }
                    }
                }
            }
        }
        int total = 0;
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, List<String>> en : byFile.entrySet()) {
            Path p = Paths.get(en.getKey());
            if (!Files.exists(p)) continue;
            List<String> fqns = en.getValue();
            List<Pattern> pats = new ArrayList<>();
            for (String f : fqns) {
                pats.add(Pattern.compile("^\\s*import\\s+(static\\s+)?" + Pattern.quote(f) + "\\s*;.*$"));
            }
            List<String> srcLines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<String> outLines = new ArrayList<>();
            int removed = 0;
            for (String sl : srcLines) {
                boolean drop = false;
                for (Pattern pat : pats) {
                    if (pat.matcher(sl).matches()) { drop = true; break; }
                }
                if (drop) removed++; else outLines.add(sl);
            }
            if (removed > 0) {
                total += removed;
                report.append("REMOVED ").append(removed).append(" in ").append(p.getFileName()).append(" : ").append(fqns).append('\n');
                if (!dry) {
                    Files.write(p, String.join("\n", outLines).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        System.out.println(report);
        System.out.println("removed=" + total + (dry ? " (DRY)" : ""));
    }

    static String extractFqn(String msg) {
        int idx = msg.lastIndexOf(" - ");
        if (idx < 0) idx = msg.indexOf(" - ");
        if (idx < 0) return null;
        String fqn = msg.substring(idx + 3).trim();
        if (fqn.endsWith("。")) fqn = fqn.substring(0, fqn.length() - 1).trim();
        while (fqn.endsWith(".")) fqn = fqn.substring(0, fqn.length() - 1).trim();
        return fqn.isEmpty() ? null : fqn;
    }
}
