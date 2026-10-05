#!/bin/bash
# Build-Skript für Domino MCP Servlet (Komponente des Domino MCP Toolkit)
# Erstellt WAR-File und fat JAR für Deployment auf HCL Domino Server

set -e

# Java 8 Umgebung: vorhandenes JAVA_HOME nutzen, sonst System-Java.
# Das Projekt targetet Java 8 (maven.compiler.source/target=8); ein
# JDK 8+ ist erforderlich, empfohlen ist Java 8.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
else
    if ! command -v java >/dev/null 2>&1; then
        echo "FEHLER: java nicht gefunden. Bitte JAVA_HOME auf eine JDK 8+ setzen." >&2
        exit 1
    fi
fi

# Projektverzeichnis
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
    echo "WAR-File: target/domino-mcp-servlet.war"
    ls -lh target/domino-mcp-servlet.war
    echo ""
    echo "JAR-File (Alternative): target/domino-mcp-servlet.jar"
    ls -lh target/domino-mcp-servlet.jar
    echo ""
    echo "Nächste Schritte (WAR):"
    echo "1. WAR-File auf Domino Server kopieren:"
    echo "   scp target/domino-mcp-servlet.war domino-server:/opt/hcl/domino/data/java/"
    echo ""
    echo "Nächste Schritte (JAR):"
    echo "1. JAR + web.xml auf Domino Server kopieren:"
    echo "   scp target/domino-mcp-servlet.jar src/main/webapp/WEB-INF/web.xml domino-server:/opt/hcl/domino/data/java/"
    echo "   (web.xml: Servlet-Klasse DominoMcpServlet [Default-Package] + Init-Param"
    echo "    mcpMaxPayloadBytes [Default 10239999]; nur EINE Servlet-Definition je"
    echo "    Server — WAR oder JAR, nicht beides)"
    echo ""
    echo "2. notes.ini auf Domino Server konfigurieren:"
    echo "   HTTPJavaEnabled=1"
    echo "   HTTPClassPath=/opt/hcl/domino/data/java"
    echo ""
    echo "3. HTTP Task neu starten:"
    echo "   tell http restart"
    echo ""
else
    echo ""
    echo "BUILD FAILED!"
    exit 1
fi
