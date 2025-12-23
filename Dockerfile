# Java 21 런타임(JRE) 기반 이미지 사용
FROM eclipse-temurin:21-jre

# 컨테이너 내부 작업 디렉토리 설정
WORKDIR /app

# 빌드된 JAR 파일을 컨테이너로 복사
COPY build/libs/*.jar app.jar

# 컨테이너 실행 시 Java 애플리케이션 실행
ENTRYPOINT ["java","-jar","app.jar"]