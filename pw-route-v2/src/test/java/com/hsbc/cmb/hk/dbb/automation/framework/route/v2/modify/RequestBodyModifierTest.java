package com.hsbc.cmb.hk.dbb.automation.framework.route.v2.modify;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.BodyOp;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.util.MediaType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RequestBodyModifier 请求体修改：JSON（set/add/remove）、表单、非适用类型明确拒绝。
 */
public class RequestBodyModifierTest {

    @Test
    public void jsonSetUpdatesOrCreatesField() {
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(
                MediaType.parse("application/json"),
                "{\"code\":0,\"data\":{\"name\":\"old\"}}",
                List.of(new BodyOp(BodyOp.BodyOpType.SET, "$.code", 1)));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "{\"code\":1,\"data\":{\"name\":\"old\"}}", (Object) r.body());
    }

    @Test
    public void jsonAddAppendsToArray() {
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(
                MediaType.parse("application/json"),
                "{\"items\":[1,2]}",
                List.of(new BodyOp(BodyOp.BodyOpType.ADD, "$.items", 3)));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "{\"items\":[1,2,3]}", (Object) r.body());
    }

    @Test
    public void jsonRemoveDeletesPath() {
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(
                MediaType.parse("application/json"),
                "{\"code\":0,\"secret\":\"x\"}",
                List.of(new BodyOp(BodyOp.BodyOpType.REMOVE, "$.secret", null)));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "{\"code\":0}", (Object) r.body());
    }

    @Test
    public void missingJsonPathFailsIndependently() {
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(
                MediaType.parse("application/json"),
                "{\"a\":1}",
                List.of(
                        new BodyOp(BodyOp.BodyOpType.SET, "$.a", 2),
                        new BodyOp(BodyOp.BodyOpType.REMOVE, "$.missing.deep", null)));
        assertEquals((long) 1, (long) r.failures().size());
        assertEquals("成功操作仍生效，失败操作 fail-open", (Object) "{\"a\":2}", (Object) r.body());
    }

    @Test
    public void formSetAddRemove() {
        MediaType form = MediaType.parse("application/x-www-form-urlencoded");
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(form, "a=1&b=2",
                List.of(
                        new BodyOp(BodyOp.BodyOpType.SET, "a", "9"),
                        new BodyOp(BodyOp.BodyOpType.ADD, "c", "3"),
                        new BodyOp(BodyOp.BodyOpType.REMOVE, "b", null)));
        assertTrue(r.failures().isEmpty());
        assertEquals((Object) "a=9&c=3", (Object) r.body());
    }

    @Test
    public void unknownContentTypeRejectedFailOpen() {
        RequestBodyModifier.ModifyResult r = RequestBodyModifier.modify(
                MediaType.parse("application/octet-stream"),
                "binary",
                List.of(new BodyOp(BodyOp.BodyOpType.SET, "$.a", "1")));
        assertEquals((long) 1, (long) r.failures().size());
        assertTrue(r.failures().get(0).contains("not applicable"));
        assertEquals("拒绝修改必须保留原体", (Object) "binary", (Object) r.body());
    }
}
