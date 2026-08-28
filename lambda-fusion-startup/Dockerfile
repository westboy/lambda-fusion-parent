FROM eclipse-temurin:21-jre-noble

ARG APP_JAR=target/lambda-fusion-startup-*-executable.jar

RUN groupadd --system --gid 10001 lambda-fusion \
    && useradd --system --uid 10001 --gid lambda-fusion --home-dir /opt/lambda-fusion lambda-fusion \
    && mkdir -p /opt/lambda-fusion/data \
    && chown -R lambda-fusion:lambda-fusion /opt/lambda-fusion

WORKDIR /opt/lambda-fusion

COPY --chown=lambda-fusion:lambda-fusion ${APP_JAR} application.jar

USER lambda-fusion

EXPOSE 20005

ENTRYPOINT ["java", "-jar", "application.jar"]
