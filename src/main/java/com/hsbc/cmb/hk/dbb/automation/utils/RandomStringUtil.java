package com.hsbc.cmb.hk.dbb.automation.utils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 随机字符串/数字生成工具类
 * 
 * 功能：
 * 1. 随机生成数字
 * 2. 随机生成字母字符串
 * 3. 随机生成混合字符串（字母+数字）
 * 4. 随机生成包含特殊字符的字符串
 * 5. 随机生成指定长度的字符串
 * 6. 随机生成指定范围内的数字
 * 7. 随机从列表中选择元素
 */
public class RandomStringUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    // 数字字符集
    private static final String DIGITS = "0123456789";

    // 小写字母
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";

    // 大写字母
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // 字母（大小写）
    private static final String ALPHABET = LOWERCASE + UPPERCASE;

    // 字母+数字
    private static final String ALPHANUMERIC = ALPHABET + DIGITS;

    // 特殊字符
    private static final String SPECIAL_CHARS = "!@#$%^&*()-_=+[]{}|;:,.<>?";

    // 所有可打印ASCII字符（排除空格）
    private static final String ALL_PRINTABLE = ALPHANUMERIC + SPECIAL_CHARS;

    /**
     * 随机生成数字
     * 
     * @param digitCount 数字位数
     * @return 指定位数的数字字符串
     * @throws IllegalArgumentException 如果digitCount小于等于0
     */
    public static String randomNumeric(int digitCount) {
        if (digitCount <= 0) {
            throw new IllegalArgumentException("Digit count must be greater than 0");
        }
        return random(DIGITS, digitCount);
    }

    /**
     * 随机生成小写字母字符串
     * 
     * @param length 字符串长度
     * @return 指定长度的小写字母字符串
     */
    public static String randomLowercase(int length) {
        return random(LOWERCASE, length);
    }

    /**
     * 随机生成大写字母字符串
     * 
     * @param length 字符串长度
     * @return 指定长度的大写字母字符串
     */
    public static String randomUppercase(int length) {
        return random(UPPERCASE, length);
    }

    /**
     * 随机生成字母字符串（大小写混合）
     * 
     * @param length 字符串长度
     * @return 指定长度的字母字符串
     */
    public static String randomAlphabetic(int length) {
        return random(ALPHABET, length);
    }

    /**
     * 随机生成字母数字混合字符串
     * 
     * @param length 字符串长度
     * @return 指定长度的字母数字字符串
     */
    public static String randomAlphanumeric(int length) {
        return random(ALPHANUMERIC, length);
    }

    /**
     * 随机生成包含特殊字符的字符串
     * 
     * @param length 字符串长度
     * @return 包含字母、数字和特殊字符的随机字符串
     */
    public static String randomStringWithSpecialChars(int length) {
        return random(ALL_PRINTABLE, length);
    }

    /**
     * 随机生成指定数量的特殊字符
     * 
     * @param count 特殊字符数量
     * @return 随机特殊字符字符串
     */
    public static String randomSpecialChars(int count) {
        return random(SPECIAL_CHARS, count);
    }

    /**
     * 随机生成指定范围内的整数
     * 
     * @param min 最小值（包含）
     * @param max 最大值（不包含）
     * @return [min, max)范围内的随机整数
     */
    public static int randomInt(int min, int max) {
        if (min >= max) {
            throw new IllegalArgumentException("Max must be greater than min");
        }
        return RANDOM.nextInt(max - min) + min;
    }

    /**
     * 随机生成指定范围内的整数（包含边界值）
     * 
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @return [min, max]范围内的随机整数
     */
    public static int randomIntInclusive(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Max must be greater than or equal to min");
        }
        return RANDOM.nextInt(max - min + 1) + min;
    }

    /**
     * 随机生成指定范围内的长整数
     * 
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @return [min, max]范围内的随机长整数
     */
    public static long randomLong(long min, long max) {
        if (min >= max) {
            throw new IllegalArgumentException("Max must be greater than min");
        }
        long range = max - min;
        long bits = 63 - Long.numberOfLeadingZeros(range);
        long value;
        do {
            value = RANDOM.nextLong() >>> (64 - bits);
        } while (value > range);
        return min + value;
    }

    /**
     * 随机生成布尔值
     * 
     * @return 随机true或false
     */
    public static boolean randomBoolean() {
        return RANDOM.nextBoolean();
    }

    /**
     * 随机生成浮点数
     * 
     * @param min 最小值（包含）
     * @param max 最大值（不包含）
     * @return [min, max)范围内的随机浮点数
     */
    public static double randomDouble(double min, double max) {
        if (min >= max) {
            throw new IllegalArgumentException("Max must be greater than min");
        }
        return min + (max - min) * RANDOM.nextDouble();
    }

    /**
     * 从列表中随机选择一个元素
     * 
     * @param list 列表
     * @param <T> 泛型类型
     * @return 随机选择的元素
     * @throws IllegalArgumentException 如果列表为空
     */
    public static <T> T randomElement(List<T> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("List cannot be null or empty");
        }
        return list.get(RANDOM.nextInt(list.size()));
    }

    /**
     * 从数组中随机选择一个元素
     * 
     * @param array 数组
     * @param <T> 泛型类型
     * @return 随机选择的元素
     * @throws IllegalArgumentException 如果数组为空
     */
    public static <T> T randomElement(T[] array) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException("Array cannot be null or empty");
        }
        return array[RANDOM.nextInt(array.length)];
    }

    /**
     * 随机生成UUID（不含连字符）
     * 
     * @return 32位的随机UUID字符串
     */
    public static String randomUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 随机生成手机号码（中国手机号格式）
     * 
     * @param prefix 号码前缀，如"138"、"139"，null则随机选择
     * @return 11位手机号
     */
    public static String randomMobileNumber(String prefix) {
        // 中国移动、联通、电信的号段
        String[] prefixes = {"134", "135", "136", "137", "138", "139", 
                         "150", "151", "152", "157", "158", "159",
                         "182", "183", "184", "187", "188", "178",
                         "130", "131", "132", "155", "156", "185", "186",
                         "133", "153", "180", "181", "189"};
        
        if (prefix == null || prefix.isEmpty()) {
            prefix = randomElement(prefixes);
        }
        
        return prefix + randomNumeric(8);
    }

    /**
     * 随机生成邮箱地址
     * 
     * @return 随机邮箱地址
     */
    public static String randomEmail() {
        String[] domains = {"gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "qq.com", "163.com", "126.com"};
        String domain = randomElement(domains);
        String username = randomAlphanumeric(8).toLowerCase();
        return username + "@" + domain;
    }

    /**
     * 随机生成IPv4地址
     * 
     * @return 随机IPv4地址
     */
    public static String randomIPv4() {
        return String.format("%d.%d.%d.%d",
                randomInt(0, 256),
                randomInt(0, 256),
                randomInt(0, 256),
                randomInt(0, 256));
    }

    /**
     * 随机生成MAC地址
     * 
     * @return 随机MAC地址
     */
    public static String randomMacAddress() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(String.format("%02x", randomInt(0, 256)));
        }
        return sb.toString();
    }

    /**
     * 随机生成十六进制字符串
     * 
     * @param length 字符串长度
     * @return 小写十六进制字符串
     */
    public static String randomHex(int length) {
        String hexChars = "0123456789abcdef";
        return random(hexChars, length);
    }

    /**
     * 随机生成中文字符串
     * 
     * @param length 字符串长度
     * @return 随机中文字符串
     */
    public static String randomChinese(int length) {
        // 常用汉字范围：基本汉字 0x4E00-0x9FFF
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = (char) randomInt(0x4E00, 0x9FFF + 1);
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 随机打乱列表
     * 
     * @param list 要打乱的列表
     * @param <T> 泛型类型
     */
    public static <T> void shuffle(List<T> list) {
        if (list == null) {
            return;
        }
        Collections.shuffle(list, RANDOM);
    }

    /**
     * 随机生成指定模式的字符串
     * 
     * @param pattern 模式字符串，如"XXX-XXX"（X代表随机字母，N代表随机数字）
     * @return 生成的字符串
     */
    public static String randomByPattern(String pattern) {
        StringBuilder result = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case 'X':
                case 'x':
                    result.append(random(ALPHABET, 1));
                    break;
                case 'N':
                case 'n':
                    result.append(random(DIGITS, 1));
                    break;
                case 'A':
                case 'a':
                    result.append(random(UPPERCASE, 1));
                    break;
                case 'L':
                case 'l':
                    result.append(random(LOWERCASE, 1));
                    break;
                case 'S':
                case 's':
                    result.append(random(SPECIAL_CHARS, 1));
                    break;
                default:
                    result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * 从指定字符集中随机生成字符串
     * 
     * @param charSet 字符集
     * @param length 生成长度
     * @return 随机字符串
     */
    private static String random(String charSet, int length) {
        if (charSet == null || charSet.isEmpty()) {
            throw new IllegalArgumentException("Character set cannot be null or empty");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        if (length == 0) {
            return "";
        }

        char[] buffer = new char[length];
        int charSetLength = charSet.length();
        for (int i = 0; i < length; i++) {
            buffer[i] = charSet.charAt(RANDOM.nextInt(charSetLength));
        }
        return new String(buffer);
    }
}
