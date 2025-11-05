# =============================
# Stage 1: Build with Maven
# =============================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom.xml first to cache dependencies
COPY pom.xml ./

## Pre-download all dependencies (cached by mounted ~/.m2)
#RUN mvn dependency:go-offline -B

# Copy full source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# =============================
# Stage 2: Runtime image
# =============================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
