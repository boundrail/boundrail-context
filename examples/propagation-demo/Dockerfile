# Builds the demo with the wrapper, so the host needs only Docker. One image serves every container. The context is the repository root.
FROM eclipse-temurin:25-jdk

WORKDIR /repository
COPY . .

# Only Demo 1's test: the other demo tests need Docker. Running it here puts Gradle and the dependencies in the image, for the run without network.
RUN ./gradlew --no-daemon :examples:propagation-demo:installDist :examples:propagation-demo:test --tests '*ContextLossDemoTest'

ENV CLASSPATH="examples/propagation-demo/build/install/propagation-demo/lib/*"
