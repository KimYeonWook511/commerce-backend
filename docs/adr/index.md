<!-- This file is the homepage of your Log4brains knowledge base. You are free to edit it as you want -->

# Architecture knowledge base

Welcome 👋 to the architecture knowledge base of commerce-backend.
You will find here all the Architecture Decision Records (ADR) of the project.

## Definition and purpose

> An Architectural Decision (AD) is a software design choice that addresses a functional or non-functional requirement that is architecturally significant.
> An Architectural Decision Record (ADR) captures a single AD, such as often done when writing personal notes or meeting minutes; the collection of ADRs created and maintained in a project constitutes its decision log.

An ADR is immutable: only its status can change (i.e., become deprecated or superseded). That way, you can become familiar with the whole project history just by reading its decision log in chronological order.
Moreover, maintaining this documentation aims at:

- 🚀 Improving and speeding up the onboarding of a new team member
- 🔭 Avoiding blind acceptance/reversal of a past decision (cf [Michael Nygard's famous article on ADRs](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions.html))
- 🤝 Formalizing the decision process of the team

## Usage

ADR은 코드 옆의 마크다운 파일(`docs/adr/`)로 관리한다. `log4brains preview`로 검색·타임라인 뷰를 로컬에서 볼 수 있다.
작성 규칙은 [`docs/adr-conventions.md`](../adr-conventions.md)를 따른다.

2026-07 이전의 결정들은 단일 파일(`docs/adr.md`)에서 이 체계로 마이그레이션됐다. 구 번호(ADR-NNN)와 파일의 매핑은 [`README.md`](README.md)를 참고한다.

## More information

- [Log4brains documentation](https://github.com/thomvaill/log4brains/tree/develop#readme)
- [What is an ADR and why should you use them](https://github.com/thomvaill/log4brains/tree/develop#-what-is-an-adr-and-why-should-you-use-them)
- [ADR GitHub organization](https://adr.github.io/)
