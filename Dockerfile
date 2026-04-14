FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn ./.mvn
COPY mvnw ./mvnw
COPY pom.xml .
RUN chmod +x ./mvnw
RUN ./mvnw -B -ntp -DskipTests dependency:go-offline
COPY src ./src
RUN ./mvnw -B -ntp -e -Dmaven.test.skip=true clean package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/notification-system-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
