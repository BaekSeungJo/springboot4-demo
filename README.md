# 해당 프로젝트는 Spring Boot 4 / Spring Framework 7 학습 목적으로 생성했습니다.
---

- branch 기준
  - main : 항상 실행 가능한 브랜치로 누적 가능한 내용은 누적한다 
  - feature 
    - 하나의 주제만 학습 
    - 완성되면 main에 머지 -> 태그로 고정 

- 학습 완료 -> main에 머지 

- ```git checkout main
  git checkout main
  git merge feat/001-http-service-clients
  ```

- 머지 후 "학습 체크포인트" 태그 생성

- ```
  git tag -a 001-http-service-clients -m "http-service-clients 학습"
  git push origin 001-http-service-clients
  ```



---

# Learning Index

- feat/001-http-service-clients
- feat/002-api-versioning

