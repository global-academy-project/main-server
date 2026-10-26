# Build stage for the backend application
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY . .

RUN echo "Access authorization for the Gradle wrapper of the backend application"
# Access authorization for the Gradle wrapper of [001 : --x]
RUN chmod +x gradlew

RUN echo "Making Gradle wrapper executable"
RUN ./gradlew bootJar --no-daemon

#---------------------------------------------------
# Multi-stage build: final image

FROM eclipse-temurin:17-jre

RUN echo "Setting working directory for the final image"
WORKDIR /app

RUN echo "Copying the built JAR from the build stage"
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 60001

# ENTRYPOINT ["java", "-jar", "app.jar", "--server.port=60001"]
CMD ["java", "-jar", "app.jar", "--server.port=60001"]