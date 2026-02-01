package com.hsbc.cmb.dbb.hk.automation.framework.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 文件工具类
 */
public class FileUtils {
    
    /**
     * 读取文件内容为字符串
     */
    public static String readFileToString(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()));
    }
    
    /**
     * 将字符串写入文件
     */
    public static void writeStringToFile(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes());
    }
    
    /**
     * 检查文件是否存在
     */
    public static boolean fileExists(File file) {
        return file.exists() && file.isFile();
    }
    
    /**
     * 检查目录是否存在
     */
    public static boolean directoryExists(File directory) {
        return directory.exists() && directory.isDirectory();
    }
}