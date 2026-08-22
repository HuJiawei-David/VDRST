# VDRST — no build stage, because there is nothing to resolve.
#
# v1's image would have needed Maven, a settings.xml, a warm dependency cache and a
# network at build time to fetch roughly forty jars. This project has no external
# dependencies, so the build is javac over the source tree and the runtime is a JRE.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY src ./src
RUN mkdir -p /out && \
    javac --release 21 --add-modules jdk.incubator.vector -encoding UTF-8 \
          -d /out $(find src/main/java -name '*.java')

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Runs unprivileged. v1 ran as root and shipped a database password in its jar.
RUN addgroup -S vdrst && adduser -S -G vdrst vdrst
COPY --from=build --chown=vdrst:vdrst /out ./classes
USER vdrst

EXPOSE 9090
ENV VDRST_PORT=9090

# --add-modules is required at run time as well: the Vector API is an incubating module.
ENTRYPOINT ["java", "--add-modules", "jdk.incubator.vector", \
            "-XX:+UseSerialGC", "-Xmx2g", \
            "-cp", "/app/classes", "vdrst.http.Main"]
CMD ["--db", "/data/viruses.fasta"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD wget -qO- http://localhost:9090/health || exit 1
