package com.hsbc.cmb.hk.dbb.automation.framework.route.body;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code multipart/form-data} 编解码与字段改写（表单家族的「文件上传」形态）。
 *
 * <p>策略：
 * <ul>
 *   <li>按 boundary 解析为有序 part 列表，<b>文件 part（含 {@code filename}）原样字节透传</b>，绝不篡改。</li>
 *   <li>文本 part（无 filename）支持：修改（替换 body）、删除（移除 part）、新增（追加 part）。</li>
 *   <li>重新序列化时<b>保真原始 boundary 与 CRLF 分隔</b>，文件 part 字节零拷贝。</li>
 *   <li>新增 / 修改的文本值按 UTF-8 编码（标准表单文本编码假设）。</li>
 * </ul>
 *
 * <p>安全：任何解析异常、缺 boundary、格式不符均返回原始字节（绝不截断 / 抛异常）。
 */
public final class MultipartBodyCodec implements BodyCodec {

    public static final MultipartBodyCodec INSTANCE = new MultipartBodyCodec();

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CRLFCRLF = {'\r', '\n', '\r', '\n'};

    private static final class Part {
        byte[] headerBlock;   // 含结尾 CRLFCRLF（头部与正文间的空行）
        byte[] body;
        final String name;    // 解码后的 part name（可能为 null）
        final boolean file;   // 是否文件 part

        Part(byte[] headerBlock, byte[] body, String name, boolean file) {
            this.headerBlock = headerBlock;
            this.body = body;
            this.name = name;
            this.file = file;
        }

