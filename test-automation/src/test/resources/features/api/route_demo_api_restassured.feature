@api
Feature: Route Demo Service API (RestAssured framework)

  # 每个场景前重置 demo 数据，保证用例相互独立
  # 运行方式: mvn verify -Dcucumber.filter.tags=@api （需先启动 route-demo-service）
  Background:
    Given an entity with "routedemo"
    And I set endpoint to "/api/reset"
    When I send POST request
    Then I should get response status code 200
    Given I clear request body

  Scenario: Get all users returns 3 seeded users
    Given I set endpoint to "/api/users"
    When I send GET request
    Then I should get response status code 200
    And response array "$" length is 3

  Scenario: Get user by id returns the correct user
    Given I set endpoint to "/api/users/1"
    When I send GET request
    Then I should get response status code 200
    And response field "name" should be "Alice"
    And response field "email" should be "alice@example.com"

  Scenario: Get non-existent user returns 404
    Given I set endpoint to "/api/users/9999"
    When I send GET request
    Then I should get response status code 404

  Scenario: Create user returns 200 with echoed body
    Given I set endpoint to "/api/users"
    And I load payload from file "api-demo-create-user.json"
    When I send POST request
    Then I should get response status code 200
    And response field "name" should be "Dave"
    And response field "email" should be "dave@example.com"

  Scenario: Update user returns 200 with updated fields
    Given I set endpoint to "/api/users/2"
    And I load payload from file "api-demo-update-user.json"
    When I send PUT request
    Then I should get response status code 200
    And response field "name" should be "Alice2"

  Scenario: Delete user returns 204
    Given I set endpoint to "/api/users/3"
    When I send DELETE request
    Then I should get response status code 204

  Scenario: Search users by role returns only matching users
    Given I set endpoint to "/api/search"
    And I add query parameter "role" with value "ADMIN"
    When I send GET request
    Then I should get response status code 200
    And response array "$" length is 1
    And response field "[0].name" should be "Bob"

  Scenario: Get user orders returns the user's orders
    Given I set endpoint to "/api/users/1/orders"
    When I send GET request
    Then I should get response status code 200
    And response array "$" length is 3

  Scenario: Login with valid credentials returns a token
    Given I set endpoint to "/api/auth/login"
    And I load payload from file "api-demo-login-valid.json"
    When I send POST request
    Then I should get response status code 200
    And response field "message" should be "Login successful"
    And response body should contain "token"

  Scenario: Login with invalid credentials returns 401
    Given I set endpoint to "/api/auth/login"
    And I load payload from file "api-demo-login-invalid.json"
    When I send POST request
    Then I should get response status code 401

  Scenario: Slow endpoint responds successfully
    Given I set endpoint to "/api/slow/endpoint"
    When I send GET request
    Then I should get response status code 200

  Scenario: Users list available after login
    Given I set endpoint to "/api/auth/login"
    And I load payload from file "api-demo-login-valid.json"
    When I send POST request
    Then I should get response status code 200
    Given I clear request body
    And I set endpoint to "/api/users"
    When I send GET request
    Then I should get response status code 200
