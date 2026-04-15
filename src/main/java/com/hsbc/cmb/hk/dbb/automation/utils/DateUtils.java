package com.hsbc.cmb.hk.dbb.automation.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Calendar;
import java.util.Date;

/**
 * 日期时间处理工具类
 * 
 * 功能：
 * 1. 日期格式化
 * 2. 日期计算（加减天数、月、年）
 * 3. 日期比较
 * 4. 获取各种日期（本周、本月、年初、年末等）
 * 5. 时间戳转换
 * 6. 时区处理
 */
public class DateUtils {

    // ==================== 常用日期格式 ====================
    
    /**
     * 标准日期格式：yyyy-MM-dd
     */
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    /**
     * 标准时间格式：yyyy-MM-dd HH:mm:ss
     */
    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 标准时间格式（毫秒）：yyyy-MM-dd HH:mm:ss.SSS
     */
    public static final String DATETIME_MS_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    /**
     * 紧凑日期格式：yyyyMMdd
     */
    public static final String COMPACT_DATE_FORMAT = "yyyyMMdd";

    /**
     * 紧凑时间格式：yyyyMMddHHmmss
     */
    public static final String COMPACT_DATETIME_FORMAT = "yyyyMMddHHmmss";

    /**
     * 年月格式：yyyyMM
     */
    public static final String YEAR_MONTH_FORMAT = "yyyyMM";

    /**
     * 时间格式：HH:mm:ss
     */
    public static final String TIME_FORMAT = "HH:mm:ss";

    /**
     * 带T的ISO格式：yyyy-MM-dd'T'HH:mm:ss
     */
    public static final String ISO_DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    // ==================== 日期格式化 ====================

