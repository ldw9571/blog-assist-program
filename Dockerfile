# ── 1단계: 빌드 ──
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# ── 2단계: 실행 ──
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul \
    DATA_DIR=/data \
    THYMELEAF_CACHE=true \
    JAVA_OPTS="-Xmx512m"
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# 회원 DB와 분양 자료가 저장되는 곳 → 호스팅에서 '볼륨(유지되는 디스크)'을 /data 에 연결할 것
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
