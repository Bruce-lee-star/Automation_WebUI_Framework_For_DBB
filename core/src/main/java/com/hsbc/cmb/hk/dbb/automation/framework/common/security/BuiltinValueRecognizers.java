package com.hsbc.cmb.hk.dbb.automation.framework.common.security;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内置值级敏感数据识别器（按值内容判定，与字段名白名单互补）。
 *
 * <p>覆盖金融多市场监管要求：
 * <ul>
 *   <li>{@code PAN} —— 银行卡号（Luhn 校验，13~19 位，去常见分隔符）</li>
 *   <li>{@code CHINA_BANK_CARD} —— 银联卡号（62 开头 BIN + Luhn，16~19 位）</li>
 *   <li>{@code IBAN} —— 国际银行账号（ISO 13616，mod-97 校验位）</li>
 *   <li>{@code HKID} —— 香港身份证（加权 mod-11 校验位）</li>
 *   <li>{@code CHINA_ID} —— 中国大陆居民身份证号（18 位，GB 11643 校验位）</li>
 *   <li>{@code CHINA_MOBILE} —— 中国大陆手机号（1[3-9] 开头 11 位，PII）</li>
 *   <li>{@code CHINA_PASSPORT} —— 中国大陆护照号（字母前缀 E/G/D/S/P + 8 位数字，PII）</li>
 *   <li>{@code CHINA_HKMO_PERMIT} —— 港澳通行证 / 回乡证（C/H/M/W 前缀 + 8 位数字，PII）</li>
 *   <li>{@code CHINA_USCC} —— 统一社会信用代码（GB 32100-2015，18 位，mod-31 校验位）</li>
 *   <li>{@code TRACK} —— 信用卡轨道数据（Track 1/2）</li>
 * </ul>
 *
 * <p>除 {@code TRACK} 与 {@code CHINA_PASSPORT}/{@code CHINA_HKMO_PERMIT}（按格式把关，无公开校验位）外，
 * 其余识别器均做「格式 + 校验位」双重把关，显著降低误报（如订单号恰巧通过 Luhn）。
 * 银行卡 CVV 无校验位，按<b>字段名</b>（cvv/cvc/cid/securityCode，见 {@code SensitiveDataSanitizer} 内置键清单）
 * 识别，避免裸 3~4 位数字在值级造成海量误报。</p>
 * 误报可通过配置 {@code sensitive.data.value.excludes} 豁免名单进一步抑制。
 */
final class BuiltinValueRecognizers {

    private BuiltinValueRecognizers() {
    }

    /** 返回内置识别器（顺序即优先级；数组/对象值判定为整值匹配）。 */
    static List<SensitiveValueRecognizer> builtins() {
        List<SensitiveValueRecognizer> list = new ArrayList<>();
        list.add(new LuhnPanRecognizer());
        list.add(new ChinaUnionPayCardRecognizer());
        list.add(new IbanRecognizer());
        list.add(new HkidRecognizer());
        list.add(new ChinaResidentIdRecognizer());
        list.add(new ChinaMobileRecognizer());
        list.add(new ChinaPassportRecognizer());
        list.add(new ChinaHkMoPermitRecognizer());
        list.add(new ChinaUsccRecognizer());
        list.add(new TrackDataRecognizer());
        return list;
    }

    // ────────────────────────────────────────────────────────────
    // PAN / Luhn
    // ────────────────────────────────────────────────────────────

