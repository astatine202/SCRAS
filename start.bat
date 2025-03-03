@echo off
start "SCRAS-backend" /D "backend" cmd /k "mvn spring-boot:run"
start "SCRAS-frontend" /D "frontend" cmd /k "npm run dev"