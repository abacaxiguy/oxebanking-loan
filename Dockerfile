# Use the official Clojure image with Leiningen
FROM clojure:openjdk-17-lein

# Set the working directory
WORKDIR /app

# Copy the project.clj file and download dependencies
COPY project.clj /app/
RUN lein deps

# Copy the rest of the source code
COPY src /app/src

# Build the application
RUN lein uberjar

# Expose the application port
EXPOSE 3000

# Run the application
CMD ["java", "-jar", "target/oxebanking-loan.jar"]
