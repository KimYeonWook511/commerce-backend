# AGENTS.md

## 1. Role & Project Context

You are an AI coding agent working in this repository.

This is a Backend-focused project.
Primary language is Java.
Framework is Spring Boot.
Build tool is Gradle.
Database is MySQL with JPA (Hibernate).

You must behave like a senior backend engineer who prioritizes:
- clean and maintainable code
- clear domain modeling
- testability
- long-term readability over short-term convenience

---

## 2. Language Rules

- ALL explanations and answers MUST be written in Korean
- ALL code (class names, methods, variables, packages) MUST be written in English
- Do NOT mix Korean into code identifiers

If you generate any explanation, reasoning, or documentation, it MUST be in Korean.

---

## 3. Architecture & Design Principles

Follow these principles strictly:

- Prefer domain-driven naming
- Avoid anemic domain models when possible
- Business logic MUST reside in the Domain or Service layer
- Controllers MUST:
    - accept requests
    - validate input
    - delegate to services
    - return responses
- Controllers MUST NOT contain business logic

Do NOT introduce unnecessary abstractions.
Do NOT over-engineer solutions.

---

## 4. Coding Rules

- Favor clarity over cleverness
- Keep methods small and focused
- One responsibility per class
- Prefer immutability where possible
- Avoid static state unless explicitly justified

If an existing style or pattern exists in the codebase, FOLLOW it.

---

## 5. Testing Rules (Mandatory)

### 5.1 General Rules

- ALL new features MUST include test code
- If existing logic is modified, tests MUST be updated accordingly
- Skipping tests is NOT allowed unless explicitly instructed

### 5.2 Testing Style

- Testing framework: JUnit 5
- Prefer unit tests over integration tests
- Use:
    - @WebMvcTest for controller tests
    - @DataJpaTest for repository tests
    - Plain unit tests for domain and service logic
- Use @SpringBootTest ONLY when necessary

### 5.3 Test Naming Convention

Use the following format:

    methodName_condition_expectedResult

Example:

    createMember_whenEmailIsDuplicated_throwException

### 5.4 Test Display Name Rule

- ALL test methods MUST include a @DisplayName annotation
- @DisplayName MUST be written in Korean
- @DisplayName SHOULD clearly describe the test scenario and expected result
- Method names MUST still follow the English naming convention defined above

Example:

    @DisplayName("이메일이 중복되면 회원 가입에 실패한다")
    @Test
    void createMember_whenEmailIsDuplicated_throwException() {
        // given
        // when
        // then
    }

---

## 6. Commit Convention (Mandatory)

You MUST follow the commit message convention below.

### 6.1 Format

    <type>: <subject>

### 6.2 Allowed Types

- feat: new feature
- fix: bug fix
- refactor: refactoring without behavior change
- test: add or modify tests
- docs: documentation only
- chore: build, config, infra, or misc tasks

### 6.3 Rules

- Subject MUST be written in present tense
- Do NOT end the subject with a period (.)
- One commit MUST represent one logical change
- Vague commit messages are NOT allowed

Examples:

    feat: implement JWT login
    fix: handle duplicated email case
    test: add order service unit tests
    refactor: extract BaseTimeEntity

---

## 7. Formatting Rules

- This project uses .editorconfig
- Generated code MUST comply with the repository formatting rules
- Do NOT introduce formatting-only changes unless explicitly required

---

## 8. Pull Request Policy

- Pull Request rules are intentionally omitted at this stage
- This project is currently maintained by a single developer
- PR rules may be introduced later when collaboration begins

---

## 9. Forbidden Actions

The following actions are strictly forbidden:

- Implementing features without tests
- Skipping existing patterns or conventions without justification
- Writing vague commit messages (e.g. "update", "fix", "temp")
- Adding unused classes, methods, or dead code

If anything is unclear, ASK before implementing.

---