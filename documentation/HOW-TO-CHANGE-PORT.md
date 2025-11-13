# How to Change the Backend Port

## Quick Guide

The backend currently runs on **port 8081** (changed from default 8080).

## Method 1: Edit Configuration File (Permanent - Recommended)

### For Local Development

Edit `src/main/resources/application-local.yml`:

```yaml
server:
  port: 8081  # Change this to your desired port
```

### For Production

Edit `src/main/resources/application.yml`:

```yaml
server:
  port: 8081  # Change this to your desired port
```

### For Specific Profiles

You can set different ports for each profile:

**application-local.yml** (Development)
```yaml
server:
  port: 8081
```

**application-dev.yml** (Dev Server)
```yaml
server:
  port: 8080
```

**application-prod.yml** (Production)
```yaml
server:
  port: 9090
```

## Method 2: Command Line Argument (Temporary)

### When running with Maven:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

### When running with Maven and profile:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local --server.port=8081"
```

### When running the JAR directly:

```bash
java -jar target/econet-leads-backend-0.0.1-SNAPSHOT.jar --server.port=8081
```

### With profile:

```bash
java -jar target/econet-leads-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=8081
```

## Method 3: Environment Variable (Docker/Production)

### Set environment variable:

```bash
export SERVER_PORT=8081
mvn spring-boot:run
```

### In Docker:

```dockerfile
ENV SERVER_PORT=8081
```

Or with docker-compose:

```yaml
environment:
  - SERVER_PORT=8081
```

## Method 4: System Property (JVM)

```bash
mvn spring-boot:run -Dserver.port=8081
```

Or when running JAR:

```bash
java -Dserver.port=8081 -jar target/econet-leads-backend-0.0.1-SNAPSHOT.jar
```

## Checking Which Port is in Use

### Check if a port is already in use:

```bash
# macOS/Linux
lsof -ti:8080

# If something is using it, kill it:
kill $(lsof -ti:8080)

# Or kill all Java processes:
pkill -f "spring-boot:run"
```

### Check what process is using a port:

```bash
lsof -ti:8080 | xargs ps -p
```

### List all listening ports:

```bash
lsof -i -P | grep LISTEN
```

## Testing the New Port

After changing the port:

```bash
# Test health endpoint
curl http://localhost:8081/actuator/health

# Should return: {"status":"UP"}

# Test login endpoint
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

## Common Ports

- **8080** - Default Spring Boot port (often conflicts with other apps)
- **8081** - Common alternative (current setup)
- **8082-8089** - Other common development ports
- **9090** - Common production port
- **3000** - Frontend port (React/Vite)
- **5173** - Vite default dev server port

## Update Frontend Configuration

After changing the backend port, update the frontend API base URL:

**File**: `frontend/src/services/api.ts`

```typescript
const apiClient = axios.create({
  baseURL: 'http://localhost:8081/api',  // Update this
  // ...
});
```

Or use environment variables:

**File**: `frontend/.env.local`

```
VITE_API_URL=http://localhost:8081
```

Then in code:

```typescript
baseURL: import.meta.env.VITE_API_URL + '/api'
```

## Priority Order

Spring Boot resolves port configuration in this order (highest to lowest):

1. Command line arguments (`--server.port=8081`)
2. System properties (`-Dserver.port=8081`)
3. Environment variables (`SERVER_PORT=8081`)
4. Profile-specific config (`application-local.yml`)
5. Main config file (`application.yml`)
6. Default (8080)

## Troubleshooting

### Port still in use after killing process

```bash
# Wait a few seconds for the port to be released
sleep 5

# Or force kill
kill -9 $(lsof -ti:8080)
```

### Backend starts but shows wrong port

Check that you're running with the correct profile:

```bash
# Should show: --spring.profiles.active=local
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"
```

### Frontend can't connect to backend

1. Check backend is running: `curl http://localhost:8081/actuator/health`
2. Check CORS configuration in `SecurityConfig.java`
3. Check frontend API base URL
4. Check browser console for CORS errors

## Current Setup

- **Backend Port**: 8081
- **Frontend Port**: 5173 (Vite dev server)
- **Redis Port**: 6379
- **PostgreSQL Port**: 5432

## Quick Commands Reference

```bash
# Start backend on port 8081 (local profile)
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=local"

# Start backend on custom port temporarily
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9000"

# Check what's on port 8081
lsof -ti:8081

# Kill backend
pkill -f "spring-boot:run"

# Test backend
curl http://localhost:8081/actuator/health
```