    /**
     * 格式化日期为指定格式的字符串
     * 
     * @param date 日期对象
     * @param pattern 格式模式
     * @return 格式化后的字符串
     */
    public static String format(Date date, String pattern) {
        if (date == null || pattern == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        return sdf.format(date);
    }

    /**
     * 格式化LocalDateTime为指定格式的字符串
     * 
     * @param dateTime LocalDateTime对象
     * @param pattern 格式模式
     * @return 格式化后的字符串
     */
    public static String format(LocalDateTime dateTime, String pattern) {
        if (dateTime == null || pattern == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        return dateTime.format(formatter);
    }

    /**
     * 使用标准日期格式格式化
     * 
     * @param date 日期对象
     * @return yyyy-MM-dd格式的字符串
     */
    public static String formatDate(Date date) {
        return format(date, DATE_FORMAT);
    }

    /**
     * 使用标准时间格式格式化
     * 
     * @param date 日期对象
     * @return yyyy-MM-dd HH:mm:ss格式的字符串
     */
    public static String formatDateTime(Date date) {
        return format(date, DATETIME_FORMAT);
    }

    /**
     * 使用标准时间格式格式化（毫秒）
     * 
     * @param date 日期对象
     * @return yyyy-MM-dd HH:mm:ss.SSS格式的字符串
     */
    public static String formatDateTimeMs(Date date) {
        return format(date, DATETIME_MS_FORMAT);
    }

    /**
     * 格式化LocalDate
     * 
     * @param date LocalDate对象
     * @return yyyy-MM-dd格式的字符串
     */
    public static String format(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * 格式化LocalDateTime为标准格式
     * 
     * @param dateTime LocalDateTime对象
     * @return yyyy-MM-dd HH:mm:ss格式的字符串
     */
    public static String formatDateTime(LocalDateTime dateTime) {
        return format(dateTime, DATETIME_FORMAT);
    }

    /**
     * 格式化LocalTime
     * 
     * @param time LocalTime对象
     * @return HH:mm:ss格式的字符串
     */
    public static String formatTime(LocalTime time) {
        if (time == null) {
            return null;
        }
        return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
    }

    // ==================== 字符串转日期 ====================

    /**
     * 将字符串解析为日期
     * 
     * @param dateStr 日期字符串
     * @param pattern 格式模式
     * @return Date对象
     * @throws ParseException 解析失败
     */
    public static Date parse(String dateStr, String pattern) throws ParseException {
        if (dateStr == null || pattern == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        return sdf.parse(dateStr);
    }

    /**
     * 将字符串解析为LocalDateTime
     * 
     * @param dateStr 日期字符串
     * @param pattern 格式模式
     * @return LocalDateTime对象
     */
    public static LocalDateTime parseDateTime(String dateStr, String pattern) {
        if (dateStr == null || pattern == null) {
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        return LocalDateTime.parse(dateStr, formatter);
    }

    /**
     * 使用标准日期格式解析
     * 
     * @param dateStr yyyy-MM-dd格式的字符串
     * @return Date对象
     * @throws ParseException 解析失败
     */
    public static Date parseDate(String dateStr) throws ParseException {
        return parse(dateStr, DATE_FORMAT);
    }

    /**
     * 使用标准时间格式解析
     * 
     * @param dateStr yyyy-MM-dd HH:mm:ss格式的字符串
     * @return Date对象
     * @throws ParseException 解析失败
     */
    public static Date parseDateTime(String dateStr) throws ParseException {
        return parse(dateStr, DATETIME_FORMAT);
    }

    // ==================== 日期计算 ====================

    /**
     * 在指定日期基础上增加天数
     * 
     * @param date 原始日期
     * @param days 增加的天数（可为负数）
     * @return 新的日期
     */
    public static Date addDays(Date date, int days) {
        if (date == null) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_MONTH, days);
        return cal.getTime();
    }

    /**
     * 在LocalDateTime基础上增加天数
     * 
     * @param dateTime 原始日期时间
     * @param days 增加的天数（可为负数）
     * @return 新的日期时间
     */
    public static LocalDateTime addDays(LocalDateTime dateTime, int days) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusDays(days);
    }

    /**
     * 在指定日期基础上增加小时
     * 
     * @param date 原始日期
     * @param hours 增加的小时数（可为负数）
     * @return 新的日期
     */
    public static Date addHours(Date date, int hours) {
        if (date == null) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.HOUR_OF_DAY, hours);
        return cal.getTime();
    }

    /**
     * 在LocalDateTime基础上增加小时
     * 
     * @param dateTime 原始日期时间
     * @param hours 增加的小时数（可为负数）
     * @return 新的日期时间
     */
    public static LocalDateTime addHours(LocalDateTime dateTime, int hours) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusHours(hours);
    }

    /**
     * 在指定日期基础上增加分钟
     * 
     * @param date 原始日期
     * @param minutes 增加的分钟数（可为负数）
     * @return 新的日期
     */
    public static Date addMinutes(Date date, int minutes) {
        if (date == null) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.MINUTE, minutes);
        return cal.getTime();
    }