    private static final class LuhnPanRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "PAN";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim();
            if (v.isEmpty()) return false;
            // 去掉常见分隔符（空格、连字符、点）
            String digits = v.replaceAll("[ \\-.]", "");
            int n = digits.length();
            if (n < 13 || n > 19) return false;
            if (!digits.chars().allMatch(Character::isDigit)) return false;
            // 全同数字（如 0000…0000）几乎不可能是真实卡号，抑制误报
            if (digits.chars().distinct().count() == 1) return false;
            return luhnValid(digits);
        }
    }

    private static boolean luhnValid(String digits) {
        int sum = 0, alt = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt % 2 != 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            alt++;
        }
        return sum % 10 == 0;
    }

    // ────────────────────────────────────────────────────────────
    // IBAN (ISO 13616, mod-97)
    // ────────────────────────────────────────────────────────────

    private static final class IbanRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "IBAN";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim().replaceAll("\\s+", "").toUpperCase();
            int n = v.length();
            if (n < 15 || n > 34) return false;
            if (!v.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) return false;
            // 校验位：前 4 位移至末尾，字母 A=10…Z=35（两位数字串），整体 mod 97 == 1
            String rearranged = v.substring(4) + v.substring(0, 4);
            StringBuilder sb = new StringBuilder(rearranged.length() * 2);
            for (int i = 0; i < rearranged.length(); i++) {
                char c = rearranged.charAt(i);
                sb.append(Character.isDigit(c) ? c : String.valueOf(c - 'A' + 10));
            }
            try {
                return new BigInteger(sb.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // HKID（香港身份证，加权 mod-11）
    // ────────────────────────────────────────────────────────────

    private static final class HkidRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "HKID";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            // 去括号与空白：CA182361(1) / CA1823611 均接受
            String v = value.trim().replaceAll("[()\\s]", "").toUpperCase();
            if (!v.matches("[A-Z]{1,2}\\d{6}[0-9A]")) return false;
            int part1;
            int start;
            if (v.length() == 8) {
                // 单字母前缀：隐含空格值 36（权重 9）→ 324 + 字母值×8
                int lv = v.charAt(0) - 'A' + 10; // A=10 … Z=35
                part1 = 324 + lv * 8;
                start = 1;
            } else {
                // 双字母前缀：字母1×9 + 字母2×8
                int l1 = v.charAt(0) - 'A' + 10;
                int l2 = v.charAt(1) - 'A' + 10;
                part1 = l1 * 9 + l2 * 8;
                start = 2;
            }
            int part2 = 0;
            int[] weights = {7, 6, 5, 4, 3, 2};
            for (int i = 0; i < 6; i++) {
                part2 += (v.charAt(start + i) - '0') * weights[i];
            }
            int total = part1 + part2;
            int remainder = 11 - (total % 11);
            char expected;
            if (remainder == 11) expected = '0';
            else if (remainder == 10) expected = 'A';
            else expected = (char) ('0' + remainder);
            return v.charAt(v.length() - 1) == expected;
        }
    }

    // ────────────────────────────────────────────────────────────
    // 信用卡轨道数据（Track 1 / Track 2）
    // ────────────────────────────────────────────────────────────

    private static final class TrackDataRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "TRACK";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim();
            if (v.isEmpty()) return false;
            // Track 1: %B<pan>^…^?   Track 2: ;<pan>=…?
            return v.matches("(?i)%B[0-9]{1,19}\\^.*") || v.matches("(?i);[0-9]{1,19}=.*");
        }
    }

    // ────────────────────────────────────────────────────────────
    // 银联卡号（China UnionPay，62 开头 BIN + Luhn，16~19 位）
    // ────────────────────────────────────────────────────────────

    private static final class ChinaUnionPayCardRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_BANK_CARD";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim().replaceAll("[ \\-.]", "");
            int n = v.length();
            if (n < 16 || n > 19) return false;
            if (!v.matches("62\\d+")) return false; // 银联 BIN 以 62 开头
            return luhnValid(v);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 中国大陆居民身份证号（GB 11643-1999，18 位，加权 mod-11 校验位）
    // ────────────────────────────────────────────────────────────

    private static final int[] CHINA_ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHINA_ID_CHECK = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private static final class ChinaResidentIdRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_ID";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim().toUpperCase();
            // 6 位地址 + 8 位出生日期(YYYYMMDD) + 3 位顺序 + 1 位校验(0-9/X)
            if (!v.matches("\\d{6}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9X]")) {
                return false;
            }
            int sum = 0;
            for (int i = 0; i < 17; i++) {
                sum += (v.charAt(i) - '0') * CHINA_ID_WEIGHTS[i];
            }
            return v.charAt(17) == CHINA_ID_CHECK[sum % 11];
        }
    }

    // ────────────────────────────────────────────────────────────
    // 中国大陆手机号（1[3-9] 开头 11 位，PII）
    // ────────────────────────────────────────────────────────────

    private static final class ChinaMobileRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_MOBILE";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            return value.trim().matches("1[3-9]\\d{9}");
        }
    }

    // ────────────────────────────────────────────────────────────
    // 中国大陆护照号（字母前缀 + 8 位数字；无公开校验位，按格式把关）
    // ────────────────────────────────────────────────────────────

    private static final class ChinaPassportRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_PASSPORT";
        }

        // 普通(E/G)/外交(D)/公务(S)/公务普通(P) 护照均为 1 位字母 + 8 位数字
        private static final Pattern PASSPORT = Pattern.compile("^[EGDSP]\\d{8}$");

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            return PASSPORT.matcher(value.trim().toUpperCase()).matches();
        }
    }

    // ────────────────────────────────────────────────────────────
    // 港澳通行证 / 回乡证（C/H/M/W 前缀 + 8 位数字；无公开校验位）
    // ────────────────────────────────────────────────────────────

    private static final class ChinaHkMoPermitRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_HKMO_PERMIT";
        }

        // C=往来港澳通行证(卡式)  W=往来港澳通行证(旧本式)
        // H=香港居民来往内地通行证(回乡证)  M=澳门居民来往内地通行证(回乡证)
        private static final Pattern PERMIT = Pattern.compile("^[CHMW]\\d{8}$");

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            return PERMIT.matcher(value.trim().toUpperCase()).matches();
        }
    }

    // ────────────────────────────────────────────────────────────
    // 统一社会信用代码（GB 32100-2015，18 位，mod-31 校验位）
    // ────────────────────────────────────────────────────────────

    /** 代码字符集（31 个，排除 I/O/Z/S/V）。 */
    private static final String USCC_CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    /** 前 17 位加权因子。 */
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    private static final class ChinaUsccRecognizer implements SensitiveValueRecognizer {
        @Override
        public String name() {
            return "CHINA_USCC";
        }

        @Override
        public boolean recognizes(String value) {
            if (value == null) return false;
            String v = value.trim().toUpperCase();
            if (v.length() != 18) return false;
            if (!v.matches("[" + USCC_CHARS + "]{18}")) return false;
            int sum = 0;
            for (int i = 0; i < 17; i++) {
                int idx = USCC_CHARS.indexOf(v.charAt(i));
                if (idx < 0) return false;
                sum += idx * USCC_WEIGHTS[i];
            }
            int check = 31 - (sum % 31);
            if (check == 31) check = 0;
            int checkIdx = USCC_CHARS.indexOf(v.charAt(17));
            return checkIdx == check;
        }
    }
}
