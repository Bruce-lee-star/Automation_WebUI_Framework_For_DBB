package com.hsbc.cmb.hk.dbb.automation.framework.route.body;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code application/x-www-form-urlencoded} 编解码与字段改写。
 *
 * <p>语义：
 * <ul>
 *   <li>解析为<b>保序、可重复键</b>的字段列表（表单允许同名多值，如 {@code a=1&a=2}）。</li>
 *   <li>修改——替换所有同名（解码后）字段的值。</li>
 *   <li>删除——移除所有同名字段。</li>
 *   <li>新增——在表单末尾追加字段。</li>
 *   <li>回写——按 {@code name=value} 百分号编码后用 {@code &} 连接，保持字段顺序。</li>
 * </ul>
 *
 * <p>安全：解析异常或空操作直接返回原始字节，绝不截断 / 抛异常。
 */
public final class FormUrlEncodedBodyCodec implements BodyCodec {

    public static final FormUrlEncodedBodyCodec INSTANCE = new FormUrlEncodedBodyCodec();

    private static final class FormEntry {
        final String name;
        String value;

        FormEntry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private FormUrlEncodedBodyCodec() {
    }

    @Override
    public boolean supports(String contentType) {
        return contentType != null && contentType.contains("x-www-form-urlencoded");
    }

    @Override
    public byte[] transform(byte[] raw, BodyFieldOps ops, boolean fallback) {
        if (raw == null || !ops.hasOps()) {
            return raw;
        }
        try {
            String body = new String(raw, StandardCharsets.UTF_8);
            List<FormEntry> entries = parse(body);
            boolean changed = false;

            // 2a. 修改：替换所有同名（解码后）字段值
            for (Map.Entry<String, String> e : ops.getToModify().entrySet()) {
                for (FormEntry fe : entries) {
                    if (fe.name.equals(e.getKey())) {
                        fe.value = e.getValue();
                        changed = true;
                    }
                }
            }
            // 2b. 删除：移除所有同名字段
            if (!ops.getToRemove().isEmpty()) {
                Iterator<FormEntry> it = entries.iterator();
                while (it.hasNext()) {
                    if (ops.getToRemove().contains(it.next().name)) {
                        it.remove();
                        changed = true;
                    }
                }
            }
            // 2c. 新增：追加到末尾
            for (Map.Entry<String, String> e : ops.getToAdd().entrySet()) {
                entries.add(new FormEntry(e.getKey(), e.getValue()));
                changed = true;
            }

            if (!changed) {
                return raw;
            }
            return serialize(entries).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            // 解析失败：安全返回原始字节，不破坏载荷
            return raw;
        }
    }

    private static List<FormEntry> parse(String body) {
        List<FormEntry> entries = new ArrayList<>();
        if (body.isEmpty()) {
            return entries;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            entries.add(new FormEntry(urlDecode(rawName), urlDecode(rawValue)));
        }
        return entries;
    }

    private static String serialize(List<FormEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(entries.get(i).name)).append('=').append(urlEncode(entries.get(i).value));
        }
        return sb.toString();
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
