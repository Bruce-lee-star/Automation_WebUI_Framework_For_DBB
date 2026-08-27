package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import com.hsbc.cmb.hk.dbb.automation.framework.api.config.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

public class FileReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileReader.class);

    private FileReader() {}

    public static String readFileAsString(final String relativePathOfFile) {
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream resourceAsStream = classLoader.getResourceAsStream(relativePathOfFile)) {
            if (resourceAsStream == null) {
                LOGGER.warn("Resource not found on classpath: {}", relativePathOfFile);
                return null;
            }
            // Use FrameworkConfig for encoding
            String encoding = FrameworkConfig.getFileEncoding();
            return new String(resourceAsStream.readAllBytes(), Charset.forName(encoding));
        } catch (IOException e) {
            LOGGER.error("Failed to read file {}: {}", relativePathOfFile, e.getMessage());
            return null;
        }
    }

    public static InputStream readFileAsInputStream (final String relativePathOfFile) {
        if (relativePathOfFile == null || relativePathOfFile.isEmpty()) {
            LOGGER.warn("Resource path is null or empty");
            return null;
        }
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(relativePathOfFile);
    }

}
