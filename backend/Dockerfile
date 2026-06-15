# ---- 빌드 단계 ----
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon

# ---- 실행 단계 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# 빌드 산출물 복사
COPY --from=build /app/build/libs/*.jar app.jar
# H2 파일 DB가 저장될 디렉터리 (볼륨으로 영속화 권장)
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
