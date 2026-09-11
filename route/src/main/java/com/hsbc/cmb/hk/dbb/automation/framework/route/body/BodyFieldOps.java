package com.hsbc.cmb.hk.dbb.automation.framework.route.body;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 请求体字段操作的不可变载体。
 *
 * <p>统一承载「修改 / 新增 / 删除」三个维度，路径（key）语义由具体 {@link BodyCodec} 解释：
 * <ul>
 *   <li>JSON 体——key 为 JSONPath</li>
 *   <li>表单体（urlencoded / multipart）——key 为扁平字段名（part name）</li>
 * </ul>
 * 这样上层 {@code ModifyHandler} 无需关心编码，仅按 Content-Type 选择 codec 后透传本载体即可。
 */
public final class BodyFieldOps {

    private final Map<String, String> toModify;
    private final Map<String, String> toAdd;
    private final Set<String> toRemove;

    public BodyFieldOps(Map<String, String> toModify, Map<String, String> toAdd, Set<String> toRemove) {
        this.toModify = toModify != null ? toModify : Collections.emptyMap();
        this.toAdd = toAdd != null ? toAdd : Collections.emptyMap();
        this.toRemove = toRemove != null ? toRemove : Collections.emptySet();
    }

    public Map<String, String> getToModify() {
        return toModify;
    }

    public Map<String, String> getToAdd() {
        return toAdd;
    }

    public Set<String> getToRemove() {
        return toRemove;
    }

    /** 是否至少存在一个有效操作（用于短路，避免无谓解析）。 */
    public boolean hasOps() {
        return !toModify.isEmpty() || !toAdd.isEmpty() || !toRemove.isEmpty();
    }

    /** 空操作集合（调用方可用以判断是否需要进入编解码路径）。 */
    public static BodyFieldOps empty() {
        return new BodyFieldOps(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>());
    }
}
