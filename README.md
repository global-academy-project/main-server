# Main API Server

Spring Boot 기반의 현장 안전관리 API입니다. 사용자와 현장 권한, 작업 및
안전 기록, 결재, 알림, 감사 보고서 기능을 제공하고 웹 애플리케이션과 AI
서비스가 사용하는 API를 함께 운영합니다.

## 기술 스택

- Java 17
- Spring Boot 4
- Gradle Wrapper
- MySQL 및 Flyway
- Spring Security, JWT, Springdoc OpenAPI

## 로컬 개발

Java 17과 MySQL이 필요합니다. 기본 로컬 DB 연결값은
`src/main/resources/application.yaml`에 정의되어 있습니다
(`localhost:3307`, 데이터베이스 `ocrdb`). 다른 DB를 사용할 경우
`MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`,
`MYSQL_PASSWORD` 환경 변수로 설정을 덮어씁니다.

JWT 서명 키를 지정한 후 서버를 실행합니다.

```powershell
$env:JWT_SECRET = "<local-only-secret>"
.\gradlew.bat bootRun
```

로컬 서버의 기본 주소는 `http://localhost:8080`입니다. 메일은 기본적으로
실제 발송 대신 서버 로그에 링크를 기록합니다. Docker 환경에서는 애플리케이션
포트 `60001`을 사용합니다.

## 빌드와 테스트

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

## API 확인

- Springdoc UI: `/swagger-ui/index.html`
- OpenAPI 문서: `/v3/api-docs`
- 상태 확인: `/actuator/health`
- IntelliJ HTTP Client 예제: [`http/`](http/)

## 데이터베이스 마이그레이션

Flyway가 `src/main/resources/db/migration/`의 버전별 SQL을 적용합니다.
적용된 마이그레이션 파일을 수정하기보다 새 버전 파일을 추가하세요.

## 주요 디렉터리

| 경로 | 내용 |
|---|---|
| `src/main/java/` | API, 인증, 업무 도메인과 서비스 |
| `src/main/resources/` | 애플리케이션 설정, Flyway 및 화면/추출 데이터 |
| `src/test/` | 자동화 테스트 |
| `http/` | API 수동 호출 예제 |

비밀번호, JWT 키, 내부 API 토큰, 메일 자격 증명은 로컬 환경 변수로 관리하고
저장소에 커밋하지 마세요.
