# Dockerfile
# Containerizes DraftFlow VCS using an ultra-slim OpenJDK 24 runtime environment.

FROM openjdk:24-slim
WORKDIR /repo

# Copy the compiled executable fat JAR
COPY target/draftflow.jar /app/draftflow.jar

# Define default repository home directory
ENV DRAFTFLOW_HOME=/repo

# Set entry point to run draftflow CLI
ENTRYPOINT ["java", "-jar", "/app/draftflow.jar"]
