package com.hsbc.cmb.hk.dbb.automation.framework.route.modify;

import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.BodyOp;
import com.hsbc.cmb.hk.dbb.automation.framework.route.util.MediaType;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求体修改器 —— MODIFY_REQUEST 的 body 级修改（IO 线程执行）。
 *
 * <p>修改语义按请求 Content-Type 路由（不把 JSON 当默认）：
 * <ul>
 *   <li>{@code application/json} 家族 → JSONPath：{@code SET}（存在更新/缺失按路径创建）、
 *       {@code ADD}（数组追加）、{@code REMOVE}（删除）；</li>
 *   <li>{@code application/x-www-form-urlencoded} → 表单字段：SET（存在替换首个/不存在追加）、
 *       ADD（追加重复键）、REMOVE（删除全部该键）；</li>
 *   <li>multipart / 未知 / 二进制 → 明确拒绝（{@code not applicable}），请求按原样 resume（fail-open），
 *       绝不猜测解析。</li>
 * </ul>
 *
 * <p>纯函数：不修改入参；单条操作失败不影响其它操作（fail-open，失败明细可观测）。
 */
public final class RequestBodyModifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBodyModifier.class);

    /** 修改结果：body=null 表示全部操作失败（调用方按原体 resume）；failures=失败明细。 */
    public record ModifyResult(String body, List<String> failures) {
        public ModifyResult {
            failures = Collections.unmodifiableList(new ArrayList<>(failures));
        }
    }

    private RequestBodyModifier() {
    }

    public static ModifyResult modify(MediaType mediaType, String body, List<BodyOp> ops) {
        String current = body;
        List<String> failures = new ArrayList<>();
        for (BodyOp op : ops) {
            try {
                current = applyOne(mediaType, current, op);
            } catch (Exception ex) {
                failures.add("body op " + op.type().name().toLowerCase() + " '" + op.path()
                        + "' failed: " + ex.getMessage());
                // fail-open：保留当前 body，继续下一条操作
            }
        }
        return new ModifyResult(current, failures);
    }

    private static String applyOne(MediaType mediaType, String body, BodyOp op) throws Exception {
        if (mediaType.isJson()) {
            return applyJson(body, op);
        }
        if (mediaType.isFormUrlEncoded()) {
            return applyForm(body, op, mediaType.charset());
        }
        throw new IllegalStateException("body modification not applicable: content-type is "
                + (mediaType.raw() == null ? "<missing>" : mediaType.raw()));
    }

    private static String applyJson(String body, BodyOp op) {
        DocumentContext context;
        try {
            context = JsonPath.parse(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("body is not valid JSON: " + e.getMessage(), e);
        }
        try {
            switch (op.type()) {
                case SET:
                    setOrCreate(context, op.path(), op.value());
                    break;
                case ADD:
                    context.add(op.path(), op.value());
                    break;
                case REMOVE:
                    context.delete(op.path());
                    break;
                default:
                    throw new IllegalStateException("unsupported body op: " + op.type());
            }
        } catch (PathNotFoundException e) {
            throw new IllegalArgumentException("jsonpath not found: " + op.path(), e);
        }
        return context.jsonString();
    }

    /** set 优先；路径不存在时父路径存在则按对象属性创建（末段为属性名）。 */
    private static void setOrCreate(DocumentContext context, String path, Object value) {
        try {
            context.set(path, value);
        } catch (PathNotFoundException e) {
            context.put(parentPath(path), lastKey(path), value);
        }
    }

    private static String parentPath(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "$" : path.substring(0, dot);
    }

    private static String lastKey(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static String applyForm(String body, BodyOp op, Charset charset) {
        Map<String, List<String>> fields = parseForm(body, charset);
        String value = String.valueOf(op.value());
        switch (op.type()) {
            case SET:
                List<String> values = fields.computeIfAbsent(op.path(), k -> new ArrayList<>());
                if (values.isEmpty()) {
                    values.add(value);
                } else {
                    values.set(0, value); // 替换首个值（重复键保留其余）
                }
                break;
            case ADD:
                fields.computeIfAbsent(op.path(), k -> new ArrayList<>()).add(value);
                break;
            case REMOVE:
                fields.remove(op.path());
                break;
            default:
                throw new IllegalStateException("unsupported body op: " + op.type());
        }
        return encodeForm(fields, charset);
    }

    static Map<String, List<String>> parseForm(String body, Charset charset) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = URLDecoder.decode(k, charset);
                v = URLDecoder.decode(v, charset);
            } catch (IllegalArgumentException e) {
                // 解码失败保留原样（不中断整条表单解析）
                LOGGER.trace("form pair decode failed, keeping raw: {}", pair);
            }
            result.computeIfAbsent(k, key -> new ArrayList<>()).add(v);
        }
        return result;
    }

    static String encodeForm(Map<String, List<String>> fields, Charset charset) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            for (String value : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(e.getKey(), charset))
                        .append('=')
                        .append(URLEncoder.encode(value, charset));
            }
        }
        return sb.toString();
    }
}
