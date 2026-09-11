package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.body.BodyCodecRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.route.body.BodyFieldOps;
import com.hsbc.cmb.hk.dbb.automation.framework.route.body.FormUrlEncodedBodyCodec;
import com.hsbc.cmb.hk.dbb.automation.framework.route.body.MultipartBodyCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表单（urlencoded / multipart）编解码企业级契约测试。
 *
 * <p>锁定：
 * <ul>
 *   <li>urlencoded：modify / add / remove、重复键、百分号编码保真、顺序保真、无操作返回原字节。</li>
 *   <li>multipart：文本 part 改写 / 删除 / 新增、文件 part 字节透传、boundary 与 CRLF 保真、解析失败安全回退。</li>
 *   <li>BodyCodecRegistry 按 Content-Type 分发。</li>
 * </ul>
 */
public class BodyCodecFormDataTest {

    private static BodyFieldOps ops(Map<String, String> modify, Map<String, String> add, Set<String> remove) {
        return new BodyFieldOps(modify, add, remove);
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static Set<String> s(String... keys) {
        Set<String> set = new LinkedHashSet<>();
        for (String k : keys) {
            set.add(k);
        }
        return set;
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // ───────────────────────── urlencoded ─────────────────────────

    @Test
    public void urlencoded_modify_replacesValuePreservingOrder() {
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "a=1&b=2".getBytes(StandardCharsets.UTF_8), ops(m("a", "9"), null, null), false);
        assertEquals("a=9&b=2", str(out));
    }

    @Test
    public void urlencoded_add_appendsAtEnd() {
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "a=1&b=2".getBytes(StandardCharsets.UTF_8), ops(null, m("c", "3"), null), false);
        assertEquals("a=1&b=2&c=3", str(out));
    }

