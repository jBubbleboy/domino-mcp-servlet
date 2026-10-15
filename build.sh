#!/bin/bash
# Build script for Domino MCP Servlet (component of the Domino MCP Toolkit)
# Builds WAR file and fat JAR for deployment on HCL Domino servers

set -e

# Java 8 environment: use existing JAVA_HOME, otherwise system Java.
# The project targets Java 8 (maven.compiler.source/target=8); a
# JDK 8+ is required, Java 8 recommended.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
else
    if ! command -v java >/dev/null 2>&1; then
        echo "ERROR: java not found. Please set JAVA_HOME to a JDK 8+." >&2
        exit 1
    fi
fi

# Project directory
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "Domino MCP Servlet - Build"
echo "=========================================="
echo ""

# Check Java version
echo "Java Version:"
java -version
echo ""

# Clean previous build
echo "Cleaning previous build..."
mvn clean

# Build WAR + JAR
echo "Building WAR + JAR..."
mvn package -DskipTests

# Check result
if [ -f "target/domino-mcp-servlet.war" ]; then
    echo ""
    echo "=========================================="
    echo "BUILD SUCCESS!"
    echo "=========================================="
    echo ""
    echo "WAR file: target/domino-mcp-servlet.war"
    ls -lh target/domino-mcp-servlet.war
    echo ""
    echo "JAR file (alternative): target/domino-mcp-servlet.jar"
    ls -lh target/domino-mcp-servlet.jar
    echo ""
    echo "Next steps (WAR):"
    echo "1. Copy the WAR file into the server's Java classpath directory"
    echo "   (see server document, open in Notes client or Administration Client,"
    echo "   tab 'Internet Protocols' -> 'Domino Web Server' ->"
    echo "   section 'Java Servlets'), e.g. /opt/hcl/domino/data/servlet/:"
    echo "   scp target/domino-mcp-servlet.war domino-server:/opt/hcl/domino/data/servlet/"
    echo ""
    echo "Next steps (JAR):"
    echo "1. Copy the JAR + web.xml into the server's Java classpath directory"
    echo "   (see server document, open in Notes client or Administration Client,"
    echo "   tab 'Internet Protocols' -> 'Domino Web Server' ->"
    echo "   section 'Java Servlets'), e.g. /opt/hcl/domino/data/servlet/:"
    echo "   scp target/domino-mcp-servlet.jar src/main/webapp/WEB-INF/web.xml domino-server:/opt/hcl/domino/data/servlet/"
    echo "   (web.xml: servlet class DominoMcpServlet [default package] + init-param"
    echo "    mcpMaxPayloadBytes [default 10239999]; only ONE servlet definition"
    echo "    per server — WAR or JAR, not both)"
    echo ""
    echo "2. Configure the server document on the Domino server"
    echo "   (open in Notes client or Administration Client, tab 'Internet Protocols' ->"
    echo "   'Domino Web Server' -> section 'Java Servlets'):"
    echo "   - Enable Java servlet support"
    echo "   - Java servlet classpath: e.g. servlet (relative to the data directory,"
    echo "     the JAR/WAR must be placed in this directory; path freely configurable)"
    echo "   - Java servlet path: /servlet"
    echo ""
    echo "3. Restart the HTTP task:"
    echo "   tell http restart"
    echo ""
else
    echo ""
    echo "BUILD FAILED!"
    exit 1
fi
