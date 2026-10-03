# Frontend image. Build context is the REPOSITORY ROOT:
#
#   docker build -f deployment/docker/frontend.Dockerfile -t vyoog-web .
#
# Stage 1: Build Stage
FROM node:22 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy package.json and package-lock.json
COPY frontend/package*.json ./

# Install dependencies
RUN npm install

# Copy the rest of the application code
COPY frontend/ .

ARG VITE_API_BASE=/api/v1
ARG VITE_KEYCLOAK_URL=https://user.evyoog.com
ARG VITE_KEYCLOAK_REALM=eVyoog
ARG VITE_KEYCLOAK_CLIENT_ID=vyoog-web

ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID

# Build the application for production
RUN npm run build

# Stage 2: Production Stage
# VYB-0909 (F33-F35): the unprivileged nginx build runs as uid 101 (user "nginx"), keeps its pid and
# temp files under /tmp, and cannot bind a port below 1024 — so the container listens on 8080, not 80.
# Anything that mapped port 80 to this image (a load balancer target, an ECS port mapping) must
# point at 8080.
FROM nginxinc/nginx-unprivileged:stable-alpine

# Copy the React build from the build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Copy custom Nginx configuration
COPY deployment/nginx/frontend.conf /etc/nginx/conf.d/default.conf

# Expose the port
EXPOSE 8080

# /healthz is answered by nginx itself (frontend.conf); it does not depend on the API being up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