    @Test
    public void urlencoded_remove_dropsField() {
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "a=1&b=2".getBytes(StandardCharsets.UTF_8), ops(null, null, s("b")), false);
        assertEquals("a=1", str(out));
    }

    @Test
    public void urlencoded_modify_replacesAllDuplicateKeys() {
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "a=1&a=2".getBytes(StandardCharsets.UTF_8), ops(m("a", "9"), null, null), false);
        assertEquals("a=9&a=9", str(out));
    }

    @Test
    public void urlencoded_preservesPercentEncodingRoundTrip() {
        // "hello world" 经 URLDecoder 为 "hello world"，改回 "a b" 应编码为 "a+b"
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "name=hello+world".getBytes(StandardCharsets.UTF_8), ops(m("name", "a b"), null, null), false);
        assertEquals("name=a+b", str(out));
    }

    @Test
    public void urlencoded_noOps_returnsOriginalReference() {
        byte[] raw = "a=1".getBytes(StandardCharsets.UTF_8);
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(raw, ops(null, null, null), false);
        assertSame(raw, out);
    }

    @Test
    public void urlencoded_emptyBody_addProducesSingleField() {
        byte[] out = FormUrlEncodedBodyCodec.INSTANCE.transform(
                "".getBytes(StandardCharsets.UTF_8), ops(null, m("x", "1"), null), false);
        assertEquals("x=1", str(out));
    }

    // ───────────────────────── multipart ─────────────────────────

    private static byte[] buildMultipart(String boundary, String... parts) {
        // parts: "name\u0000body" 或 "name\u0000filename\u0000body"
        // 严格遵循 RFC 2046：每个 part 前以 "--boundary\r\n" 分隔，末尾以 "--boundary--\r\n" 关闭
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("--").append(boundary).append("\r\n");
            }
            String p = parts[i];
            String[] seg = p.split("\u0000", -1);
            String name = seg[0];
            if (seg.length == 3) {
                sb.append("Content-Disposition: form-data; name=\"").append(name)
                        .append("\"; filename=\"").append(seg[1]).append("\"\r\n");
                sb.append("Content-Type: application/octet-stream\r\n\r\n").append(seg[2]).append("\r\n");
            } else {
                sb.append("Content-Disposition: form-data; name=\"").append(name)
                        .append("\"\r\n\r\n").append(seg[1]).append("\r\n");
            }
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void multipart_modify_replacesTextFieldKeepsFileAndBoundary() {
        byte[] raw = buildMultipart("BND", "field1\u0000v1", "file\u0000f.txt\u0000BINARYDATA");
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(m("field1", "vX"), null, null), false);
        String body = str(out);
        assertTrue(body.contains("vX"));
        assertFalse(body.contains("v1"));
        assertTrue(body.contains("BINARYDATA"));          // 文件 part 透传
        assertTrue(body.contains("--BND"));               // boundary 保真
        assertTrue(body.contains("--BND--"));             // 关闭分隔符保真
        assertTrue(body.contains("filename=\"f.txt\""));  // 文件头透传
    }

    @Test
    public void multipart_remove_dropsTextFieldKeepsFile() {
        byte[] raw = buildMultipart("BND", "field1\u0000v1", "file\u0000f.txt\u0000BINARYDATA");
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(null, null, s("field1")), false);
        String body = str(out);
        assertFalse(body.contains("v1"));
        assertTrue(body.contains("BINARYDATA"));
    }

    @Test
    public void multipart_add_appendsTextFieldBeforeClose() {
        byte[] raw = buildMultipart("BND", "field1\u0000v1");
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(null, m("extra", "e1"), null), false);
        String body = str(out);
        assertTrue(body.contains("name=\"extra\""));
        assertTrue(body.contains("e1"));
        // 新增 part 应位于关闭分隔符之前
        int closeIdx = body.indexOf("--BND--");
        int extraIdx = body.indexOf("name=\"extra\"");
        assertTrue(extraIdx > 0 && extraIdx < closeIdx);
    }

    @Test
    public void multipart_fileOnly_untouchedOnNoMatchingOp() {
        byte[] raw = buildMultipart("BND", "file\u0000f.txt\u0000BINARYDATA");
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(m("field1", "x"), null, null), false);
        // 无匹配字段：应原样返回（内容等价）
        assertEquals(str(raw), str(out));
    }

    @Test
    public void multipart_malformed_returnsOriginalSafely() {
        byte[] raw = "this is not multipart at all".getBytes(StandardCharsets.UTF_8);
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(m("x", "1"), null, null), false);
        assertSame(raw, out);
    }

    // ───────────────────────── registry 分发 ─────────────────────────

    @Test
    public void registry_dispatchesUrlencodedByContentType() {
        byte[] out = BodyCodecRegistry.tryTransform("application/x-www-form-urlencoded; charset=UTF-8",
                "a=1".getBytes(StandardCharsets.UTF_8), ops(m("a", "9"), null, null), false);
        assertEquals("a=9", str(out));
    }

    @Test
    public void registry_dispatchesMultipartByContentType() {
        byte[] raw = buildMultipart("BND", "field1\u0000v1");
        byte[] out = BodyCodecRegistry.tryTransform("multipart/form-data; boundary=BND", raw,
                ops(m("field1", "vX"), null, null), false);
        assertTrue(str(out).contains("vX"));
    }

    @Test
    public void registry_unsupportedContentType_returnsNull() {
        byte[] out = BodyCodecRegistry.tryTransform("application/xml",
                "<a>1</a>".getBytes(StandardCharsets.UTF_8), ops(m("a", "9"), null, null), false);
        assertNull(out);
    }

    // ───────────────────── 二进制文件 part 保真 ─────────────────────

    /** 字节级构造含二进制文件 part 的 multipart（避免经 String 重建导致二进制损坏）。 */
    private static byte[] buildMultipartBinary(String boundary, String textName, String textValue,
                                                String fileName, byte[] fileBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] textHeader = ("Content-Disposition: form-data; name=\"" + textName + "\"\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        out.write(delim, 0, delim.length);
        out.write(crlf, 0, 2);
        out.write(textHeader, 0, textHeader.length);
        out.write(crlf, 0, 2);
        byte[] tv = textValue.getBytes(StandardCharsets.UTF_8);
        out.write(tv, 0, tv.length);
        out.write(crlf, 0, 2);
        byte[] fileHeader = ("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n").getBytes(StandardCharsets.ISO_8859_1);
        out.write(delim, 0, delim.length);
        out.write(crlf, 0, 2);
        out.write(fileHeader, 0, fileHeader.length);
        out.write(crlf, 0, 2);
        out.write(fileBytes, 0, fileBytes.length);
        out.write(crlf, 0, 2);
        out.write(delim, 0, delim.length);
        out.write('-');
        out.write('-');
        out.write(crlf, 0, 2);
        return out.toByteArray();
    }

    private static int indexOf(byte[] src, byte[] pat) {
        for (int i = 0; i + pat.length <= src.length; i++) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void multipart_binaryFilePart_preservedByteForByteAfterTextFieldModify() {
        byte[] binary = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                (byte) 0xFF, (byte) 0xD8, (byte) 0x00, (byte) 0xAB, (byte) 0x7F, (byte) 0xC3 };
        byte[] raw = buildMultipartBinary("BND", "field1", "v1", "img.bin", binary);
        // 改写文本字段 field1 -> vX；二进制文件 part 须零拷贝透传（对应 ModifyHandler 经 setPostData(byte[]) 保真回写）
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(m("field1", "vX"), null, null), false);
        int idx = indexOf(out, binary);
        assertTrue(idx >= 0, "二进制文件 part 应在改写文本字段后原样保真");
        byte[] extracted = new byte[binary.length];
        System.arraycopy(out, idx, extracted, 0, binary.length);
        assertArrayEquals(binary, extracted, "二进制字节应零拷贝透传");
        // 文本改写生效且结构保真
        assertTrue(str(out).contains("vX"));
        assertTrue(str(out).contains("--BND--"));
    }

    @Test
    public void multipart_binaryFilePart_unchangedWhenNoMatchingOp() {
        byte[] binary = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xFF, (byte) 0x00, (byte) 0xAB };
        byte[] raw = buildMultipartBinary("BND", "field1", "v1", "img.bin", binary);
        // 改写目标不存在：应原样返回，二进制字节零损耗
        byte[] out = MultipartBodyCodec.INSTANCE.transform(raw, ops(m("nonexistent", "x"), null, null), false);
        int idx = indexOf(out, binary);
        assertTrue(idx >= 0);
        byte[] extracted = new byte[binary.length];
        System.arraycopy(out, idx, extracted, 0, binary.length);
        assertArrayEquals(binary, extracted);
    }
}
