FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src/ /app/src/
COPY public/ /app/public/
RUN javac src/*.java
EXPOSE 9090 8081