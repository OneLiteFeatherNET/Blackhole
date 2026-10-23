# The Blackhole Discord bot: a plain Java application (JDA). It is stateless; it talks to the backend
# with the client credentials grant (Entra app role Blackhole.Discord) and to Discord.
#
# Build from the repository root (it needs the client spec and the Gradle build):
#   docker build -f discord/Dockerfile -t blackhole-discord .
#
# Run (all values are required unless noted; see discord/README.md):
#   docker run \
#     -e DISCORD_TOKEN=<bot token> -e DISCORD_GUILD_ID=<guild id> \
#     -e DISCORD_TEAM_CHANNEL_ID=<channel id> -e DISCORD_STAFF_ROLE_ID=<role id> \
#     -e BLACKHOLE_BACKEND_URL=http://backend:8080 -e BLACKHOLE_PANEL_URL=https://blackhole.example.org \
#     -e BLACKHOLE_DISCORD_TENANT_ID=<tenant> -e BLACKHOLE_DISCORD_CLIENT_ID=<bot client id> \
#     -e BLACKHOLE_DISCORD_CLIENT_SECRET=<secret> -e BLACKHOLE_DISCORD_AUTH_SCOPE=api://<api client id>/.default \
#     blackhole-discord

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY client ./client
COPY discord ./discord
# Only the bot and the generated client are built here; the other modules need nothing of this image.
RUN sed -i -E '/^include\("(backend|velocity|phoca|web)"\)/d' settings.gradle.kts \
 && ./gradlew --no-daemon :discord:shadowJar -x test

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 blackhole && useradd --system --uid 10001 --gid blackhole --no-create-home blackhole
WORKDIR /app
# Owned by root and only readable for the bot user, so the process cannot rewrite its own code.
COPY --from=build /src/discord/build/libs/blackhole-discord.jar /app/blackhole-discord.jar
USER 10001:10001
ENV DISCORD_HEALTH_PORT=8082
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD ["java", "-cp", "/app/blackhole-discord.jar", "net.onelitefeather.blackhole.discord.HealthCheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/blackhole-discord.jar"]
