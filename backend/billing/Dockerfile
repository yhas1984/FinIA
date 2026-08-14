FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/install/finai-billing/ /app/
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/finai-billing"]
