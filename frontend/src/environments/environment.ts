// In production (docker-compose), the frontend is served by nginx which proxies
// /api to the backend service, so a relative path works in every environment.
export const environment = {
  apiBaseUrl: '/api'
};
