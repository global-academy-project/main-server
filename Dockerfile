# syntax=docker/dockerfile:1

# Original version (reference only; not used by Docker)
# FROM eclipse-temurin:17-jdk-resolute AS build
# # FROM eclipse-temurin:17-jdk AS build
#
# WORKDIR /app
#
# COPY . .
#
# RUN echo "Access authorization for the Gradle wrapper of the backend application"
# # Linux에서 Gradle 래퍼에 실행 권한 부여 [001 : --x]
# RUN chmod +x gradlew
#
# RUN echo "Making Gradle wrapper executable"
# RUN ./gradlew bootJar --no-daemon
#
# #---------------------------------------------------
# # Multi-stage build: final image
# FROM eclipse-temurin:17-jre-resolute
# # FROM eclipse-temurin:17-jre
#
# RUN echo "Setting working directory for the final image"
# WORKDIR /app
#
# RUN echo "Copying the built JAR from the build stage"
# COPY --from=build /app/build/libs/*.jar app.jar
#
# EXPOSE 60001
#
# # ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=60001"]

# Optimized version (active)
FROM eclipse-temurin:17-jdk-resolute AS build
# FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY gradlew ./
COPY gradle/ ./gradle/
COPY settings.gradle build.gradle ./

RUN echo "Access authorization for the Gradle wrapper of the backend application"
# Linux에서 Gradle 래퍼에 실행 권한 부여 [001 : --x]
RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
	./gradlew --no-daemon dependencies

COPY src/ ./src/

RUN echo "Making Gradle wrapper executable"
# RUN ./gradlew bootJar --no-daemon
# 캐싱을 활성화한 상태에서 Gradle 래퍼를 사용하여 애플리케이션을 빌드한다.
RUN --mount=type=cache,target=/root/.gradle \
	./gradlew --build-cache bootJar --no-daemon

#-------------------------[Multi-stage build final image]---------------------------------------
FROM eclipse-temurin:17-jre-resolute
RUN echo "Setting working directory for the final image"

WORKDIR /app

RUN echo "Copying the built JAR from the build stage"
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 60001

# ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=60001"]
CMD ["java", "-jar", "app.jar", "--server.port=60001"]