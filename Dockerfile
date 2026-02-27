# =============================================================================
# Stage 1: Build
# =============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Bağımlılıkları önce indir (layer caching için)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Kaynak kodu kopyala ve paketle
COPY src ./src
RUN mvn package -DskipTests -B -q

# =============================================================================
# Stage 2: Runtime
# =============================================================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Güvenlik: root olmayan kullanıcı
RUN addgroup -S quantshine && adduser -S quantshine -G quantshine
USER quantshine

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
