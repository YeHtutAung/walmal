# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY walmal-common/pom.xml walmal-common/pom.xml
COPY walmal-infrastructure/pom.xml walmal-infrastructure/pom.xml
COPY walmal-auth/pom.xml walmal-auth/pom.xml
COPY walmal-product/pom.xml walmal-product/pom.xml
COPY walmal-inventory/pom.xml walmal-inventory/pom.xml
COPY walmal-order/pom.xml walmal-order/pom.xml
COPY walmal-pos/pom.xml walmal-pos/pom.xml
COPY walmal-warehouse/pom.xml walmal-warehouse/pom.xml
COPY walmal-notification/pom.xml walmal-notification/pom.xml
COPY walmal-app/pom.xml walmal-app/pom.xml
RUN chmod +x mvnw && sed -i 's/\r//' mvnw && ./mvnw dependency:go-offline -B
COPY . .
RUN chmod +x mvnw && sed -i 's/\r//' mvnw && ./mvnw package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/walmal-app/target/walmal-app-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