    /**
     * 在LocalDateTime基础上增加分钟
     * 
     * @param dateTime 原始日期时间
     * @param minutes 增加的分钟数（可为负数）
     * @return 新的日期时间
     */
    public static LocalDateTime addMinutes(LocalDateTime dateTime, int minutes) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusMinutes(minutes);
    }

    /**
     * 在指定日期基础上增加月数
     * 
     * @param date 原始日期
     * @param months 增加的月数（可为负数）
     * @return 新的日期
     */
    public static Date addMonths(Date date, int months) {
        if (date == null) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.MONTH, months);
        return cal.getTime();
    }

    /**
     * 在LocalDateTime基础上增加月数
     * 
     * @param dateTime 原始日期时间
     * @param months 增加的月数（可为负数）
     * @return 新的日期时间
     */
    public static LocalDateTime addMonths(LocalDateTime dateTime, int months) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusMonths(months);
    }

    /**
     * 在指定日期基础上增加年数
     * 
     * @param date 原始日期
     * @param years 增加的年数（可为负数）
     * @return 新的日期
     */
    public static Date addYears(Date date, int years) {
        if (date == null) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.YEAR, years);
        return cal.getTime();
    }

    /**
     * 在LocalDateTime基础上增加年数
     * 
     * @param dateTime 原始日期时间
     * @param years 增加的年数（可为负数）
     * @return 新的日期时间
     */
    public static LocalDateTime addYears(LocalDateTime dateTime, int years) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.plusYears(years);
    }

    // ==================== 日期比较 ====================

    /**
     * 比较两个日期
     * 
     * @param date1 日期1
     * @param date2 日期2
     * @return date1 < date2返回负数，相等返回0，date1 > date2返回正数
     */
    public static long compare(Date date1, Date date2) {
        if (date1 == null && date2 == null) {
            return 0;
        }
        if (date1 == null) {
            return -1;
        }
        if (date2 == null) {
            return 1;
        }
        return date1.getTime() - date2.getTime();
    }

    /**
     * 判断date1是否早于date2
     * 
     * @param date1 日期1
     * @param date2 日期2
     * @return date1 < date2返回true
     */
    public static boolean isBefore(Date date1, Date date2) {
        return compare(date1, date2) < 0;
    }

    /**
     * 判断date1是否晚于date2
     * 
     * @param date1 日期1
     * @param date2 日期2
     * @return date1 > date2返回true
     */
    public static boolean isAfter(Date date1, Date date2) {
        return compare(date1, date2) > 0;
    }

    /**
     * 判断两个日期是否相等
     * 
     * @param date1 日期1
     * @param date2 日期2
     * @return 日期相等返回true
     */
    public static boolean isEqual(Date date1, Date date2) {
        return compare(date1, date2) == 0;
    }

    /**
     * 计算两个日期之间的天数差
     * 
     * @param start 开始日期
     * @param end 结束日期
     * @return 天数差
     */
    public static long daysBetween(Date start, Date end) {
        if (start == null || end == null) {
            return 0;
        }
        long diffInMs = end.getTime() - start.getTime();
        return diffInMs / (1000 * 60 * 60 * 24);
    }

    /**
     * 计算两个LocalDateTime之间的天数差
     * 
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 天数差
     */
    public static long daysBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(start, end);
    }

    /**
     * 计算两个日期之间的小时数差
     * 
     * @param start 开始日期
     * @param end 结束日期
     * @return 小时数差
     */
    public static long hoursBetween(Date start, Date end) {
        if (start == null || end == null) {
            return 0;
        }
        long diffInMs = end.getTime() - start.getTime();
        return diffInMs / (1000 * 60 * 60);
    }

    /**
     * 计算两个日期之间的分钟数差
     * 
     * @param start 开始日期
     * @param end 结束日期
     * @return 分钟数差
     */
    public static long minutesBetween(Date start, Date end) {
        if (start == null || end == null) {
            return 0;
        }
        long diffInMs = end.getTime() - start.getTime();
        return diffInMs / (1000 * 60);
    }

    // ==================== 获取特殊日期 ====================

    /**
     * 获取当前日期
     * 
     * @return 当前日期
     */
    public static Date now() {
        return new Date();
    }

    /**
     * 获取当前LocalDateTime
     * 
     * @return 当前日期时间
     */
    public static LocalDateTime nowDateTime() {
        return LocalDateTime.now();
    }

    /**
     * 获取当前LocalDate
     * 
     * @return 当前日期
     */
    public static LocalDate today() {
        return LocalDate.now();
    }

    /**
     * 获取今天的开始时间（00:00:00）
     * 
     * @return 今天0点
     */
    public static LocalDateTime startOfDay() {
        return LocalDate.now().atStartOfDay();
    }

    /**
     * 获取今天的结束时间（23:59:59）
     * 
     * @return 今天最后一秒
     */
    public static LocalDateTime endOfDay() {
        return LocalDate.now().atTime(23, 59, 59);
    }

    /**
     * 获取本周的第一天（周一）
     * 
     * @return 本周一
     */
    public static LocalDate firstDayOfWeek() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 获取本周的最后一天（周日）
     * 
     * @return 本周日
     */
    public static LocalDate lastDayOfWeek() {
        return LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /**
     * 获取本月的第一天
     * 
     * @return 本月1号
     */
    public static LocalDate firstDayOfMonth() {
        return LocalDate.now().withDayOfMonth(1);
    }

    /**
     * 获取本月的最后一天
     * 
     * @return 本月最后一天
     */
    public static LocalDate lastDayOfMonth() {
        return LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
    }

    /**
     * 获取今年的第一天
     * 
     * @return 本年1月1号
     */
    public static LocalDate firstDayOfYear() {
        return LocalDate.now().withDayOfYear(1);
    }

    /**
     * 获取今年的最后一天
     * 
     * @return 本年12月31号
     */
    public static LocalDate lastDayOfYear() {
        return LocalDate.now().withMonth(12).withDayOfMonth(31);
    }

    /**
     * 获取昨天
     * 
     * @return 昨天日期
     */
    public static LocalDate yesterday() {
        return LocalDate.now().minusDays(1);
    }

    /**
     * 获取明天
     * 
     * @return 明天日期
     */
    public static LocalDate tomorrow() {
        return LocalDate.now().plusDays(1);
    }

    // ==================== 时间戳转换 ====================

    /**
     * Date转时间戳（毫秒）
     * 
     * @param date 日期对象
     * @return 时间戳
     */
    public static long toTimestamp(Date date) {
        return date == null ? 0 : date.getTime();
    }

    /**
     * 时间戳转Date
     * 
     * @param timestamp 时间戳（毫秒）
     * @return Date对象
     */
    public static Date fromTimestamp(long timestamp) {
        return new Date(timestamp);
    }

    /**
     * LocalDateTime转时间戳（毫秒）
     * 
     * @param dateTime LocalDateTime对象
     * @return 时间戳
     */
    public static long toTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 时间戳转LocalDateTime
     * 
     * @param timestamp 时间戳（毫秒）
     * @return LocalDateTime对象
     */
    public static LocalDateTime fromTimestampDateTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /**
     * LocalDateTime转秒时间戳
     * 
     * @param dateTime LocalDateTime对象
     * @return 秒时间戳
     */
    public static long toSecondsTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return 0;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 秒时间戳转LocalDateTime
     * 
     * @param timestamp 秒时间戳
     * @return LocalDateTime对象
     */
    public static LocalDateTime fromSecondsTimestamp(long timestamp) {
        return Instant.ofEpochSecond(timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    // ==================== 日期判断 ====================

    /**
     * 判断是否是闰年
     * 
     * @param year 年份
     * @return 是否是闰年
     */
    public static boolean isLeapYear(int year) {
        if (year % 4 != 0) {
            return false;
        } else if (year % 100 != 0) {
            return true;
        } else {
            return year % 400 == 0;
        }
    }

    /**
     * 判断给定日期是否是周末
     * 
     * @param date 日期
     * @return 是否是周六或周日
     */
    public static boolean isWeekend(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || 
               dayOfWeek == DayOfWeek.SUNDAY;
    }

    /**
     * 判断给定日期是否是工作日
     * 
     * @param date 日期
     * @return 是否是周一到周五
     */
    public static boolean isWeekday(LocalDate date) {
        return !isWeekend(date);
    }

    /**
     * 判断两个日期是否在同一天
     * 
     * @param date1 日期1
     * @param date2 日期2
     * @return 是否是同一天
     */
    public static boolean isSameDay(Date date1, Date date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        Calendar cal1 = Calendar.getInstance();
        cal1.setTime(date1);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 判断是否是今天
     * 
     * @param date 日期
     * @return 是否是今天
     */
    public static boolean isToday(Date date) {
        return isSameDay(date, now());
    }

    // ==================== 日期转换 ====================

    /**
     * Date转LocalDate
     * 
     * @param date Date对象
     * @return LocalDate对象
     */
    public static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Date转LocalDateTime
     * 
     * @param date Date对象
     * @return LocalDateTime对象
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * LocalDate转Date
     * 
     * @param localDate LocalDate对象
     * @return Date对象（时间部分为00:00:00）
     */
    public static Date toDate(LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * LocalDateTime转Date
     * 
     * @param localDateTime LocalDateTime对象
     * @return Date对象
     */
    public static Date toDate(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
