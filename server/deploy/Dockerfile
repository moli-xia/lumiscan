FROM eclipse-temurin:21-jre-noble

RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin lumiscan \
    && mkdir -p /app /data && chown lumiscan /data

WORKDIR /app
COPY lumiscan-server.jar /app/lumiscan-server.jar

USER lumiscan
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/lumiscan-server.jar"]
