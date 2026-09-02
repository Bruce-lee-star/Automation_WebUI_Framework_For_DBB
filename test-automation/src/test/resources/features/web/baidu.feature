Feature: Baidu search - reproduce Element Not Found after switchNewPage

  # Scenario1: 搜索 → waitForPopup 捕获新 tab → switchToPage 切换 → 等待
  @baidu1
  Scenario: baidu - search playwright and open result in new tab
    When open the baidu site
    And search "playwright" keywords
    And ctrl-click the first search result
    And wait for 3 seconds

  # Scenario2: 再次导航到百度 → 搜索 → 这里如果报 Element Not Found in Dom 即为 Bug
  @baidu1
  Scenario: baidu - navigate again and search selenium
    When open the baidu site
    And search "selenium" keywords
    And ctrl-click the first search result
    And wait for 3 seconds
