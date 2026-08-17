FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

# Keep the Maven build predictable on the small production VPS. The build still
# has enough heap for this project while avoiding an unbounded JVM next to the
# already running containers.
ENV MAVEN_OPTS="-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m"

COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home prodamus \
    && mkdir -p /app/logs \
    && chown -R prodamus:prodamus /app
COPY --from=build /workspace/target/prodamus-backend-*.jar /app/prodamus-backend.jar
USER prodamus
EXPOSE 8080

# Runtime memory is also capped by docker run. MaxRAMPercentage is calculated
# from that container limit, not from total host RAM.
ENTRYPOINT ["java","-XX:InitialRAMPercentage=15.0","-XX:MaxRAMPercentage=55.0","-XX:MaxMetaspaceSize=128m","-Dfile.encoding=UTF-8","-jar","/app/prodamus-backend.jar"]
