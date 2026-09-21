package com.hsbc.cmb.hk.dbb.automation.framework.api.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileReader} 单测（评审 P-2 补测）。
 *
 * <p>为什么值得测：payload / schema 等测试资材统一经本类从 classpath 读取，其契约是
 * 「<b>缺失资源返回 null 而非抛异常</b>」——调用方据此给出可定位的失败信息；若改为抛异常，
 * 会把「资材没打进包」变成难以归因的崩溃。故固化「命中读取 + 缺失静默 null + 空入参 null」三类行为。
 */
class FileReaderTest {

    private static final String PROBE = "file-reader-probe.txt";
    private static final String ABSENT = "no-such-resource-for-coverage-xyz.txt";

    @Test
    void readFileAsStringReturnsContentOfExistingClasspathResource() {
        assertThat(FileReader.readFileAsString(PROBE)).isEqualTo("file-reader-probe-payload");
    }

    @Test
    void readFileAsStringReturnsNullForMissingResource() {
        assertThat(FileReader.readFileAsString(ABSENT)).isNull();
    }

    @Test
    void readFileAsInputStreamHandlesNullBlankMissingAndExisting() {
        assertThat(FileReader.readFileAsInputStream(null)).isNull();
        assertThat(FileReader.readFileAsInputStream("")).isNull();
        assertThat(FileReader.readFileAsInputStream(ABSENT)).isNull();
        assertThat(FileReader.readFileAsInputStream(PROBE)).isNotNull();
    }
}
