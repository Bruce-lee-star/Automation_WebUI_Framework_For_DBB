package com.hsbc.cmb.hk.dbb.automation.tests.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-5：数据工厂契约——模板加载、变体覆盖（基底保留）、唯一化、缺失模板报错。
 */
class PayloadFactoryTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void login_overridesCredentials() {
        JsonObject o = json(PayloadFactory.login("u123", "p456").toJson());

        assertEquals("u123", o.get("username").getAsString());
        assertEquals("p456", o.get("password").getAsString());
    }

    @Test
    void createUser_keepsTemplateFields_andAppliesOverrides() {
        JsonObject o = json(PayloadFactory.createUser(Map.of("name", "Neo")).toJson());

        assertEquals("Neo", o.get("name").getAsString());
        // 模板未覆盖字段应保留
        assertEquals("USER", o.get("role").getAsString());
        assertEquals("dave@example.com", o.get("email").getAsString());
    }

    @Test
    void uniqueUser_generatesDistinctData() {
        JsonObject a = json(PayloadFactory.uniqueUser().toJson());
        JsonObject b = json(PayloadFactory.uniqueUser().toJson());

        assertTrue(a.get("name").getAsString().startsWith("user_"));
        assertNotEquals(a.get("name").getAsString(), b.get("name").getAsString());
        assertNotEquals(a.get("email").getAsString(), b.get("email").getAsString());
    }

    @Test
    void missingTemplate_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Payload.of("this-template-does-not-exist"));
    }

    @Test
    void blankTemplateName_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Payload.of("  "));
    }
}
