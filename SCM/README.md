# 부조뱅크 SCM

관리자와 매장 계정을 분리한 Servlet + JSP/JSTL 기반 SCM입니다. MariaDB에 상품·매장·계정·발주·입출고·재고 이력을 저장합니다.

- [실행 방법, 계정 권한, 업무 정책](docs/02.화면구현.md)
- [요구사항](docs/01.Readme.md)
- [DB 설정 양식](docs/scm.properties.example)

현재 PC 미리보기: http://localhost:8087/scm/app/login

관리자 아이디: `admin`. 초기 비밀번호는 Git에서 제외된 `.local/initial-admin.txt`에 있습니다. 로그인 후 비밀번호를 변경하세요.

```powershell
$env:JAVA_HOME = 'C:\DevTools\java\openlogic-openjdk-17.0.14+7-windows-x64'
& C:\DevTools\apache-maven-3.9.16\bin\mvn.cmd -B package
powershell -ExecutionPolicy Bypass -File scripts/start-preview.ps1
```

DB 접속정보는 `.local/scm.properties`에서 읽으며 WAR에 포함되지 않습니다. 다른 환경에서는 `SCM_CONFIG` 또는 `SCM_DB_URL`/`SCM_DB_USER`/`SCM_DB_PASSWORD` 환경변수를 지정합니다.



