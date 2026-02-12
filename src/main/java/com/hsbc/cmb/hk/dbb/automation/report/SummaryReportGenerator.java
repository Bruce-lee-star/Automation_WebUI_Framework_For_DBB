package com.hsbc.cmb.hk.dbb.automation.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.utils.LoggingConfigUtil;
import net.thucydides.model.domain.TestOutcome;
import net.thucydides.model.domain.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 邮件友好的测试摘要报告生成器
 * 生成完全独立的HTML报告，不依赖外部JS和CSS
 */
public class SummaryReportGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SummaryReportGenerator.class);
    private static final String REPORT_DIR = "target/site/serenity";
    private static final String SUMMARY_FILE = "summary.html";

    private final List<TestOutcome> testOutcomes;
    private final List<SimpleTestOutcome> simpleTestOutcomes;
    private final Map<TestResult, Long> resultCounts;
    private final long totalDuration;
    private final LocalDateTime reportTime;
    private final String customReportDir;
    private final Map<String, String> featureToHtmlMap;

    public SummaryReportGenerator() {
        this(REPORT_DIR);
    }
    
    /**
     * 支持自定义报告目录的构造函数
     * @param reportDir 报告目录路径
     */
    public SummaryReportGenerator(String reportDir) {
        this.customReportDir = reportDir;
        this.simpleTestOutcomes = new ArrayList<>();
        this.featureToHtmlMap = new java.util.LinkedHashMap<>();
        this.testOutcomes = loadTestOutcomes();
        // 尝试从其他文件加载测试结果
        File reportFile = new File(reportDir);
        loadFromOtherFiles(reportFile);
        // 从index.html提取Feature到HTML的映射
        loadFeatureHtmlMapping();

        this.resultCounts = calculateResultCounts();
        this.totalDuration = calculateTotalDuration();
        this.reportTime = LocalDateTime.now();
    }

    /**
     * 生成邮件友好的摘要报告
     */
    public void generateSummaryReport() {
        try {
            // 在生成报告之前，重新加载 Feature 映射（此时 index.html 应该已经生成）
            featureToHtmlMap.clear();
            loadFeatureHtmlMapping();
            LoggingConfigUtil.logInfoIfVerbose(logger, "Feature mappings reloaded: {} mappings", featureToHtmlMap.size());

            String htmlContent = buildHtmlReport();
            writeSummaryFile(htmlContent);
            LoggingConfigUtil.logInfoIfVerbose(logger, "Summary report generated: {}/{}", customReportDir, SUMMARY_FILE);
            } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to generate summary report", e);
        }
    }
    


    /**
     * 构建HTML报告内容
     */
    private String buildHtmlReport() {
        StringBuilder html = new StringBuilder();

        // HTML头部和样式
        html.append("<!DOCTYPE html>\n")
            .append("<html lang=\"en\">\n")
            .append("<head>\n")
            .append("    <meta charset=\"UTF-8\">\n")
            .append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("    <title>Summary Report</title>\n")
            .append(getLegacySerenityStyles())
            .append(getAdditionalStyles())
            .append("</head>\n")
            .append("<body>\n");

        // 报告标题和基本信息
        html.append(buildHeaderSection());

        // 测试结果统计
        html.append(buildResultSummarySection());

        // 测试用例详情
        html.append(buildTestDetailsSection());

        // 测试执行时间分析
        html.append(buildPerformanceSection());

        // HTML尾部
        html.append(getJavaScript())
            .append("</body>\n")
            .append("</html>\n");

        return html.toString();
    }

    /**
     * 获取额外的CSS样式
     */
    private String getAdditionalStyles() {
        return "<style type=\"text/css\">\n" +
                ".merged-cell {\n" +
                "    border-top: none !important;\n" +
                "}\n" +
                ".filter-group {\n" +
                "    display: flex;\n" +
                "    flex-direction: column;\n" +
                "    margin-right: 15px;\n" +
                "    min-width: 180px;\n" +
                "    max-width: 280px;\n" +
                "    flex: 1;\n" +
                "}\n" +
                ".filter-group label {\n" +
                "    font-weight: 600;\n" +
                "    margin-bottom: 5px;\n" +
                "    color: #4a5568;\n" +
                "    font-size: 13px;\n" +
                "    width: 100%;\n" +
                "    box-sizing: border-box;\n" +
                "}\n" +
                ".filter-input, .filter-select {\n" +
                "    padding: 8px 10px;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "    border-radius: 6px;\n" +
                "    font-size: 13px;\n" +
                "    transition: all 0.2s ease;\n" +
                "    width: 100%;\n" +
                "    box-sizing: border-box;\n" +
                "}\n" +
                ".filter-input:focus, .filter-select:focus {\n" +
                "    outline: none;\n" +
                "    border-color: #667eea;\n" +
                "    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.2);\n" +
                "}\n" +
                ".clear-filters-btn {\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    color: white;\n" +
                "    border: none;\n" +
                "    padding: 8px 16px;\n" +
                "    border-radius: 6px;\n" +
                "    font-size: 13px;\n" +
                "    font-weight: 600;\n" +
                "    cursor: pointer;\n" +
                "    transition: all 0.2s ease;\n" +
                "    margin-right: 10px;\n" +
                "}\n" +
                ".clear-filters-btn:hover {\n" +
                "    transform: translateY(-2px);\n" +
                "    box-shadow: 0 4px 8px rgba(102, 126, 234, 0.3);\n" +
                "}\n" +
                ".filter-buttons {\n" +
                "    display: flex;\n" +
                "    align-items: center;\n" +
                "    margin-top: 5px;\n" +
                "}\n" +
                "</style>\n";
    }
    
    /**
     * 获取JavaScript代码
     */
    private String getJavaScript() {
        return "<script type=\"text/javascript\">\n" +
                "document.addEventListener('DOMContentLoaded', function() {\n" +
                "    // 筛选功能\n" +
                "    var featureFilter = document.getElementById('feature-filter');\n" +
                "    var scenarioFilter = document.getElementById('scenario-filter');\n" +
                "    var resultFilter = document.getElementById('result-filter');\n" +
                "    var clearFiltersBtn = document.getElementById('clear-filters');\n" +
                "    var showExceptionsCheckbox = document.getElementById('show-exceptions');\n" +
                "    var testTable = document.querySelector('.test-details-table tbody');\n" +
                "    var allRows = Array.from(document.querySelectorAll('.test-row'));\n" +
                "    \n" +
                "    // 保存原始行的完整副本，避免DOM操作导致引用失效\n" +
                "    var originalRows = allRows.map(function(row) {\n" +
                "        var cloned = row.cloneNode(true);\n" +
                "        // 提取并存储Feature名称，避免依赖DOM遍历\n" +
                "        var cells = row.getElementsByTagName('td');\n" +
                "        if (cells.length > 0) {\n" +
                "            var featureCell = cells[0];\n" +
                "            var featureName = featureCell.textContent.trim();\n" +
                "            // 如果当前单元格为空（合并单元格），查找Feature名称\n" +
                "            if (!featureName) {\n" +
                "                var originalRow = row;\n" +
                "                while (originalRow.previousElementSibling) {\n" +
                "                    originalRow = originalRow.previousElementSibling;\n" +
                "                    var originalCells = originalRow.getElementsByTagName('td');\n" +
                "                    if (originalCells.length > 0) {\n" +
                "                        featureName = originalCells[0].textContent.trim();\n" +
                "                        if (featureName) break;\n" +
                "                    }\n" +
                "                }\n" +
                "            }\n" +
                "            // 将Feature名称存储为数据属性\n" +
                "            cloned.dataset.featureName = featureName || '';\n" +
                "        }\n" +
                "        return cloned;\n" +
                "    });\n" +
                "    \n" +
                "    // 更新Scenario下拉选项根据选中的Feature\n" +
                "    function updateScenarioOptions() {\n" +
                "        var selectedFeature = featureFilter.value;\n" +
                "        var currentValue = scenarioFilter.value;\n" +
                "        \n" +
                "        // 清空Scenario下拉选项\n" +
                "        while (scenarioFilter.options.length > 1) {\n" +
                "            scenarioFilter.remove(1);\n" +
                "        }\n" +
                "        \n" +
                "        // 从原始行中提取所有scenarios\n" +
                "        var allScenarios = new Set();\n" +
                "        originalRows.forEach(function(row) {\n" +
                "            var cells = row.getElementsByTagName('td');\n" +
                "            if (cells.length > 1) {\n" +
                "                var scenarioName = cells[1].textContent.trim();\n" +
                "                var rowFeature = row.dataset.featureName || '';\n" +
                "                \n" +
                "                // 如果选择了Feature，只添加该Feature下的scenarios\n" +
                "                if (!selectedFeature || rowFeature === selectedFeature) {\n" +
                "                    allScenarios.add(scenarioName);\n" +
                "                }\n" +
                "            }\n" +
                "        });\n" +
                "        \n" +
                "        // 添加options到下拉框\n" +
                "        Array.from(allScenarios).sort().forEach(function(scenario) {\n" +
                "            var option = document.createElement('option');\n" +
                "            option.value = scenario;\n" +
                "            option.textContent = scenario;\n" +
                "            scenarioFilter.appendChild(option);\n" +
                "        });\n" +
                "        \n" +
                "        // 保持或清除当前选择\n" +
                "        if (currentValue && allScenarios.has(currentValue)) {\n" +
                "            scenarioFilter.value = currentValue;\n" +
                "        } else {\n" +
                "            scenarioFilter.value = '';\n" +
                "        }\n" +
                "    }\n" +
                "    \n" +
                "    function applyFilters() {\n" +
                "        var featureValue = featureFilter.value;\n" +
                "        var scenarioValue = scenarioFilter.value;\n" +
                "        var resultValue = resultFilter.value;\n" +
                "        \n" +
                "        // console.log('Applying filters - feature:', featureValue, 'scenario:', scenarioValue, 'total rows:', originalRows.length);\n" +
                "        \n" +
                "        // 每次从原始行副本开始筛选\n" +
                "        var filteredRows = originalRows.slice();\n" +
                "        \n" +
                "        // Feature Name筛选 - 显示该Feature下的所有Scenario\n" +
                "        if (featureValue) {\n" +
                "            // console.log('Filtering by feature:', featureValue, 'before feature filter rows:', filteredRows.length);\n" +
                "            filteredRows = filteredRows.filter(function(row) {\n" +
                "                // 使用存储的Feature名称，避免DOM遍历问题\n" +
                "                var featureName = row.dataset.featureName || '';\n" +
                "                if (!featureName) return false;\n" +
                "                \n" +
                "                var match = featureName === featureValue;\n" +
                "                // if (!match) console.log('Feature no match - row feature:', featureName, 'filter:', featureValue);\n" +
                "                return match;\n" +
                "            });\n" +
                "            // console.log('After feature filter rows:', filteredRows.length);\n" +
                "        }\n" +
                "        \n" +
                "        // Scenario Name筛选 - 在已筛选的Feature中再筛选Scenario\n" +
                "        if (scenarioValue) {\n" +
                "            // console.log('Filtering by scenario:', scenarioValue, 'before scenario filter rows:', filteredRows.length);\n" +
                "            filteredRows = filteredRows.filter(function(row) {\n" +
                "                var cells = row.getElementsByTagName('td');\n" +
                "                var scenarioCell = cells[1];\n" +
                "                if (!scenarioCell) return false;\n" +
                "                var cellContent = scenarioCell.textContent.trim();\n" +
                "                var match = cellContent === scenarioValue;\n" +
                "                // if (!match) console.log('Scenario no match - cell:', cellContent, 'filter:', scenarioValue);\n" +
                "                return match;\n" +
                "            });\n" +
                "            // console.log('After scenario filter rows:', filteredRows.length);\n" +
                "        } else {\n" +
                "            // console.log('No scenario filter, showing all scenarios for selected feature');\n" +
                "        }\n" +
                "        \n" +
                "        // Result筛选\n" +
                "        if (resultValue) {\n" +
                "            filteredRows = filteredRows.filter(function(row) {\n" +
                "                var resultClass = row.getAttribute('data-result');\n" +
                "                return resultClass === resultValue;\n" +
                "            });\n" +
                "        }\n" +
                "        \n" +
                "" +
                "        \n" +
                "        // 清空表格并重新添加排序后的行\n" +
                "        while (testTable.firstChild) {\n" +
                "            testTable.removeChild(testTable.firstChild);\n" +
                "        }\n" +
                "        \n" +
                "        // console.log('Adding', filteredRows.length, 'rows to table');\n" +
                "        filteredRows.forEach(function(row) {\n" +
                "            // 克隆节点，避免移动originalRows中的节点\n" +
                "            var clonedRow = row.cloneNode(true);\n" +
                "            testTable.appendChild(clonedRow);\n" +
                "        });\n" +
                "        \n" +
                "" +
                "    }\n" +
                "    \n" +
                "    // 清除筛选条件\n" +
                "    function clearFilters() {\n" +
                "        featureFilter.value = '';\n" +
                "        updateScenarioOptions();\n" +
                "        scenarioFilter.value = '';\n" +
                "        resultFilter.value = '';\n" +
                "        showExceptionsCheckbox.checked = false;\n" +
                "        // 隐藏所有异常详情\n" +
                "        var exceptionDetails = document.querySelectorAll('.exception-details');\n" +
                "        for (var i = 0; i < exceptionDetails.length; i++) {\n" +
                "            exceptionDetails[i].style.display = 'none';\n" +
                "            var buttons = exceptionDetails[i].previousElementSibling.querySelectorAll('.toggle-exception');\n" +
                "            for (var j = 0; j < buttons.length; j++) {\n" +
                "                buttons[j].textContent = 'Show Exception';\n" +
                "            }\n" +
                "        }\n" +
                "        applyFilters();\n" +
                "    }\n" +
                "    \n" +
                "    // 添加事件监听器\n" +
                "    featureFilter.addEventListener('change', function() {\n" +
                "        updateScenarioOptions();\n" +
                "        applyFilters();\n" +
                "    });\n" +
                "    scenarioFilter.addEventListener('change', applyFilters);\n" +
                "    resultFilter.addEventListener('change', applyFilters);\n" +
                "" +
                "    // Show All Exceptions 复选框事件\n" +
                "    showExceptionsCheckbox.addEventListener('change', function() {\n" +
                "        var isChecked = this.checked;\n" +
                "        var exceptionDetails = document.querySelectorAll('.exception-details');\n" +
                "        for (var i = 0; i < exceptionDetails.length; i++) {\n" +
                "            exceptionDetails[i].style.display = isChecked ? 'block' : 'none';\n" +
                "            var buttons = exceptionDetails[i].previousElementSibling.querySelectorAll('.toggle-exception');\n" +
                "            for (var j = 0; j < buttons.length; j++) {\n" +
                "                buttons[j].textContent = isChecked ? 'Hide Exception' : 'Show Exception';\n" +
                "            }\n" +
                "        }\n" +
                "    });\n" +
                "" +
                "    clearFiltersBtn.addEventListener('click', clearFilters);\n" +
                "    \n" +
                "    // Show Exception 按钮事件委托\n" +
                "    testTable.addEventListener('click', function(event) {\n" +
                "        var button = event.target.closest('.toggle-exception');\n" +
                "        if (!button) return;\n" +
                "        \n" +
                "        var targetId = button.getAttribute('data-target');\n" +
                "        if (!targetId) return;\n" +
                "        \n" +
                "        var exceptionDiv = document.getElementById(targetId);\n" +
                "        if (exceptionDiv) {\n" +
                "            var isHidden = exceptionDiv.style.display === 'none' || exceptionDiv.style.display === '';\n" +
                "            exceptionDiv.style.display = isHidden ? 'block' : 'none';\n" +
                "            button.textContent = isHidden ? 'Hide Exception' : 'Show Exception';\n" +
                "        }\n" +
                "    });\n" +
                "    \n" +
                "    // CSV下载功能\n" +
                "    function downloadCSV() {\n" +
                "        // 获取所有可见的行（当前筛选结果）\n" +
                "        var visibleRows = Array.from(document.querySelectorAll('.test-row'));\n" +
                "        \n" +
                "        // 创建CSV内容\n" +
                "        var csvContent = 'Feature Name,Scenario Name,Result,Error Details\\n';\n" +
                "        \n" +
                "        visibleRows.forEach(function(row) {\n" +
                "            var cells = row.getElementsByTagName('td');\n" +
                "            if (cells.length >= 4) {\n" +
                "                // Feature Name\n" +
                "                var featureName = cells[0].textContent.trim();\n" +
                "                if (!featureName) {\n" +
                "                    // 如果是合并单元格，从前一个非空行获取Feature名称\n" +
                "                    var prevRow = row.previousElementSibling;\n" +
                "                    while (prevRow && !featureName) {\n" +
                "                        var prevCells = prevRow.getElementsByTagName('td');\n" +
                "                        if (prevCells.length > 0) {\n" +
                "                            featureName = prevCells[0].textContent.trim();\n" +
                "                        }\n" +
                "                        if (!featureName) prevRow = prevRow.previousElementSibling;\n" +
                "                    }\n" +
                "                }\n" +
                "                \n" +
                "                // Scenario Name\n" +
                "                var scenarioName = cells[1].textContent.trim();\n" +
                "                \n" +
                "                // Result\n" +
                "                var resultCell = cells[2];\n" +
                "                var resultText = resultCell.textContent.trim();\n" +
                "                \n" +
                "                // Error Details（如果有）\n" +
                "                var errorDetails = '';\n" +
                "                // 检查是否有异常按钮或异常信息\n" +
                "                var errorButton = row.querySelector('.toggle-exception');\n" +
                "                if (errorButton) {\n" +
                "                    // 有异常按钮，获取异常ID\n" +
                "                    var errorId = errorButton.getAttribute('data-target');\n" +
                "                    if (errorId) {\n" +
                "                        // 直接从整个文档中查找异常元素\n" +
                "                        var errorDiv = document.getElementById(errorId);\n" +
                "                        if (errorDiv) {\n" +
                "                            // 从异常元素中获取pre标签内的内容\n" +
                "                            var preElement = errorDiv.querySelector('pre');\n" +
                "                            if (preElement) {\n" +
                "                                errorDetails = preElement.textContent.trim();\n" +
                "                            } else {\n" +
                "                                // 如果没有pre标签，获取整个元素内容\n" +
                "                                errorDetails = errorDiv.textContent.trim();\n" +
                "                            }\n" +
                "                        }\n" +
                "                    }\n" +
                "                } else {\n" +
                "                    // 检查错误详情单元格\n" +
                "                    var errorDetailsCell = cells[3];\n" +
                "                    var noExceptionText = errorDetailsCell.textContent;\n" +
                "                    if (noExceptionText && noExceptionText.includes('No exception')) {\n" +
                "                        errorDetails = '';\n" +
                "                    } else {\n" +
                "                        // 可能有其他错误信息，直接获取单元格内容\n" +
                "                        errorDetails = errorDetailsCell.textContent.trim();\n" +
                "                    }\n" +
                "                }\n" +
                "                \n" +
                "                // 添加到CSV，确保正确转义双引号和逗号\n" +
                "                csvContent += '\"' + featureName.replace(/\"/g, '\"\"') + '\",' +\n" +
                "                             '\"' + scenarioName.replace(/\"/g, '\"\"') + '\",' +\n" +
                "                             '\"' + resultText.replace(/\"/g, '\"\"') + '\",' +\n" +
                "                             '\"' + errorDetails.replace(/\"/g, '\"\"') + '\"\\n';\n" +
                "            }\n" +
                "        });\n" +
                "        \n" +
                "        // 创建Blob对象\n" +
                "        var blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });\n" +
                "        var url = URL.createObjectURL(blob);\n" +
                "        \n" +
                "        // 创建临时下载链接\n" +
                "        var downloadLink = document.createElement('a');\n" +
                "        downloadLink.setAttribute('href', url);\n" +
                "        downloadLink.setAttribute('download', 'test_results_' + new Date().toISOString().slice(0, 19).replace(/:/g, '-') + '.csv');\n" +
                "        downloadLink.style.visibility = 'hidden';\n" +
                "        document.body.appendChild(downloadLink);\n" +
                "        downloadLink.click();\n" +
                "        document.body.removeChild(downloadLink);\n" +
                "        URL.revokeObjectURL(url);\n" +
                "    }\n" +
                "    \n" +
                "    // 添加CSV下载按钮事件监听器\n" +
                "    var downloadCsvBtn = document.getElementById('download-csv');\n" +
                "    if (downloadCsvBtn) {\n" +
                "        downloadCsvBtn.addEventListener('click', downloadCSV);\n" +
                "    }\n" +
                "    \n" +
                "    // 初始化时更新Scenario选项\n" +
                "    updateScenarioOptions();\n" +
                "});\n" +
                "</script>\n";
    }
    
    /**
     * 获取Serenity BDD 2.0.84版本的CSS样式
     * 生成完全独立的HTML报告，不依赖外部资源
     */
    private String getLegacySerenityStyles() {
        return "<style type=\"text/css\">\n" +
                "body {\n" +
                "    font-family: \"Helvetica Neue\", Helvetica, Arial, sans-serif;\n" +
                "    font-size: 14px;\n" +
                "    line-height: 1.42857143;\n" +
                "    color: #333;\n" +
                "    background-color: #f5f7fa;\n" +
                "    margin: 0;\n" +
                "    padding: 20px;\n" +
                "}\n" +
                ".container {\n" +
                "    width: 100%;\n" +
                "    max-width: 1200px;\n" +
                "    margin-right: auto;\n" +
                "    margin-left: auto;\n" +
                "    background-color: white;\n" +
                "    border-radius: 12px;\n" +
                "    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.07);\n" +
                "    padding: 30px;\n" +
                "}\n" +
                ".page-header {\n" +
                "    margin-bottom: 30px;\n" +
                "    padding: 25px 30px;\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    border-radius: 12px;\n" +
                "    color: white;\n" +
                "    box-shadow: 0 8px 16px rgba(102, 126, 234, 0.3);\n" +
                "}\n" +
                ".page-header h1 {\n" +
                "    margin: 0 0 10px 0;\n" +
                "    font-size: 28px;\n" +
                "    font-weight: 600;\n" +
                "    color: white;\n" +
                "}\n" +
                ".text-muted {\n" +
                "    color: rgba(255, 255, 255, 0.9);\n" +
                "    font-size: 14px;\n" +
                "}\n" +
                "h4 {\n" +
                "    margin-top: 0;\n" +
                "    margin-bottom: 20px;\n" +
                "    font-size: 20px;\n" +
                "    font-weight: 600;\n" +
                "    color: #2d3748;\n" +
                "}\n" +
                ".summary-table {\n" +
                "    width: 100%;\n" +
                "    margin-bottom: 20px;\n" +
                "    border-collapse: collapse;\n" +
                "    border-spacing: 0;\n" +
                "    background-color: white;\n" +
                "    border-radius: 8px;\n" +
                "    overflow: hidden;\n" +
                "    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);\n" +
                "}\n" +
                ".summary-table thead {\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    color: white;\n" +
                "}\n" +
                ".summary-table th {\n" +
                "    padding: 14px 12px;\n" +
                "    line-height: 1.42857143;\n" +
                "    vertical-align: bottom;\n" +
                "    text-align: left;\n" +
                "    font-weight: 600;\n" +
                "    font-size: 13px;\n" +
                "    color: white;\n" +
                "    border-bottom: none;\n" +
                "}\n" +
                ".summary-table td {\n" +
                "    padding: 12px;\n" +
                "    line-height: 1.42857143;\n" +
                "    vertical-align: top;\n" +
                "    border-top: 1px solid #e2e8f0;\n" +
                "    text-align: left;\n" +
                "    font-size: 13px;\n" +
                "}\n" +
                ".summary-leading-column {\n" +
                "    font-weight: 600;\n" +
                "    background-color: #f7fafc;\n" +
                "    width: 18%;\n" +
                "    color: #4a5568;\n" +
                "}\n" +
                ".progress-container {\n" +
                "    width: 100%;\n" +
                "    height: 24px;\n" +
                "    background-color: #edf2f7;\n" +
                "    border-radius: 12px;\n" +
                "    overflow: hidden;\n" +
                "    margin: 15px 0;\n" +
                "    display: flex;\n" +
                "    box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.06);\n" +
                "}\n" +
                ".progress-segment {\n" +
                "    height: 100%;\n" +
                "    transition: all 0.3s ease;\n" +
                "    display: flex;\n" +
                "    align-items: center;\n" +
                "    justify-content: center;\n" +
                "    font-size: 11px;\n" +
                "    font-weight: 600;\n" +
                "    color: white;\n" +
                "    cursor: pointer;\n" +
                "    position: relative;\n" +
                "    overflow: hidden;\n" +
                "}\n" +
                ".progress-segment:hover {\n" +
                "    filter: brightness(1.1);\n" +
                "    transform: scaleY(1.05);\n" +
                "    z-index: 10;\n" +
                "}\n" +
                ".progress-segment.active {\n" +
                "    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.4);\n" +
                "    transform: scaleY(1.1);\n" +
                "    z-index: 20;\n" +
                "}\n" +
                ".progress-success {\n" +
                "    background: linear-gradient(135deg, #48bb78 0%, #38a169 100%);\n" +
                "}\n" +
                ".progress-failure {\n" +
                "    background: linear-gradient(135deg, #fc8181 0%, #f56565 100%);\n" +
                "}\n" +
                ".progress-error {\n" +
                "    background: linear-gradient(135deg, #ed8936 0%, #dd6b20 100%);\n" +
                "}\n" +
                ".progress-skipped {\n" +
                "    background: linear-gradient(135deg, #a0aec0 0%, #718096 100%);\n" +
                "}\n" +
                ".progress-pending {\n" +
                "    background: linear-gradient(135deg, #f6e05e 0%, #d69e2e 100%);\n" +
                "}\n" +
                ".progress-compromised {\n" +
                "    background: linear-gradient(135deg, #ed64a6 0%, #d53f8c 100%);\n" +
                "}\n" +
                ".progress-ignored {\n" +
                "    background: linear-gradient(135deg, #9f7aea 0%, #805ad5 100%);\n" +
                "}\n" +
                ".icon-check {\n" +
                "    display: inline-block;\n" +
                "    width: 14px;\n" +
                "    height: 14px;\n" +
                "    margin-right: 5px;\n" +
                "    background-color: #5cb85c;\n" +
                "    border-radius: 50%;\n" +
                "    color: white;\n" +
                "    text-align: center;\n" +
                "    line-height: 14px;\n" +
                "}\n" +
                ".icon-thumbs-down {\n" +
                "    display: inline-block;\n" +
                "    width: 14px;\n" +
                "    height: 14px;\n" +
                "    margin-right: 5px;\n" +
                "    background-color: #d9534f;\n" +
                "    border-radius: 50%;\n" +
                "    color: white;\n" +
                "    text-align: center;\n" +
                "    line-height: 14px;\n" +
                "}\n" +
                ".icon-calendar {\n" +
                "    display: inline-block;\n" +
                "    width: 14px;\n" +
                "    height: 14px;\n" +
                "    margin-right: 5px;\n" +
                "    background-color: #f0ad4e;\n" +
                "    border-radius: 50%;\n" +
                "    color: white;\n" +
                "    text-align: center;\n" +
                "    line-height: 14px;\n" +
                "}\n" +
                ".icon-ban-circle {\n" +
                "    display: inline-block;\n" +
                "    width: 14px;\n" +
                "    height: 14px;\n" +
                "    margin-right: 5px;\n" +
                "    background-color: #777;\n" +
                "    border-radius: 50%;\n" +
                "    color: white;\n" +
                "    text-align: center;\n" +
                "    line-height: 14px;\n" +
                "}\n" +
                ".panel {\n" +
                "    margin-bottom: 30px;\n" +
                "    background-color: white;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "    border-radius: 12px;\n" +
                "    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);\n" +
                "}\n" +
                ".panel-heading {\n" +
                "    padding: 20px 25px;\n" +
                "    border-bottom: 1px solid #e2e8f0;\n" +
                "    border-top-left-radius: 12px;\n" +
                "    border-top-right-radius: 12px;\n" +
                "    color: #2d3748;\n" +
                "    background: linear-gradient(135deg, #f7fafc 0%, #edf2f7 100%);\n" +
                "    font-size: 18px;\n" +
                "    font-weight: 600;\n" +
                "}\n" +
                ".panel {\n" +
                "    box-sizing: border-box;\n" +
                "    overflow: hidden;\n" +
                "}\n" +
                ".panel-body {\n" +
                "    padding: 10px;\n" +
                "    overflow-x: auto;\n" +
                "    box-sizing: border-box;\n" +
                "    width: 100%;\n" +
                "}\n" +
                "#test-details-container {\n" +
                "    overflow-x: auto;\n" +
                "    width: 100%;\n" +
                "    box-sizing: border-box;\n" +
                "}\n" +
                ".filter-section {\n" +
                "    margin-top: 0;\n" +
                "    padding: 8px;\n" +
                "    background: linear-gradient(135deg, #f7fafc 0%, #edf2f7 100%);\n" +
                "    border-radius: 8px;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "    width: 100%;\n" +
                "    box-sizing: border-box;\n" +
                "}\n" +
                ".filter-section-title {\n" +
                "    font-weight: 600;\n" +
                "    margin-bottom: 5px;\n" +
                "    color: #4a5568;\n" +
                "    font-size: 14px;\n" +
                "}\n" +
                ".filter-options {\n" +
                "    display: flex;\n" +
                "    flex-wrap: wrap;\n" +
                "    gap: 6px;\n" +
                "    align-items: center;\n" +
                "    width: 100%;\n" +
                "    box-sizing: border-box;\n" +
                "}\n" +
                ".filter-label {\n" +
                "    display: flex;\n" +
                "    align-items: center;\n" +
                "    gap: 6px;\n" +
                "    padding: 8px 16px;\n" +
                "    background-color: white;\n" +
                "    border-radius: 20px;\n" +
                "    border: 2px solid #e2e8f0;\n" +
                "    cursor: pointer;\n" +
                "    transition: all 0.2s ease;\n" +
                "    font-size: 13px;\n" +
                "    font-weight: 500;\n" +
                "}\n" +
                ".filter-label:hover {\n" +
                "    border-color: #667eea;\n" +
                "    transform: translateY(-2px);\n" +
                "    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.08);\n" +
                "}\n" +
                ".filter-label input[type=\"checkbox\"] {\n" +
                "    width: 18px;\n" +
                "    height: 18px;\n" +
                "    cursor: pointer;\n" +
                "    accent-color: #667eea;\n" +
                "}\n" +
                ".filter-number-input {\n" +
                "    width: 70px;\n" +
                "    padding: 8px 12px;\n" +
                "    border: 2px solid #e2e8f0;\n" +
                "    border-radius: 8px;\n" +
                "    font-size: 13px;\n" +
                "    font-weight: 600;\n" +
                "    color: #4a5568;\n" +
                "    transition: all 0.2s ease;\n" +
                "}\n" +
                ".filter-number-input:focus {\n" +
                "    outline: none;\n" +
                "    border-color: #667eea;\n" +
                "    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.2);\n" +
                "}\n" +
                ".feature-group {\n" +
                "    margin-bottom: 25px;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "    border-radius: 12px;\n" +
                "    padding: 0;\n" +
                "    background-color: white;\n" +
                "    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.04);\n" +
                "    overflow: hidden;\n" +
                "}\n" +
                ".feature-title {\n" +
                "    margin: 0;\n" +
                "    padding: 18px 25px;\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    color: white;\n" +
                "    font-size: 16px;\n" +
                "    font-weight: 600;\n" +
                "    display: flex;\n" +
                "    justify-content: space-between;\n" +
                "    align-items: center;\n" +
                "}\n" +
                ".feature-title a {\n" +
                "    color: white;\n" +
                "    text-decoration: none;\n" +
                "    transition: opacity 0.2s ease;\n" +
                "}\n" +
                ".feature-title a:hover {\n" +
                "    opacity: 0.9;\n" +
                "    text-decoration: underline;\n" +
                "}\n" +
                ".feature-stats {\n" +
                "    display: flex;\n" +
                "    gap: 15px;\n" +
                "    align-items: center;\n" +
                "    font-size: 13px;\n" +
                "}\n" +
                ".feature-progress-container {\n" +
                "    width: 200px;\n" +
                "    height: 8px;\n" +
                "    background-color: rgba(255, 255, 255, 0.2);\n" +
                "    border-radius: 4px;\n" +
                "    overflow: hidden;\n" +
                "    display: flex;\n" +
                "}\n" +
                ".feature-progress-segment {\n" +
                "    height: 100%;\n" +
                "    transition: all 0.3s ease;\n" +
                "    cursor: pointer;\n" +
                "}\n" +
                ".feature-progress-segment:hover {\n" +
                "    filter: brightness(1.2);\n" +
                "    transform: scaleY(1.3);\n" +
                "    z-index: 10;\n" +
                "}\n" +
                ".feature-progress-success { background-color: #48bb78; }\n" +
                ".feature-progress-failure { background-color: #f56565; }\n" +
                ".feature-progress-error { background-color: #ed8936; }\n" +
                ".feature-progress-skipped { background-color: #a0aec0; }\n" +
                ".feature-stat-item {\n" +
                "    display: flex;\n" +
                "    align-items: center;\n" +
                "    gap: 4px;\n" +
                "    padding: 4px 10px;\n" +
                "    background-color: rgba(255, 255, 255, 0.2);\n" +
                "    border-radius: 12px;\n" +
                "    font-weight: 500;\n" +
                "    font-size: 12px;\n" +
                "}\n" +
                ".feature-stat-item.success { background-color: rgba(72, 187, 120, 0.3); }\n" +
                ".feature-stat-item.failure { background-color: rgba(245, 101, 101, 0.3); }\n" +
                ".feature-stat-item.error { background-color: rgba(237, 137, 54, 0.3); }\n" +
                ".feature-stat-item.skipped { background-color: rgba(160, 174, 192, 0.3); }\n" +
                ".feature-table {\n" +
                "    margin-top: 0;\n" +
                "    border-radius: 0;\n" +
                "    box-shadow: none;\n" +
                "}\n" +
                ".feature-table thead {\n" +
                "    background: linear-gradient(135deg, #f7fafc 0%, #edf2f7 100%);\n" +
                "    color: #4a5568;\n" +
                "}\n" +
                ".feature-table th {\n" +
                "    background-color: transparent;\n" +
                "    color: #4a5568;\n" +
                "    font-size: 12px;\n" +
                "    font-weight: 600;\n" +
                "    padding: 12px 15px;\n" +
                "}\n" +
                ".feature-table tbody tr {\n" +
                "    transition: all 0.2s ease;\n" +
                "}\n" +
                ".feature-table tbody tr:hover {\n" +
                "    background-color: #f7fafc;\n" +
                "    transform: scale(1.01);\n" +
                "    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);\n" +
                "}\n" +
                ".table-container { width: 100%; margin: 5px 0 20px 0; overflow: hidden; }\n" +
                ".test-details-table { width: 100%; table-layout: fixed; }\n" +
                ".test-details-table th, .test-details-table td { overflow: hidden; word-wrap: break-word; }\n" +
                ".test-details-table th:nth-child(1), .test-details-table td:nth-child(1) { width: 30%; min-width: 360px; max-width: 30%; }\n" +
                ".test-details-table th:nth-child(2), .test-details-table td:nth-child(2) { width: 30%; min-width: 360px; max-width: 30%; }\n" +
                ".test-details-table th:nth-child(3), .test-details-table td:nth-child(3) { width: 10%; min-width: 120px; max-width: 10%; }\n" +
                ".test-details-table th:nth-child(4), .test-details-table td:nth-child(4) { width: 30%; min-width: 360px; max-width: 30%; }\n" +
                ".test-details-table td:nth-child(1) a, .test-details-table td:nth-child(2) a { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n" +
                ".test-row { position: relative; }\n" +
                ".exception-details { display: none; }\n" +
                ".test-success { color: #48bb78; font-weight: 700; }\n" +
                ".test-failure { color: #f56565; font-weight: 700; }\n" +
                ".test-error { color: #ed8936; font-weight: 700; }\n" +
                ".test-pending { color: #d69e2e; font-weight: 700; }\n" +
                ".test-ignored { color: #805ad5; font-weight: 700; }\n" +
                ".test-skipped { color: #718096; font-weight: 700; }\n" +
                ".test-compromised { color: #d53f8c; font-weight: 700; }\n" +
                ".test-unknown { color: #a0aec0; font-weight: 700; }\n" +
                ".toggle-exception {\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    border: none;\n" +
                "    color: white;\n" +
                "    cursor: pointer;\n" +
                "    font-size: 12px;\n" +
                "    padding: 6px 14px;\n" +
                "    margin: 0;\n" +
                "    border-radius: 6px;\n" +
                "    font-weight: 600;\n" +
                "    transition: all 0.2s ease;\n" +
                "    box-shadow: 0 2px 4px rgba(102, 126, 234, 0.3);\n" +
                "}\n" +
                ".toggle-exception:hover {\n" +
                "    transform: translateY(-2px);\n" +
                "    box-shadow: 0 4px 8px rgba(102, 126, 234, 0.4);\n" +
                "}\n" +
                ".exception-details {\n" +
                "    margin-top: 10px;\n" +
                "    padding: 15px;\n" +
                "    background: linear-gradient(135deg, #fff5f5 0%, #fed7d7 100%);\n" +
                "    border: 1px solid #fc8181;\n" +
                "    border-radius: 8px;\n" +
                "    max-height: 300px;\n" +
                "    overflow-y: auto;\n" +
                "    box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.06);\n" +
                "}\n" +
                ".exception-details pre {\n" +
                "    margin: 0;\n" +
                "    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;\n" +
                "    white-space: pre-wrap;\n" +
                "    font-size: 11px;\n" +
                "    color: #c53030;\n" +
                "    line-height: 1.6;\n" +
                "}\n" +
                ".duration-cell {\n" +
                "    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;\n" +
                "    font-size: 12px;\n" +
                "    font-weight: 600;\n" +
                "    color: #4a5568;\n" +
                "}\n" +
                ".test-row.hidden {\n" +
                "    display: none;\n" +
                "}\n" +
                ".feature-group.hidden {\n" +
                "    display: none;\n" +
                "}\n" +
                ".summary-table {\n" +
                "    font-size: 13px;\n" +
                "}\n" +
                ".summary-table td { font-size: 13px; }\n" +
                ".stats-card {\n" +
                "    background: linear-gradient(135deg, #f7fafc 0%, #edf2f7 100%);\n" +
                "    border-radius: 8px;\n" +
                "    padding: 20px;\n" +
                "    margin-bottom: 20px;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "}\n" +
                ".stats-grid {\n" +
                "    display: grid;\n" +
                "    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));\n" +
                "    gap: 15px;\n" +
                "    margin-top: 15px;\n" +
                "}\n" +
                ".stat-box {\n" +
                "    background: white;\n" +
                "    padding: 20px;\n" +
                "    border-radius: 10px;\n" +
                "    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.06);\n" +
                "    text-align: center;\n" +
                "    transition: all 0.3s ease;\n" +
                "}\n" +
                ".stat-box:hover {\n" +
                "    transform: translateY(-4px);\n" +
                "    box-shadow: 0 6px 12px rgba(0, 0, 0, 0.1);\n" +
                "}\n" +
                ".stat-box.total { border-left: 4px solid #667eea; }\n" +
                ".stat-box.success { border-left: 4px solid #48bb78; }\n" +
                ".stat-box.failure { border-left: 4px solid #f56565; }\n" +
                ".stat-box.error { border-left: 4px solid #ed8936; }\n" +
                ".stat-box.skipped { border-left: 4px solid #a0aec0; }\n" +
                ".stat-label {\n" +
                "    font-size: 12px;\n" +
                "    font-weight: 600;\n" +
                "    color: #718096;\n" +
                "    text-transform: uppercase;\n" +
                "    letter-spacing: 0.5px;\n" +
                "    margin-bottom: 8px;\n" +
                "}\n" +
                ".stat-value {\n" +
                "    font-size: 32px;\n" +
                "    font-weight: 700;\n" +
                "    color: #2d3748;\n" +
                "    margin-bottom: 5px;\n" +
                "}\n" +
                ".stat-percent {\n" +
                "    font-size: 14px;\n" +
                "    font-weight: 600;\n" +
                "    color: #718096;\n" +
                "}\n" +
                ".stat-box.success .stat-value { color: #48bb78; }\n" +
                ".stat-box.failure .stat-value { color: #f56565; }\n" +
                ".stat-box.error .stat-value { color: #ed8936; }\n" +
                ".stat-box.skipped .stat-value { color: #a0aec0; }\n" +
                ".filter-select {\n" +
                "    margin-left: 8px;\n" +
                "    padding: 4px 8px;\n" +
                "    border: 1px solid #667eea;\n" +
                "    border-radius: 4px;\n" +
                "    background-color: white;\n" +
                "    color: #4a5568;\n" +
                "    font-size: 11px;\n" +
                "    cursor: pointer;\n" +
                "    font-weight: 600;\n" +
                "    transition: all 0.2s ease;\n" +
                "}\n" +
                ".filter-select:hover {\n" +
                "    border-color: #764ba2;\n" +
                "    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.2);\n" +
                "}\n" +
                ".filter-select:focus {\n" +
                "    outline: none;\n" +
                "    border-color: #667eea;\n" +
                "    box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.3);\n" +
                "}\n" +
                ".sort-buttons {\n" +
                "    display: inline-flex;\n" +
                "    gap: 4px;\n" +
                "    margin-left: 8px;\n" +
                "}\n" +
                ".sort-btn {\n" +
                "    width: 24px;\n" +
                "    height: 24px;\n" +
                "    padding: 0;\n" +
                "    border: 1px solid #e2e8f0;\n" +
                "    background-color: white;\n" +
                "    border-radius: 4px;\n" +
                "    cursor: pointer;\n" +
                "    font-size: 12px;\n" +
                "    color: #718096;\n" +
                "    transition: all 0.2s ease;\n" +
                "    display: flex;\n" +
                "    align-items: center;\n" +
                "    justify-content: center;\n" +
                "}\n" +
                ".sort-btn:hover {\n" +
                "    background-color: #f7fafc;\n" +
                "    border-color: #667eea;\n" +
                "    color: #667eea;\n" +
                "    transform: scale(1.1);\n" +
                "}\n" +
                ".sort-btn.active {\n" +
                "    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);\n" +
                "    border-color: #667eea;\n" +
                "    color: white;\n" +
                "    box-shadow: 0 2px 4px rgba(102, 126, 234, 0.3);\n" +
                "}\n" +
                "</style>\n";
    }

    /**
     * 构建报告头部
     */
    private String buildHeaderSection() {
        String projectName = PlaywrightManager.getProjectName();
        return String.format(
                "<div class=\"container\">\n" +
                "    <div class=\"page-header\">\n" +
                "        <h1>%s</h1>\n" +
                "        <p class=\"text-muted\">Generated at: %s</p>\n" +
                "    </div>\n" +
                "</div>", projectName, reportTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
    /**
     * 构建测试结果摘要 - 优化为更接近Serenity原生格式
     */
    private String buildResultSummarySection() {
        // 计算总数
        long totalCount = resultCounts.values().stream().mapToLong(Long::longValue).sum();
        long successCount = resultCounts.getOrDefault(TestResult.SUCCESS, 0L);
        long pendingCount = resultCounts.getOrDefault(TestResult.PENDING, 0L);
        long ignoredCount = resultCounts.getOrDefault(TestResult.IGNORED, 0L);
        long skippedCount = resultCounts.getOrDefault(TestResult.SKIPPED, 0L);
        long failureCount = resultCounts.getOrDefault(TestResult.FAILURE, 0L);
        long errorCount = resultCounts.getOrDefault(TestResult.ERROR, 0L);
        long compromisedCount = resultCounts.getOrDefault(TestResult.COMPROMISED, 0L);
        long failureOrErrorCount = failureCount + errorCount;

        // 计算百分比
        double percentageSuccessCount = totalCount > 0 ? (double) successCount / totalCount : 0.0;
        double percentagePendingCount = totalCount > 0 ? (double) pendingCount / totalCount : 0.0;
        double percentageIgnoredCount = totalCount > 0 ? (double) ignoredCount / totalCount : 0.0;
        double percentageSkippedCount = totalCount > 0 ? (double) skippedCount / totalCount : 0.0;
        double percentageFailureCount = totalCount > 0 ? (double) failureCount / totalCount : 0.0;
        double percentageErrorCount = totalCount > 0 ? (double) errorCount / totalCount : 0.0;
        double percentageFailureOrErrorCount = totalCount > 0 ? (double) failureOrErrorCount / totalCount : 0.0;
        double percentageCompromisedCount = totalCount > 0 ? (double) compromisedCount / totalCount : 0.0;

        // 格式化百分比
        String successPercent = String.format("%.1f%%", percentageSuccessCount * 100);
        String pendingPercent = String.format("%.1f%%", percentagePendingCount * 100);
        String ignoredPercent = String.format("%.1f%%", percentageIgnoredCount * 100);
        String skippedPercent = String.format("%.1f%%", percentageSkippedCount * 100);
        String failurePercent = String.format("%.1f%%", percentageFailureCount * 100);
        String errorPercent = String.format("%.1f%%", percentageErrorCount * 100);
        String failureOrErrorPercent = String.format("%.1f%%", percentageFailureOrErrorCount * 100);
        String compromisedPercent = String.format("%.1f%%", percentageCompromisedCount * 100);

        // 计算持续时间
        String durationText;
        long durationInSeconds = totalDuration / 1000;
        if (durationInSeconds > 60) {
            durationText = String.format("%d minutes %d seconds", durationInSeconds / 60, durationInSeconds % 60);
        } else {
            durationText = String.format("%d seconds", durationInSeconds);
        }

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"container\">\n")
             .append("    <div class=\"row\">\n")
             .append("        <div class=\"col-md-12\">\n")
             .append("            <div class=\"panel panel-default\">\n")
             .append("                <div class=\"panel-heading\">Test Execution Summary</div>\n")
             .append("                <div class=\"panel-body\">\n")
             .append("                    <div class=\"stats-card\">\n")
             .append("        <div class=\"progress-container\">\n");

        // 添加进度条段
        if (successCount > 0) {
            html.append("            <div class=\"progress-segment progress-success\" data-type=\"success\" style=\"width: ").append(successPercent).append(";\" title=\"Passed: ").append(successCount).append(" (").append(successPercent).append(")\">").append(successCount).append("</div>\n");
        }
        if (failureCount > 0) {
            html.append("            <div class=\"progress-segment progress-failure\" data-type=\"failure\" style=\"width: ").append(failurePercent).append(";\" title=\"Failed: ").append(failureCount).append(" (").append(failurePercent).append(")\">").append(failureCount).append("</div>\n");
        }
        if (errorCount > 0) {
            html.append("            <div class=\"progress-segment progress-error\" data-type=\"error\" style=\"width: ").append(errorPercent).append(";\" title=\"Failed with errors: ").append(errorCount).append(" (").append(errorPercent).append(")\">").append(errorCount).append("</div>\n");
        }
        if (skippedCount > 0) {
            html.append("            <div class=\"progress-segment progress-skipped\" data-type=\"skipped\" style=\"width: ").append(skippedPercent).append(";\" title=\"Skipped: ").append(skippedCount).append(" (").append(skippedPercent).append(")\">").append(skippedCount).append("</div>\n");
        }
        if (pendingCount > 0) {
            html.append("            <div class=\"progress-segment progress-pending\" data-type=\"pending\" style=\"width: ").append(pendingPercent).append(";\" title=\"Pending: ").append(pendingCount).append(" (").append(pendingPercent).append(")\">").append(pendingCount).append("</div>\n");
        }
        if (compromisedCount > 0) {
            html.append("            <div class=\"progress-segment progress-compromised\" data-type=\"compromised\" style=\"width: ").append(compromisedPercent).append(";\" title=\"Compromised: ").append(compromisedCount).append(" (").append(compromisedPercent).append(")\">").append(compromisedCount).append("</div>\n");
        }
        if (ignoredCount > 0) {
            html.append("            <div class=\"progress-segment progress-ignored\" data-type=\"ignored\" style=\"width: ").append(ignoredPercent).append(";\" title=\"Ignored: ").append(ignoredCount).append(" (").append(ignoredPercent).append(")\">").append(ignoredCount).append("</div>\n");
        }

        html.append("        </div>\n")
             .append("        <div class=\"stats-grid\">\n")
             .append("            <div class=\"stat-box total\">\n")
             .append("                <div class=\"stat-label\">Total Tests</div>\n")
             .append("                <div class=\"stat-value\">").append(totalCount).append("</div>\n")
             .append("                <div class=\"stat-percent\">100%</div>\n")
             .append("            </div>\n")
             // 只显示非0的统计框
             .append(successCount > 0 ? "            <div class=\"stat-box success\">\n" +
             "                <div class=\"stat-label\">Passed</div>\n" +
             "                <div class=\"stat-value\">" + successCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + successPercent + "</div>\n" +
             "            </div>\n" : "")
             .append(failureCount > 0 ? "            <div class=\"stat-box failure\">\n" +
             "                <div class=\"stat-label\">Failed</div>\n" +
             "                <div class=\"stat-value\">" + failureCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + failurePercent + "</div>\n" +
             "            </div>\n" : "")
             .append(errorCount > 0 ? "            <div class=\"stat-box error\">\n" +
             "                <div class=\"stat-label\">Failed with errors</div>\n" +
             "                <div class=\"stat-value\">" + errorCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + errorPercent + "</div>\n" +
             "            </div>\n" : "")
             .append(skippedCount > 0 ? "            <div class=\"stat-box skipped\">\n" +
             "                <div class=\"stat-label\">Skipped</div>\n" +
             "                <div class=\"stat-value\">" + skippedCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + skippedPercent + "</div>\n" +
             "            </div>\n" : "")
             .append(pendingCount > 0 ? "            <div class=\"stat-box pending\">\n" +
             "                <div class=\"stat-label\">Pending</div>\n" +
             "                <div class=\"stat-value\">" + pendingCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + pendingPercent + "</div>\n" +
             "            </div>\n" : "")
             .append(ignoredCount > 0 ? "            <div class=\"stat-box ignored\">\n" +
             "                <div class=\"stat-label\">Ignored</div>\n" +
             "                <div class=\"stat-value\">" + ignoredCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + ignoredPercent + "</div>\n" +
             "            </div>\n" : "")
             .append(compromisedCount > 0 ? "            <div class=\"stat-box compromised\">\n" +
             "                <div class=\"stat-label\">Compromised</div>\n" +
             "                <div class=\"stat-value\">" + compromisedCount + "</div>\n" +
             "                <div class=\"stat-percent\">" + compromisedPercent + "</div>\n" +
             "            </div>\n" : "")
             .append("                    </div>\n")
             .append("                </div>\n")
             .append("            </div>\n")
             .append("        </div>\n")
             .append("    </div>\n")
             .append("</div>\n");
        
        return html.toString();
    }

    /**
     * 构建测试用例详情 - 按Feature分组
     */
    private String buildTestDetailsSection() {
        StringBuilder details = new StringBuilder();
        
        // 如果simpleTestOutcomes为空，尝试从JSON文件直接加载测试结果
        if (simpleTestOutcomes.isEmpty()) {
            try {
                loadTestResultsFromJsonFiles();
            } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to load test results from JSON files", e);
            }
        }
        
        // 获取所有测试用例，包括标准TestOutcome和SimpleTestOutcome
        List<Object> allTests = new ArrayList<>();
        allTests.addAll(testOutcomes);
        allTests.addAll(simpleTestOutcomes);

        if (allTests.isEmpty()) {
            return "<div class=\"container\">\n" +
                "    <div class=\"row\">\n" +
                "        <div class=\"col-md-12\">\n" +
                "            <div class=\"panel panel-default\">\n" +
                "                <div class=\"panel-heading\">Summary Details</div>\n" +
                "                <div class=\"panel-body\">\n" +
                "                    <div class=\"alert alert-info\">\n" +
                "                        <strong>Notice!</strong> No test results found!\n" +
                "                    </div>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</div>";
        }

        details.append("<div class=\"container\">\n" +
                "    <div class=\"row\">\n" +
                "        <div class=\"col-md-12\">\n" +
                "            <div class=\"panel panel-default\">\n" +
                "                <div class=\"panel-heading\">Summary Details</div>\n" +
                "                <div class=\"panel-body\" id=\"test-details-container\">\n");

        // 按Feature分组
        Map<String, List<Object>> featureTests = new java.util.LinkedHashMap<>();
        Set<String> scenarioNames = new java.util.LinkedHashSet<>();
        for (Object test : allTests) {
            String featureName = getFeatureName(test);
            featureTests.computeIfAbsent(featureName, k -> new ArrayList<>()).add(test);
            
            // 收集所有Scenario名称
            if (test instanceof SimpleTestOutcome) {
                scenarioNames.add(((SimpleTestOutcome) test).getTitle());
            } else if (test instanceof TestOutcome) {
                scenarioNames.add(((TestOutcome) test).getTitle());
            }
        }



        // 添加筛选功能
        details.append("                    <div class=\"filter-section\">\n" +
                "                        <div class=\"filter-options\">\n" +
                "                            <div class=\"filter-group\">\n" +
                "                                <label for=\"feature-filter\">Feature Name:</label>\n" +
                "                                <select id=\"feature-filter\" class=\"filter-select\">\n" +
                "                                    <option value=\"\">All Features</option>\n");
        
        // 添加Feature下拉选项
        for (String featureName : featureTests.keySet()) {
            details.append("                                    <option value=\"").append(escapeHtml(featureName)).append("\">").append(escapeHtml(featureName)).append("</option>\n");
        }
        
        details.append("                                </select>\n" +
                "                            </div>\n" +
                "                            <div class=\"filter-group\">\n" +
                "                                <label for=\"scenario-filter\">Scenario Name:</label>\n" +
                "                                <select id=\"scenario-filter\" class=\"filter-select\">\n" +
                "                                    <option value=\"\">All Scenarios</option>\n");
        
        // 添加Scenario下拉选项
        for (String scenarioName : scenarioNames) {
            details.append("                                    <option value=\"").append(escapeHtml(scenarioName)).append("\">").append(escapeHtml(scenarioName)).append("</option>\n");
        }
        
        details.append("                                </select>\n" +
                "                            </div>\n" +
                "                            <div class=\"filter-group\">\n" +
                "                                <label for=\"result-filter\">Result:</label>\n" +
                "                                <select id=\"result-filter\" class=\"filter-select\">\n" +
                "                                    <option value=\"\">All Results</option>\n" +
                "                                    <option value=\"test-success\">Passed</option>\n" +
                "                                    <option value=\"test-failure\">Failed</option>\n" +
                "                                    <option value=\"test-error\">Failed with errors</option>\n" +
                "                                    <option value=\"test-pending\">Pending</option>\n" +
                "                                    <option value=\"test-skipped\">Skipped</option>\n" +
                "                                    <option value=\"test-ignored\">Ignored</option>\n" +
                "                                </select>\n" +
                "                            </div>\n" +
                "                            <div class=\"filter-group\">\n" +
                "                                <label style=\"display: flex; align-items: center; cursor: pointer;\">\n" +
                "                                    <input type=\"checkbox\" id=\"show-exceptions\" style=\"margin-right: 8px;\">\n" +
                "                                    Show All Exceptions\n" +
                "                                </label>\n" +
                "                            </div>\n" +
                "                            <div class=\"filter-buttons\">\n" +
                "                                <button id=\"clear-filters\" class=\"clear-filters-btn\">Clear Filters</button>\n" +
                "                                <button id=\"download-csv\" class=\"clear-filters-btn\">Download CSV</button>\n" +
                "                            </div>\n" +
                "                        </div>\n" +
                "                    </div>\n");

        // 创建简化的单行表格
        details.append("                    <div class=\"table-container\">\n" +
                "                        <table class=\"summary-table test-details-table\">\n" +
                "                            <thead>\n" +
                "                                <tr>\n" +
                "                                    <th>Feature Name</th>\n" +
                "                                    <th>Scenario Name</th>\n" +
                "                                    <th>Result</th>\n" +
                "                                    <th>Error Details</th>\n" +
                "                                </tr>\n" +
                "                            </thead>\n" +
                "                            <tbody class=\"test-cases\">\n");

        // 按Feature分组生成测试用例行
        for (Map.Entry<String, List<Object>> entry : featureTests.entrySet()) {
            String featureName = entry.getKey();
            List<Object> tests = entry.getValue();

            // 从映射中查找Feature的HTML链接（Scenario在Feature中定义，链接一定存在）
            String featureReportLink = featureToHtmlMap.get(featureName);

            // Debug log: Output Feature name and link
            LoggingConfigUtil.logInfoIfVerbose(logger, "Processing Feature: {}, Link: {}", featureName, featureReportLink);
            if (featureReportLink == null) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Feature link not found for: {}. Available features in map: {}", featureName, featureToHtmlMap.keySet());
            }

            boolean firstTestForFeature = true;
            
            // 为每个测试用例生成详情
            for (Object test : tests) {
                String testName, testResult, resultClass, errorMessage, errorDetails, durationText, testLink;
                long duration;
                TestResult result;
                
                if (test instanceof TestOutcome) {
                    TestOutcome testOutcome = (TestOutcome) test;
                    result = testOutcome.getResult();
                    testName = testOutcome.getTitle() != null ? testOutcome.getTitle() : "Unknown Test";
                    testResult = getResultDisplay(result);
                    resultClass = getResultClass(result);
                    errorMessage = testOutcome.getTestFailureMessage() != null ?
                        testOutcome.getTestFailureMessage() : "No exception";
                    errorDetails = testOutcome.getTestFailureCause() != null ?
                        testOutcome.getTestFailureCause().toString() : "No details";
                    duration = testOutcome.getDuration();
                    durationText = formatDuration(duration);
                    testLink = findHtmlFileForTest(testName, customReportDir);
                } else {
                    SimpleTestOutcome simpleTest = (SimpleTestOutcome) test;
                    result = simpleTest.getResult();
                    testName = simpleTest.getTitle() != null ? simpleTest.getTitle() : "Unknown Test";
                    testResult = getResultDisplay(result);
                    resultClass = getResultClass(result);
                    errorMessage = simpleTest.getTestFailureMessage() != null ?
                        simpleTest.getTestFailureMessage() : "No exception";
                    errorDetails = simpleTest.getTestFailureCause() != null ?
                        simpleTest.getTestFailureCause().toString() : "No details";
                    duration = simpleTest.getDuration();
                    durationText = formatDuration(duration);
                    testLink = simpleTest.getReport() != null ? simpleTest.getReport() : "report.html#" + testName.replace(" ", "_");
                }
                
                // 只有失败/错误/妥协等状态才显示异常信息按钮
                boolean showException = (result.equals(TestResult.FAILURE) ||
                                       result.equals(TestResult.ERROR) ||
                                       result.equals(TestResult.COMPROMISED));
                
                // 为同一Feature的第一个测试用例添加Feature链接，后续测试用例Feature单元格为空
                // Scenario在Feature中定义，Feature链接一定存在
                String featureCell;
                if (firstTestForFeature) {
                    featureCell = String.format("<a href=\"%s\" target=\"_blank\">%s</a>", featureReportLink, escapeHtml(featureName));
                } else {
                    featureCell = "";
                }
                
                if (showException) {
                    String buttonStyle = "background: none; border: none; color: #007bff; cursor: pointer; padding: 0; text-decoration: underline;";
                    String exceptionDivStyle = "display:none; margin-top: 8px; padding: 8px; background-color: #f8f9fa; border-left: 3px solid #dc3545; border-radius: 4px; max-width: 100%;";
                    String preStyle = "white-space: pre-wrap; word-wrap: break-word; margin: 0; font-size: 12px; color: #721c24;";
                    details.append(String.format(
                            "                            <tr class=\"test-row\" data-result=\"%s\" data-duration=\"%d\">\n" +
                            "                                <td%s>%s</td>\n" +
                            "                                <td><a href=\"%s\" target=\"_blank\">%s</a></td>\n" +
                            "                                <td class=\"%s\">%s</td>\n" +
                            "                                <td>\n" +
                            "                                    <button class=\"toggle-exception\" data-target=\"exception-%d\" style=\"%s\">\n" +
                            "                                        Show Exception\n" +
                            "                                    </button>\n" +
                            "                                    <div id=\"exception-%d\" class=\"exception-details\" style=\"%s\">\n" +
                            "                                        <pre style=\"%s\">%s</pre>\n" +
                            "                                    </div>\n" +
                            "                                </td>\n" +
                            "                            </tr>\n",
                            resultClass, duration, firstTestForFeature ? "" : " class=\"merged-cell\"", featureCell,
                            testLink, escapeHtml(testName), resultClass, testResult,
                            test.hashCode(), buttonStyle, test.hashCode(), exceptionDivStyle, preStyle, escapeHtml(errorDetails)));
                } else {
                    details.append(String.format(
                            "                            <tr class=\"test-row\" data-result=\"%s\" data-duration=\"%d\">\n" +
                            "                                <td%s>%s</td>\n" +
                            "                                <td><a href=\"%s\" target=\"_blank\">%s</a></td>\n" +
                            "                                <td class=\"%s\">%s</td>\n" +
                            "                                <td></td>\n" +
                            "                            </tr>\n",
                            resultClass, duration, firstTestForFeature ? "" : " class=\"merged-cell\"", featureCell,
                            testLink, escapeHtml(testName), resultClass, testResult));
                }
                
            // 标记已处理过第一个测试用例
                firstTestForFeature = false;
            }
        }

        // 在所有测试用例处理完成后关闭表格
        details.append("                        </tbody>\n" +
                    "                    </table>\n" +
                    "                </div>\n" +
                    "            </div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</div>");
        return details.toString();
    }
    
    /**
     * 获取测试的Feature名称
     */
    private String getFeatureName(Object test) {
        if (test instanceof SimpleTestOutcome) {
            return ((SimpleTestOutcome) test).getFeatureName();
        } else if (test instanceof TestOutcome) {
            return "Feature"; // TestOutcome可能不包含Feature信息
        }
        return "Unknown Feature";
    }
    
    /**
     * 获取测试结果
     */
    private TestResult getResult(Object test) {
        if (test instanceof SimpleTestOutcome) {
            return ((SimpleTestOutcome) test).getResult();
        } else if (test instanceof TestOutcome) {
            return ((TestOutcome) test).getResult();
        }
        return TestResult.UNDEFINED;
    }
    
    /**
     * 获取测试结果的显示文本
     */
    private String getResultDisplay(TestResult result) {
        switch (result) {
            case SUCCESS: return "Passed";
            case FAILURE: return "Failed";
            case ERROR: return "Failed with errors";
            case PENDING: return "Pending";
            case IGNORED: return "Ignored";
            case SKIPPED: return "Skipped";
            case COMPROMISED: return "Compromised";
            default: return "Unknown";
        }
    }
    
    /**
     * 获取测试结果对应的CSS类名
     */
    private String getResultClass(TestResult result) {
        switch (result) {
            case SUCCESS: return "test-success";
            case FAILURE: return "test-failure";
            case ERROR: return "test-error";
            case PENDING: return "test-pending";
            case IGNORED: return "test-ignored";
            case SKIPPED: return "test-skipped";
            case COMPROMISED: return "test-compromised";
            default: return "test-unknown";
        }
    }

    /**
     * 构建性能分析部分
     */
    private String buildPerformanceSection() {
        List<TestOutcome> slowTests = testOutcomes.stream()
            .filter(outcome -> outcome.getDuration() > 0)
            .sorted((a, b) -> Long.compare(b.getDuration(), a.getDuration()))
            .limit(5)
            .collect(Collectors.toList());

        if (slowTests.isEmpty()) {
            return "";
        }

        StringBuilder performance = new StringBuilder();
        performance.append(String.format(
                "<div class=\"section\">\n" +
                "    <h2 class=\"section-title\">Execution Time Analysis</h2>\n" +
                "    <p><strong>Slowest 5 Tests:</strong></p>\n"));

        for (TestOutcome test : slowTests) {
            performance.append(String.format(
                    "<div style=\"margin-bottom: 5px;\">\n" +
                    "    <strong>%s</strong>: %s\n" +
                    "</div>", 
                    escapeHtml(test.getTitle()), 
                    formatDuration(test.getDuration())));
        }

        performance.append("</div>");
        return performance.toString();
    }

    /**
     * 加载测试结果 - 尝试从多种文件格式加载，适配Serenity 4.1.3
     */
    private List<TestOutcome> loadTestOutcomes() {
        try {
            File reportDir = new File(customReportDir);
            if (!reportDir.exists()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Report directory does not exist: {}, creating it", customReportDir);
                reportDir.mkdirs();
            }

            // 尝试从多种文件格式加载数据
            List<TestOutcome> outcomes = new ArrayList<>();
            
            // 1. 首先尝试从.ser文件加载
            File[] serFiles = reportDir.listFiles((dir, name) -> name.endsWith(".ser"));
            if (serFiles != null && serFiles.length > 0) {
                outcomes.addAll(loadFromSerFiles(serFiles));
            }
            
            // 2. 如果没有.ser文件或结果为空，尝试从JSON文件加载
            if (outcomes.isEmpty()) {
                outcomes.addAll(loadFromJsonFiles(reportDir));
            }
            
            // 3. 如果仍然为空，尝试从其他可能的测试结果文件加载
            if (outcomes.isEmpty()) {
                loadFromOtherFiles(reportDir);
            }
            
            // 4. 如果仍然为空，创建一个示例TestOutcome
            if (outcomes.isEmpty()) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "No test outcomes found, creating a sample outcome for demonstration");
                // 这里我们不创建实际的TestOutcome，让报告显示空结果
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Loaded {} test outcomes from report directory", outcomes.size());
            return outcomes;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to load test outcomes", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 从.ser文件加载测试结果
     */
    private List<TestOutcome> loadFromSerFiles(File[] serFiles) {
        List<TestOutcome> outcomes = new ArrayList<>();
        
        for (File serFile : serFiles) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(serFile))) {
                Object obj = ois.readObject();
                if (obj instanceof TestOutcome) {
                    outcomes.add((TestOutcome) obj);
                } else if (obj instanceof List) {
                    for (Object item : (List<?>) obj) {
                        if (item instanceof TestOutcome) {
                            outcomes.add((TestOutcome) item);
                        }
                    }
                }
                } catch (Exception e) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to read .ser file: {}, error: {}", serFile.getName(), e.getMessage());
            }
        }
        
        return outcomes;
    }
    
    /**
     * 从JSON文件加载数据的降级方案
     */
    private List<TestOutcome> loadFromJsonFiles(File reportDir) {
        try {
            // 尝试从JSON文件加载测试结果
            List<TestOutcome> outcomes = new ArrayList<>();

            // 首先尝试从标准的serenity报告目录加载JSON文件
            File serenityDir = new File(reportDir, "serenity");
            if (!serenityDir.exists()) {
                // 如果reportDir已经是serenity目录，则直接使用
                serenityDir = reportDir;
            }

            // 查找JSON测试结果文件
            File[] jsonFiles = serenityDir.listFiles((dir, name) -> name.endsWith(".json"));

            if (jsonFiles != null && jsonFiles.length > 0) {
                LoggingConfigUtil.logInfoIfVerbose(logger, "Found {} JSON files in serenity directory: {}", jsonFiles.length, serenityDir.getAbsolutePath());

                // 直接解析JSON文件（优先使用，因为Serenity 4.1.3的API可能已改变）
                for (File jsonFile : jsonFiles) {
                    try {
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Attempting to parse JSON file: {}", jsonFile.getName());
                        parseJsonFileAndAddToSimpleOutcomes(jsonFile);
                    } catch (Exception ex) {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse JSON file: {} - {}", jsonFile.getName(), ex.getMessage());
                        ex.printStackTrace();
                    }
                }

                LoggingConfigUtil.logInfoIfVerbose(logger, "Loaded {} test outcomes by directly parsing JSON files", simpleTestOutcomes.size());
            } else {
                LoggingConfigUtil.logWarnIfVerbose(logger, "No JSON files found in serenity directory: {}", serenityDir.getAbsolutePath());
            }
            
            return outcomes;
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to load test outcomes from JSON files", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 直接解析JSON文件并添加到simpleTestOutcomes列表
     */
    private void parseJsonFileAndAddToSimpleOutcomes(File jsonFile) {
        try {
            // 读取JSON文件内容
            String jsonContent = new String(Files.readAllBytes(jsonFile.toPath()), "UTF-8");

            // 解析JSON内容
            JsonObject jsonObject = new Gson().fromJson(jsonContent, JsonObject.class);

            String name = jsonObject.has("name") ? jsonObject.get("name").getAsString() : "未知测试";
            String resultStr = jsonObject.has("result") ? jsonObject.get("result").getAsString() : "UNKNOWN";
            long duration = jsonObject.has("duration") ? jsonObject.get("duration").getAsLong() : 0;

            // 获取对应的HTML报告文件名
            String jsonFileName = jsonFile.getName();
            String htmlFileName = jsonFileName.replace(".json", ".html");

            // 获取Feature名称
            String featureName = null;
            if (jsonObject.has("userStory")) {
                try {
                    JsonObject userStory = jsonObject.getAsJsonObject("userStory");
                    if (userStory != null && userStory.has("storyName")) {
                        featureName = userStory.get("storyName").getAsString();
                    }
                    } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to extract featureName from userStory for: {}", jsonFile.getName());
                }
            }

            // 获取失败信息
            String failureMessage = null;
            Throwable failureCause = null;

            if (!"SUCCESS".equals(resultStr)) {
                // 获取testFailureMessage
                if (jsonObject.has("testFailureMessage")) {
                    try {
                        failureMessage = jsonObject.get("testFailureMessage").getAsString();
                    } catch (Exception e) {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to extract testFailureMessage as string for: {}", jsonFile.getName());
                        failureMessage = jsonObject.get("testFailureMessage").toString();
                    }
                }

                // 获取testFailureCause（可能是字符串或对象）
                if (jsonObject.has("testFailureCause")) {
                    try {
                        // 尝试作为字符串获取
                        failureCause = new RuntimeException(jsonObject.get("testFailureCause").getAsString());
                    } catch (Exception e) {
                        // 如果是对象，尝试提取message字段
                        try {
                            JsonObject causeObj = jsonObject.getAsJsonObject("testFailureCause");
                            if (causeObj != null && causeObj.has("message")) {
                                String errorMessage = causeObj.get("message").getAsString();
                                failureCause = new RuntimeException(errorMessage);
                            } else {
                                // 将整个对象转换为字符串
                                failureCause = new RuntimeException(causeObj.toString());
                            }
                        } catch (Exception ex) {
                            // 最后的备选方案：使用toString()
                            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to extract testFailureCause for: {}", jsonFile.getName());
                            failureCause = new RuntimeException(jsonObject.get("testFailureCause").toString());
                        }
                    }
                }
            }

            // 创建SimpleTestOutcome对象并添加到列表
            SimpleTestOutcome outcome = createSimpleTestOutcomeFromJson(name, resultStr, duration, failureMessage, failureCause, htmlFileName, featureName);
            simpleTestOutcomes.add(outcome);

            LoggingConfigUtil.logInfoIfVerbose(logger, "Successfully parsed test outcome: {} in feature: {} with result: {}", name, featureName, outcome.getResult());

        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse JSON file: {}", jsonFile.getName(), e);
        }
    }
    
    /**
     * 从JSON数据创建SimpleTestOutcome对象
     */
    private SimpleTestOutcome createSimpleTestOutcomeFromJson(String name, String resultStr, long duration, String failureMessage, Throwable failureCause, String htmlFileName, String featureName) {
        return new SimpleTestOutcome(name, resultStr, duration, failureMessage, failureCause, htmlFileName, featureName);
    }
    
    /**
     * TestOutcome的动态代理处理器，用于JSON数据
     */
    private static class JsonTestOutcomeHandler implements InvocationHandler {
        private final String name;
        private final String resultStr;
        private final long duration;
        private final String failureMessage;
        private final Throwable failureCause;
        private final String htmlFileName;
        private final TestResult result;
        
        public JsonTestOutcomeHandler(String name, String resultStr, long duration, String failureMessage, Throwable failureCause, String htmlFileName) {
            this.name = name;
            this.resultStr = resultStr;
            this.duration = duration;
            this.failureMessage = failureMessage;
            this.failureCause = failureCause;
            this.htmlFileName = htmlFileName;
            this.result = getTestResultFromString(resultStr);
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            switch (methodName) {
                case "getTitle":
                    return name;
                    
                case "getResult":
                    return result;
                    
                case "getDuration":
                    return duration;
                    
                case "getTestFailureMessage":
                    return failureMessage;
                    
                case "getTestFailureCause":
                    return failureCause;
                    
                case "getLink":
                    return htmlFileName;
                    
                default:
                    // 对于其他方法，返回默认值
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    } else if (method.getReturnType().equals(int.class)) {
                        return 0;
                    } else if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    } else if (method.getReturnType().equals(double.class)) {
                        return 0.0;
                    } else {
                        return null;
                    }
            }
        }
        
        /**
         * 从字符串解析测试结果
         */
        private TestResult getTestResultFromString(String resultStr) {
            switch (resultStr.toUpperCase()) {
                case "SUCCESS": return TestResult.SUCCESS;
                case "BROKEN": return TestResult.ERROR;
                case "FAILURE": return TestResult.FAILURE;
                case "PENDING": return TestResult.PENDING;
                case "IGNORED": 
                case "SKIPPED": return TestResult.IGNORED;
                case "COMPROMISED": return TestResult.COMPROMISED;
                default: return TestResult.UNDEFINED;
            }
        }
    }
    
    /**
     * 直接从JSON文件加载测试结果
     */
    private void loadTestResultsFromJsonFiles() {
        File reportDir = new File(customReportDir);
        File serenityDir = new File(reportDir, "serenity");
        
        if (!serenityDir.exists()) {
            serenityDir = reportDir;
        }
        
        File[] jsonFiles = serenityDir.listFiles((dir, name) -> name.endsWith(".json"));

        if (jsonFiles != null && jsonFiles.length > 0) {
            LoggingConfigUtil.logInfoIfVerbose(logger, "Found {} JSON files, attempting to parse directly", jsonFiles.length);

            for (File jsonFile : jsonFiles) {
                try {
                    parseJsonFileAndAddToSimpleOutcomes(jsonFile);
                } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse JSON file: {}", jsonFile.getName(), e);
                }
            }

            // 重新计算结果统计
            recalculateResultCounts();

            LoggingConfigUtil.logInfoIfVerbose(logger, "Successfully loaded {} test outcomes from JSON files", simpleTestOutcomes.size());
        }
    }
    
    /**
     * 尝试从其他可能的测试结果文件加载数据
     */
    private void loadFromOtherFiles(File reportDir) {
        try {
            // 尝试从index.html文件中解析测试结果
            File indexFile = new File(reportDir, "index.html");
            if (indexFile.exists()) {
                try {
                    String indexContent = new String(Files.readAllBytes(indexFile.toPath()), "UTF-8");
                    
                    // 解析测试结果
                    // 这里我们使用简单的字符串匹配来提取测试信息
                    // 在实际应用中，可能需要使用HTML解析器如Jsoup
                    String[] lines = indexContent.split("\n");
                    
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        
                        // 查找测试结果行的开始标记
                        if (line.contains("| TEST NAME:")) {
                            // 这是一个测试结果行，解析相关信息
                            String testName = extractValue(line, "TEST NAME:");
                            String result = extractValue(lines[i+1], "RESULT:");
                            String requirement = extractValue(lines[i+2], "REQUIREMENT:");
                            String report = extractValue(lines[i+3], "REPORT:");
                            
                            // 创建SimpleTestOutcome对象
                            SimpleTestOutcome outcome = createSimpleTestOutcomeFromData(testName, result, requirement, report);
                            if (outcome != null) {
                                simpleTestOutcomes.add(outcome);
                            }
                        }
                    }

                    LoggingConfigUtil.logInfoIfVerbose(logger, "Loaded {} test outcomes from index.html", simpleTestOutcomes.size());
                } catch (Exception e) {
                    LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse index.html", e);
                }
            }
            
            // 如果仍然没有找到测试结果，创建一个默认的TestOutcome
            if (simpleTestOutcomes.isEmpty()) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "No test outcomes found, creating a default outcome for demonstration");
                // 这里可以创建一个默认的TestOutcome用于演示
                // 在实际使用中，可能不需要这样做
            }

        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Failed to load test outcomes from other files", e);
        }
    }

    /**
     * 从index.html文件中提取Feature到HTML的映射
     */
    private void loadFeatureHtmlMapping() {
        try {
            File indexFile = new File(customReportDir, "index.html");

            // 如果 index.html 不存在，等待最多 10 秒让它生成
            int maxRetries = 10;
            int retryCount = 0;
            while (!indexFile.exists() && retryCount < maxRetries) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "index.html not found at {}, waiting... (attempt {}/{})",
                    indexFile.getAbsolutePath(), retryCount + 1, maxRetries);
                Thread.sleep(1000); // 等待 1 秒
                retryCount++;
            }

            if (!indexFile.exists()) {
                LoggingConfigUtil.logErrorIfVerbose(logger, "index.html still not found after {} attempts at {}, cannot load Feature mapping",
                    maxRetries, indexFile.getAbsolutePath());
                return;
            }

            String indexContent = new String(Files.readAllBytes(indexFile.toPath()), "UTF-8");
            LoggingConfigUtil.logInfoIfVerbose(logger, "Loaded index.html, size: {} bytes", indexContent.length());

            // 查找第一个Features表格（在"Functional Coverage Details"部分）
            int featuresHeaderStart = indexContent.indexOf("Functional Coverage Details");
            if (featuresHeaderStart == -1) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Functional Coverage Details section not found in index.html");
                return;
            }

            // 从Functional Coverage Details之后查找<h4>Features</h4>
            int featuresStart = indexContent.indexOf("<h4>Features</h4>", featuresHeaderStart);
            if (featuresStart == -1) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Features header not found after Functional Coverage Details");
                return;
            }

            // 查找Features表格
            int featureTableStart = indexContent.indexOf("<table", featuresStart);
            if (featureTableStart == -1) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Feature table not found after Features header");
                return;
            }

            int featureTableEnd = indexContent.indexOf("</table>", featureTableStart);
            if (featureTableEnd == -1) {
                LoggingConfigUtil.logWarnIfVerbose(logger, "Feature table end not found in index.html");
                return;
            }

            String featureTableSection = indexContent.substring(featureTableStart, featureTableEnd + 8);
            LoggingConfigUtil.logDebugIfVerbose(logger, "Extracted Feature table section, length: {} bytes", featureTableSection.length());

            // 使用正则表达式提取Feature和对应的HTML链接
            // 模式：匹配 <a href="xxx.html"> FeatureName </a>
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<a\\s+href=\"([^\"]+\\.html)\">\\s*([^<]+)\\s*</a>");
            java.util.regex.Matcher matcher = pattern.matcher(featureTableSection);

            int matchCount = 0;
            while (matcher.find()) {
                matchCount++;
                String htmlFile = matcher.group(1);
                String featureNameRaw = matcher.group(2);
                String featureName = featureNameRaw.trim();

                LoggingConfigUtil.logDebugIfVerbose(logger, "Regex matched - htmlFile: '{}', featureName raw: '{}', featureName trimmed: '{}'",
                    htmlFile, featureNameRaw, featureName);

                // 只处理以Feature名称（非错误/结果图标链接）作为链接文本的情况
                // 排除像 ##beforetable 这样的锚点链接
                if (!htmlFile.startsWith("#")) {
                    // 检查这个HTML文件是否存在
                    File featureFile = new File(customReportDir, htmlFile);
                    if (featureFile.exists()) {
                        // 既然HTML文件存在，就是有效的Feature报告
                        featureToHtmlMap.put(featureName, htmlFile);
                        LoggingConfigUtil.logInfoIfVerbose(logger, "Mapped Feature: '{}' -> {}", featureName, htmlFile);
                    } else {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Feature file does not exist: {}", htmlFile);
                    }
                } else {
LoggingConfigUtil.logDebugIfVerbose(logger, "Skipping match: htmlFile starts with '#'");
            }
            }

            LoggingConfigUtil.logInfoIfVerbose(logger, "Found {} total matches, loaded {} Feature mappings from index.html", matchCount, featureToHtmlMap.size());

            LoggingConfigUtil.logInfoIfVerbose(logger, "Loaded {} Feature mappings from index.html", featureToHtmlMap.size());
        } catch (Exception e) {
            LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to load Feature HTML mapping from index.html", e);
        }
    }

    /**
     * 从行中提取值
     */
    private String extractValue(String line, String key) {
        int keyIndex = line.indexOf(key);
        if (keyIndex >= 0) {
            String value = line.substring(keyIndex + key.length()).trim();
            // 移除可能的分隔符
            if (value.startsWith("|")) {
                value = value.substring(1).trim();
            }
            return value;
        }
        return "";
    }
    
    /**
     * 从解析的数据创建TestOutcome对象
     * 使用简单的数据结构，而不是TestOutcome
     */
    private class SimpleTestOutcome {
        private final String testName;
        private final TestResult result;
        private final String failureMessage;
        private final Throwable failureCause;
        private final long duration;
        private final String report;
        private final String featureName;

        // 从index.html解析时使用的构造函数
        public SimpleTestOutcome(String testName, String resultStr, String report) {
            this.testName = testName;
            this.result = getTestResultFromString(resultStr);
            this.report = report;
            this.featureName = "Unknown Feature";

            if (this.result == TestResult.SUCCESS) {
                this.failureMessage = null;
                this.failureCause = null;
            } else {
                this.failureMessage = "Test execution failed";
                this.failureCause = new RuntimeException("Test execution failed");
            }

            // 使用默认持续时间，实际应用中应该从报告中解析
            this.duration = 5000; // 默认5秒
        }

        // 从JSON文件解析时使用的构造函数
        public SimpleTestOutcome(String name, String resultStr, long duration, String failureMessage, Throwable failureCause, String htmlFileName, String featureName) {
            this.testName = name;
            this.result = getTestResultFromString(resultStr);
            this.duration = duration;
            this.failureMessage = failureMessage;
            this.failureCause = failureCause;
            this.report = htmlFileName;
            this.featureName = featureName != null ? featureName : "Unknown Feature";
        }
        
        /**
         * 从字符串解析测试结果
         */
        private TestResult getTestResultFromString(String resultStr) {
            switch (resultStr.toUpperCase()) {
                case "SUCCESS":
                case "PASSING":
                    return TestResult.SUCCESS;
                case "ERROR":
                case "BROKEN":
                    return TestResult.ERROR;
                case "FAILURE":
                case "FAIL":
                    return TestResult.FAILURE;
                case "PENDING":
                    return TestResult.PENDING;
                case "IGNORED":
                case "SKIPPED":
                    return TestResult.IGNORED;
                case "COMPROMISED":
                    return TestResult.COMPROMISED;
                default:
                    return TestResult.UNDEFINED;
            }
        }
        
        public String getTitle() {
            return testName;
        }
        
        public TestResult getResult() {
            return result;
        }
        
        public String getTestFailureMessage() {
            return failureMessage;
        }
        
        public Throwable getTestFailureCause() {
            return failureCause;
        }
        
        public long getDuration() {
            return duration;
        }
        
        public String getReport() {
            return report;
        }
        
        public String getFeatureName() {
            return featureName;
        }
    }
    
    /**
     * 根据测试名称查找对应的HTML报告文件
     */
    private String findHtmlFileForTest(String testName, String reportDir) {
        try {
            File serenityDir = new File(reportDir, "serenity");
            if (!serenityDir.exists()) {
                serenityDir = new File(reportDir);
            }
            
            // 首先尝试通过JSON文件查找对应的HTML文件
            File[] jsonFiles = serenityDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (jsonFiles != null) {
                for (File jsonFile : jsonFiles) {
                    try {
                        String jsonContent = new String(Files.readAllBytes(jsonFile.toPath()), "UTF-8");
                        JsonObject jsonObject = new Gson().fromJson(jsonContent, JsonObject.class);
                        
                        String jsonTestName = jsonObject.has("name") ? jsonObject.get("name").getAsString() : "";
                        if (testName.equals(jsonTestName)) {
                            // 找到匹配的JSON文件，返回对应的HTML文件名
                            String jsonFileName = jsonFile.getName();
                            return jsonFileName.replace(".json", ".html");
                        }
                    } catch (Exception e) {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to parse JSON file: {}", jsonFile.getName(), e);
                    }
                }
            }
            
            // 如果没有找到匹配的JSON文件，尝试直接查找HTML文件
            File[] htmlFiles = serenityDir.listFiles((dir, name) -> name.endsWith(".html"));
            if (htmlFiles != null) {
                for (File htmlFile : htmlFiles) {
                    try {
                        String htmlContent = new String(Files.readAllBytes(htmlFile.toPath()), "UTF-8");
                        if (htmlContent.contains("<title>" + testName + "</title>")) {
                            return htmlFile.getName();
                        }
                    } catch (Exception e) {
                        LoggingConfigUtil.logWarnIfVerbose(logger, "Failed to read HTML file: {}", htmlFile.getName(), e);
                    }
                }
            }
            
            // 如果仍然没有找到，返回默认格式
            return testName.replace(" ", "_") + ".html";
            
        } catch (Exception e) {
            LoggingConfigUtil.logErrorIfVerbose(logger, "Error finding HTML file for test: {}", testName, e);
            return testName.replace(" ", "_") + ".html";
        }
    }
    
    /**
     * 从解析的数据创建简单的测试结果对象
     */
    private SimpleTestOutcome createSimpleTestOutcomeFromData(String testName, String result, String requirement, String report) {
        return new SimpleTestOutcome(testName, result, report);
    }

    /**
     * 计算结果计数
     */
    private Map<TestResult, Long> calculateResultCounts() {
        Map<TestResult, Long> counts = new HashMap<>();
        
        // 处理标准TestOutcome
        testOutcomes.forEach(test -> {
            counts.merge(test.getResult(), 1L, Long::sum);
        });
        
        // 处理SimpleTestOutcome
        simpleTestOutcomes.forEach(test -> {
            counts.merge(test.getResult(), 1L, Long::sum);
        });
        
        return counts;
    }
    
    /**
     * 重新计算结果计数
     */
    private void recalculateResultCounts() {
        resultCounts.clear();
        resultCounts.putAll(calculateResultCounts());
    }

    /**
     * 计算总执行时间
     */
    private long calculateTotalDuration() {
        long total = 0;
        
        // 处理标准TestOutcome
        total += testOutcomes.stream()
            .mapToLong(TestOutcome::getDuration)
            .sum();
            
        // 处理SimpleTestOutcome
        total += simpleTestOutcomes.stream()
            .mapToLong(SimpleTestOutcome::getDuration)
            .sum();
            
        return total;
    }

    /**
     * 格式化持续时间
     */
    private String formatDuration(long milliseconds) {
        Duration duration = Duration.ofMillis(milliseconds);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        
        if (minutes > 0) {
            return String.format("%d min %d sec", minutes, seconds);
        } else {
            return String.format("%d sec", seconds);
        }
    }

    /**
     * HTML转义
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#x27;");
    }

    /**
     * 写入摘要文件
     */
    private void writeSummaryFile(String content) throws IOException {
        Path reportDir = Paths.get(customReportDir);
        if (!Files.exists(reportDir)) {
            Files.createDirectories(reportDir);
        }

        // 只生成 summary.html 文件
        Path summaryFile = reportDir.resolve(SUMMARY_FILE);
        Files.write(summaryFile, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    
    /**
     * 静态便利方法：生成邮件友好报告
     * 可在测试结束后直接调用
     */
    public static void generateEmailSummary() {
        new SummaryReportGenerator().generateSummaryReport();
    }
    
    /**
     * 静态便利方法：使用自定义报告目录
     */
    public static void generateEmailSummary(String reportDir) {
        new SummaryReportGenerator(reportDir).generateSummaryReport();
    }
    
    /**
     * 主方法 - 用于独立运行报告生成器
     */
    public static void main(String[] args) {
        try {
            String reportDir = args.length > 0 ? args[0] : REPORT_DIR;
            System.out.println("Generating email-friendly summary report to directory: " + reportDir);
            SummaryReportGenerator generator = new SummaryReportGenerator(reportDir);
            generator.generateSummaryReport();

            // 检查文件是否存在
            java.io.File reportFile = new java.io.File(reportDir, "summary.html");
            if (reportFile.exists()) {
                String reportPath = "file:///" + reportFile.getAbsolutePath().replace("\\", "/");
                System.out.println("Summary report file generated: " + reportPath);
                System.out.println("File size: " + reportFile.length() + " bytes");
            } else {
                System.out.println("Summary report file not found!");
            }

            System.out.println("Summary report generation completed!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}