        byte[] serialize() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    headerBlock.length + body.length);
            out.write(headerBlock, 0, headerBlock.length);
            out.write(body, 0, body.length);
            return out.toByteArray();
        }
    }

    private MultipartBodyCodec() {
    }

    @Override
    public boolean supports( String contentType) {
        return contentType != null && contentType.contains("multipart/form-data");
    }

    @Override
    public byte[] transform(byte[] raw, BodyFieldOps ops, boolean fallback) {
        if (raw == null || raw.length == 0 || !ops.hasOps()) {
            return raw;
        }
        try {
            String boundary = extractBoundary(raw);
            if (boundary == null || boundary.isEmpty()) {
                return raw;
            }
            byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            List<Part> parts = parseParts(raw, delim);
            if (parts == null) {
                return raw;
            }

            boolean changed = false;
            List<Part> result = new ArrayList<>(parts.size() + ops.getToAdd().size());
            for (Part p : parts) {
                if (p.file) {
                    result.add(p);
                    continue;
                }
                if (p.name != null && ops.getToRemove().contains(p.name)) {
                    changed = true; // 删除该 part
                    continue;
                }
                if (p.name != null && ops.getToModify().containsKey(p.name)) {
                    p.body = ops.getToModify().get(p.name).getBytes(StandardCharsets.UTF_8);
                    changed = true;
                }
                result.add(p);
            }
            for (Map.Entry<String, String> e : ops.getToAdd().entrySet()) {
                result.add(new Part(makeTextHeader(e.getKey()), e.getValue().getBytes(StandardCharsets.UTF_8), e.getKey(), false));
                changed = true;
            }

            if (!changed) {
                return raw;
            }
            return reassemble(result, delim);
        } catch (RuntimeException ex) {
            // 解析失败：安全返回原始字节
            return raw;
        }
    }

    /** 从体首行提取 boundary（首行格式 {@code --boundary}）。 */
    
    private static String extractBoundary(byte[] raw) {
        int nl = indexOf(raw, CRLF, 0);
        if (nl < 0) {
            return null;
        }
        String firstLine = new String(raw, 0, nl, StandardCharsets.ISO_8859_1);
        if (!firstLine.startsWith("--")) {
            return null;
        }
        return firstLine.substring(2);
    }

    /** 按 delim 切分为有序 part 列表；格式不符返回 null。 */
    
    private static List<Part> parseParts(byte[] raw, byte[] delim) {
        if (!startsWith(raw, delim, 0)) {
            return null;
        }
        int pos = delim.length;
        // 空体（首行即关闭分隔符）
        if (pos + 1 < raw.length && raw[pos] == '-' && raw[pos + 1] == '-') {
            return new ArrayList<>();
        }
        if (!startsWith(raw, CRLF, pos)) {
            return null;
        }
        pos += 2;

        List<Part> parts = new ArrayList<>();
        int partStart = pos;
        while (true) {
            int next = indexOf(raw, delim, pos);
            if (next < 0) {
                return null; // 缺少关闭分隔符，格式不符
            }
            int partEnd = next;
            if (partEnd >= 2 && raw[partEnd - 1] == '\n' && raw[partEnd - 2] == '\r') {
                partEnd -= 2; // 去掉 part 末尾的 CRLF（分隔符前的换行）
            }
            parts.add(parsePart(raw, partStart, partEnd));
            pos = next + delim.length;
            // 关闭分隔符：--boundary--
            if (pos + 1 < raw.length && raw[pos] == '-' && raw[pos + 1] == '-') {
                break;
            }
            if (!startsWith(raw, CRLF, pos)) {
                return null;
            }
            pos += 2;
            partStart = pos;
        }
        return parts;
    }

    private static Part parsePart(byte[] raw, int start, int end) {
        int hEnd = indexOf(raw, CRLFCRLF, start);
        if (hEnd < 0 || hEnd >= end) {
            // 无头/体分隔：整体当 body 透传（极少见，安全处理）
            return new Part(new byte[0], copyOf(raw, start, end), null, false);
        }
        byte[] headerBlock = copyOf(raw, start, hEnd + 4); // 含 CRLFCRLF
        byte[] body = copyOf(raw, hEnd + 4, end);
        String headerStr = new String(headerBlock, StandardCharsets.ISO_8859_1);
        String name = extractName(headerStr);
        boolean file = headerStr.toLowerCase().contains("filename=");
        return new Part(headerBlock, body, name, file);
    }

    private static byte[] makeTextHeader(String name) {
        byte[] base = ("Content-Disposition: form-data; name=\"" + name + "\"\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(base.length + 2);
        out.write(base, 0, base.length);
        out.write(CRLF, 0, CRLF.length); // 头部与正文间的空行
        return out.toByteArray();
    }

    private static byte[] reassemble(List<Part> parts, byte[] delim) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 首个 part 前的起始分隔符
        out.write(delim, 0, delim.length);
        out.write(CRLF, 0, CRLF.length);
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            out.write(p.serialize(), 0, p.serialize().length);
            out.write(CRLF, 0, CRLF.length);
            if (i < parts.size() - 1) {
                // 后续 part 前的分隔符
                out.write(delim, 0, delim.length);
                out.write(CRLF, 0, CRLF.length);
            } else {
                // 关闭分隔符：delim 紧接 "--"（无 CRLF 间隔），即 "--boundary--"
                out.write(delim, 0, delim.length);
                out.write('-');
                out.write('-');
                out.write(CRLF, 0, CRLF.length);
            }
        }
        return out.toByteArray();
    }

    
    private static String extractName(String headerStr) {
        int idx = headerStr.toLowerCase().indexOf("name=");
        if (idx < 0) {
            return null;
        }
        int q = idx + 5;
        if (q >= headerStr.length()) {
            return null;
        }
        char quote = headerStr.charAt(q);
        if (quote != '"' && quote != '\'') {
            // 无引号 name（非标准但容错）
            int end = headerStr.indexOf(';', q);
            return (end < 0 ? headerStr.substring(q) : headerStr.substring(q, end)).trim();
        }
        int close = headerStr.indexOf(quote, q + 1);
        if (close < 0) {
            return null;
        }
        return headerStr.substring(q + 1, close);
    }

    private static int indexOf(byte[] src, byte[] pattern, int from) {
        if (pattern.length == 0) {
            return from;
        }
        for (int i = from; i + pattern.length <= src.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (src[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] src, byte[] pattern, int offset) {
        if (offset + pattern.length > src.length) {
            return false;
        }
        for (int j = 0; j < pattern.length; j++) {
            if (src[offset + j] != pattern[j]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] copyOf(byte[] src, int from, int to) {
        int len = to - from;
        if (len <= 0) {
            return new byte[0];
        }
        byte[] dst = new byte[len];
        System.arraycopy(src, from, dst, 0, len);
        return dst;
    }
}
