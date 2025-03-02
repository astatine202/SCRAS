@echo off
start "SCRAS-frontend" /D "frontend" cmd /k "npm run dev"
start "SCRAS-backend" /D "backend" cmd /k "mvn spring-boot:run"
