import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 保守加括号工具：为 if/for/while/do/else 的非块单语句体补充 {}，使 Checkstyle NeedBraces 通过。
 * 行为保持，仅插入花括号。跳过字符串/字符/注释。关键字严格词边界匹配。
 * 用法: java BraceAdder <dir> [--dry]
 */
public class BraceAdder {
    private static final Set<String> COND = new HashSet<>(Arrays.asList("if", "for", "while"));
    private static final Set<String> BODY_KW = new HashSet<>(Arrays.asList("do", "else"));

    public static void main(String[] args) throws IOException {
        if (args.length < 1) { System.err.println("usage: BraceAdder <dir> [--dry]"); System.exit(2); }
        Path root = Paths.get(args[0]).toAbsolutePath();
        boolean dry = args.length > 1 && args[1].equals("--dry");
        int files = 0, changed = 0;
        StringBuilder report = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))::iterator) {
                files++;
                String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                String out = process(src);
                if (!out.equals(src)) {
                    changed++;
                    report.append("CHANGED: ").append(root.relativize(p)).append('\n');
                    if (!dry) {
                        Files.write(p, out.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        System.out.println(report);
        System.out.println("scanned=" + files + " changed=" + changed + (dry ? " (DRY)" : ""));
    }

    static String process(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int j = s.indexOf('\n', i); if (j < 0) j = n;
                out.append(s, i, j); i = j; continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2); if (j < 0) j = n - 2; else j += 2;
                out.append(s, i, j); i = j; continue;
            }
            if (c == '"' || c == '\'') {
                int j = skipLiteral(s, i); out.append(s, i, j); i = j; continue;
            }
            if (isIdentStart(c)) {
                int j = i + 1; while (j < n && isIdentPart(s.charAt(j))) j++;
                String w = s.substring(i, j);
                boolean prevOk = (i == 0) || !isIdentPart(s.charAt(i - 1));
                boolean nextOk = (j >= n) || !isIdentPart(s.charAt(j));
                if (prevOk && nextOk && (COND.contains(w) || BODY_KW.contains(w))) {
                    if (w.equals("do")) {
                        i = handleDo(s, out, i, j);
                        continue;
                    }
                    out.append(s, i, j); i = j;
                    if (COND.contains(w)) {
                        while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
                        if (i < n && s.charAt(i) == '(') {
                            int close = matchParen(s, i);
                            out.append(s, i, close + 1); i = close + 1;
                        }
                    }
                    i = handleBody(s, out, i);
                    continue;
                }
                out.append(s, i, j); i = j; continue;
            }
            out.append(c); i++;
        }
        return out.toString();
    }

    static int handleBody(String s, StringBuilder out, int i) {
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
        if (i >= n) return i;
        if (s.charAt(i) == '{') {
            return i;
        }
        int bodyEnd = statementEnd(s, i);
        out.append(" {");
        out.append(process(s.substring(i, bodyEnd)));
        out.append("} ");
        return bodyEnd;
    }

    static int handleDo(String s, StringBuilder out, int i, int j) {
        int n = s.length();
        out.append(s, i, j); i = j;
        while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
        if (i >= n) return i;
        int bodyStart = i;
        int bodyEnd = statementEnd(s, i);
        if (s.charAt(bodyStart) == '{') {
            out.append(process(s.substring(bodyStart, bodyEnd)));
        } else {
            out.append(" {");
            out.append(process(s.substring(bodyStart, bodyEnd)));
            out.append("} ");
        }
        i = bodyEnd;
        while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
        int m = i;
        if (m + 5 <= n && s.startsWith("while", m)
                && (m == 0 || !isIdentPart(s.charAt(m - 1)))
                && (m + 5 >= n || !isIdentPart(s.charAt(m + 5)))) {
            int we = m + 5;
            out.append(s, m, we); i = we;
            while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
            if (i < n && s.charAt(i) == '(') {
                int close = matchParen(s, i);
                out.append(s, i, close + 1); i = close + 1;
            }
            while (i < n && Character.isWhitespace(s.charAt(i))) { out.append(s.charAt(i)); i++; }
            if (i < n && s.charAt(i) == ';') { out.append(';'); i++; }
        }
        return i;
    }

    static int statementEnd(String s, int pos) {
        int n = s.length();
        int i = pos;
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= n) return n;
        char c = s.charAt(i);
        if (c == '{') {
            return matchBrace(s, i) + 1;
        }
        if (isIdentStart(c)) {
            int j = i + 1; while (j < n && isIdentPart(s.charAt(j))) j++;
            String w = s.substring(i, j);
            boolean prevOk = (i == 0) || !isIdentPart(s.charAt(i - 1));
            boolean nextOk = (j >= n) || !isIdentPart(s.charAt(j));
            if (prevOk && nextOk) {
            if (w.equals("if") || w.equals("for") || w.equals("while")) {
                int k = j;
                while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                if (k < n && s.charAt(k) == '(') {
                    int close = matchParen(s, k);
                    k = close + 1;
                }
                int thenEnd = statementEnd(s, k);
                if (w.equals("if")) {
                    // consume exactly one directly-attached trailing 'else' so an
                    // if-else/else-if unit is treated as a single statement body
                    // (nested else handled by recursion); this keeps else chains intact.
                    int p = thenEnd;
                    while (p < n && Character.isWhitespace(s.charAt(p))) p++;
                    if (p + 4 <= n && s.startsWith("else", p)
                            && (p == 0 || !isIdentPart(s.charAt(p - 1)))
                            && (p + 4 >= n || !isIdentPart(s.charAt(p + 4)))) {
                        int ee = p + 4;
                        while (ee < n && Character.isWhitespace(s.charAt(ee))) ee++;
                        thenEnd = statementEnd(s, ee);
                    }
                }
                return thenEnd;
            }
                if (w.equals("do")) {
                    int k = j;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    int bEnd = statementEnd(s, k);
                    int mm = bEnd;
                    while (mm < n && Character.isWhitespace(s.charAt(mm))) mm++;
                    if (mm + 5 <= n && s.startsWith("while", mm)
                            && (mm + 5 >= n || !isIdentPart(s.charAt(mm + 5)))) {
                        int p = mm + 5;
                        while (p < n && Character.isWhitespace(s.charAt(p))) p++;
                        if (p < n && s.charAt(p) == '(') {
                            int close = matchParen(s, p);
                            int q = close + 1;
                            while (q < n && Character.isWhitespace(s.charAt(q))) q++;
                            if (q < n && s.charAt(q) == ';') return q + 1;
                            return q;
                        }
                    }
                    return bEnd;
                }
                if (w.equals("else")) {
                    int k = j;
                    while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                    return statementEnd(s, k);
                }
            }
        }
        int depth = 0;
        int k = i;
        while (k < n) {
            char ch = s.charAt(k);
            if (ch == '/' && k + 1 < n && s.charAt(k + 1) == '/') { int nl = s.indexOf('\n', k); if (nl < 0) nl = n; k = nl; continue; }
            if (ch == '/' && k + 1 < n && s.charAt(k + 1) == '*') { int e = s.indexOf("*/", k + 2); if (e < 0) e = n - 2; else e += 2; k = e; continue; }
            if (ch == '"' || ch == '\'') { k = skipLiteral(s, k); continue; }
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ';' && depth == 0) return k + 1;
            k++;
        }
        return n;
    }

    static int matchParen(String s, int i) {
        int n = s.length();
        int depth = 0;
        int k = i;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '/') { int nl = s.indexOf('\n', k); if (nl < 0) nl = n; k = nl; continue; }
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '*') { int e = s.indexOf("*/", k + 2); if (e < 0) e = n - 2; else e += 2; k = e; continue; }
            if (c == '"' || c == '\'') { k = skipLiteral(s, k); continue; }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return k; }
            k++;
        }
        return n - 1;
    }

    static int matchBrace(String s, int i) {
        int n = s.length();
        int depth = 0;
        int k = i;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '/') { int nl = s.indexOf('\n', k); if (nl < 0) nl = n; k = nl; continue; }
            if (c == '/' && k + 1 < n && s.charAt(k + 1) == '*') { int e = s.indexOf("*/", k + 2); if (e < 0) e = n - 2; else e += 2; k = e; continue; }
            if (c == '"' || c == '\'') { k = skipLiteral(s, k); continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return k; }
            k++;
        }
        return n - 1;
    }

    static int skipLiteral(String s, int i) {
        int n = s.length();
        char q = s.charAt(i);
        int k = i + 1;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\\') { k += 2; continue; }
            if (c == q) return k + 1;
            k++;
        }
        return n;
    }

    static boolean isIdentStart(char c) { return Character.isJavaIdentifierStart(c); }
    static boolean isIdentPart(char c) { return Character.isJavaIdentifierPart(c); }
}
