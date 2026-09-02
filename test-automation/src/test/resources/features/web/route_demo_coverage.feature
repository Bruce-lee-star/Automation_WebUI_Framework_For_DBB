@route-coverage
Feature: Route DSL 方法 100% 覆盖（route-demo-web :8899）

  目标：覆盖 route-demo-service(:8888) 无法覆盖的全部 DSL 方法。
  前置：route-demo-web 已在 http://localhost:8899 启动
        （mvn -f route-demo-web/pom.xml spring-boot:run）。
  运行：mvn verify -Dcucumber.filter.tags=@route-coverage

  # ───────────────── Modify ─────────────────
  Scenario: setRequestHeaders(Map) 批量设置请求头并转发
    Given route coverage: modify setRequestHeaders map

  Scenario: modifyMethod 改写请求方法后转发
    Given route coverage: modifyMethod changes outgoing method

  # ───────────────── Mock ─────────────────
  Scenario: mockBodyFromFile 从文件读取响应体
    Given route coverage: mockBodyFromFile

  Scenario: mockBodyFromFile 读取后批量改字段
    Given route coverage: mockBodyFromFile with overrides

  Scenario: mockHeader 添加 mock 响应头
    Given route coverage: mockHeader

  Scenario: mockStatus 自定义状态码
    Given route coverage: mockStatus

  Scenario: replaceField 纯 mock 模式改字段
    Given route coverage: replaceField pure mock

  Scenario: replaceFields 纯 mock 模式批量改字段
    Given route coverage: replaceFields pure mock

  # ───────────────── Delay ─────────────────
  Scenario: randomDelay 随机延迟生效
    Given route coverage: randomDelay

  # ───────────────── times ─────────────────
  Scenario: times(1) 仅拦截一次后放行
    Given route coverage: times one shot

  # ───────────────── 条件匹配 ─────────────────
  Scenario: matchMethod 仅匹配指定方法
    Given route coverage: matchMethod

  Scenario: resourceType(image) 仅匹配图片资源
    Given route coverage: resourceType image

  Scenario: resourceType(script) 仅匹配脚本资源
    Given route coverage: resourceType script

  Scenario: onlyXhr 仅匹配 XHR 请求
    Given route coverage: onlyXhr

  Scenario: onlyFetch 仅匹配 fetch 请求
    Given route coverage: onlyFetch

  Scenario: onlyApi 仅匹配 xhr+fetch
    Given route coverage: onlyApi

  Scenario: matchHeader 按请求头精确匹配
    Given route coverage: matchHeader

  Scenario: matchQuery 按查询参数精确匹配
    Given route coverage: matchQuery

  Scenario: matchBodyRegex 按请求体正则匹配
    Given route coverage: matchBodyRegex

  Scenario: matchContentType 按请求 Content-Type 匹配
    Given route coverage: matchContentType

  Scenario: matchReferrer 按 referer 匹配
    Given route coverage: matchReferrer

  Scenario: matchOrigin 按 origin 匹配
    Given route coverage: matchOrigin

  Scenario: matchFrameUrl 按 frame URL 匹配
    Given route coverage: matchFrameUrl

  Scenario: onlyMainFrame 仅匹配主 frame 请求
    Given route coverage: onlyMainFrame

  Scenario: allowAllFrames 匹配 iframe 内请求
    Given route coverage: allowAllFrames

  Scenario: onlyApiCall 跳过导航、只认 api
    Given route coverage: onlyApiCall

  Scenario: allowAllRequests 匹配所有资源类型
    Given route coverage: allowAllRequests

  # ───────────────── Monitor ─────────────────
  Scenario: Monitor 捕获的响应体主线程可读（替代 onResponse 共享变量）
    Given route coverage: monitor response body readable from main thread
