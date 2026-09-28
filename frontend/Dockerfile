# Stage 1: Build Stage
FROM node:22 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy package.json and package-lock.json
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy the rest of the application code
COPY . .

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
FROM nginx:stable-alpine

# Copy the React build from the build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Copy custom Nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose the port
EXPOSE 80

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
