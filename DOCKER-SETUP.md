# Docker Setup for Local Development

This guide explains how to run Redis (and optionally PostgreSQL) using Docker for local development.

## Quick Start

### Using Docker Compose (Recommended)

Start all services:
```bash
docker-compose up -d
```

Stop all services:
```bash
docker-compose down
```

View logs:
```bash
docker-compose logs -f
```

Check status:
```bash
docker-compose ps
```

### Using Docker CLI Directly

If you prefer to manage containers individually:

**Redis:**
```bash
# Start
docker run -d --name econet-redis -p 6379:6379 --restart unless-stopped redis:7-alpine

# Stop
docker stop econet-redis

# Remove
docker rm econet-redis

# View logs
docker logs -f econet-redis
```

**PostgreSQL (Optional):**
```bash
# Start
docker run -d --name econet-postgres \
  -e POSTGRES_DB=econet_leads_dev \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  --restart unless-stopped \
  postgres:15-alpine

# Stop
docker stop econet-postgres

# Remove
docker rm econet-postgres
```

## Current Setup

Currently running:
- **Redis**: Running in Docker on port 6379
- **PostgreSQL**: Running locally (not in Docker)

## Switching to Fully Dockerized Setup

If you want to run PostgreSQL in Docker too:

1. Edit `docker-compose.yml` and uncomment the PostgreSQL service
2. Stop your local PostgreSQL:
   ```bash
   brew services stop postgresql@15
   ```
3. Start Docker services:
   ```bash
   docker-compose up -d
   ```
4. Your Spring Boot app will automatically connect to the Dockerized PostgreSQL

## Useful Commands

**Clean up everything (removes data!):**
```bash
docker-compose down -v
```

**Restart services:**
```bash
docker-compose restart
```

**View Redis CLI:**
```bash
docker exec -it econet-redis redis-cli
```

**View PostgreSQL CLI:**
```bash
docker exec -it econet-postgres psql -U postgres -d econet_leads_dev
```

## Troubleshooting

**Port already in use:**
```bash
# Find what's using the port
lsof -ti:6379  # For Redis
lsof -ti:5432  # For PostgreSQL

# Kill the process
kill $(lsof -ti:6379)
```

**Check container health:**
```bash
docker ps
docker inspect econet-redis
```

**View container logs:**
```bash
docker logs econet-redis
docker logs econet-postgres
```

## Benefits of Docker Setup

1. **Isolation**: Services run in containers, don't pollute your system
2. **Easy cleanup**: Remove containers and volumes with one command
3. **Consistent**: Same setup across all developers
4. **Version control**: Lock specific versions (Redis 7, PostgreSQL 15)
5. **Quick reset**: Delete volumes and start fresh anytime

## Environment Variables

Spring Boot automatically connects to:
- Redis: `localhost:6379`
- PostgreSQL: `localhost:5432`

No configuration changes needed in `application-local.yml`.
