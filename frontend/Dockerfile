# Build the Angular console, then serve it from nginx, which also proxies API calls to the CBRMS backend.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:stable-alpine
# nginx.conf is a template; only CBRMS_* variables are substituted, so nginx's own $variables are untouched.
ENV CBRMS_AUTH_ORIGIN="" \
    NGINX_ENVSUBST_FILTER="^CBRMS_"
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
