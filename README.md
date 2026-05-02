# vicevice (Spring Boot + React)

Local-first wardrobe app.

## What works right now (MVP)
- Upload 1+ clothing item photos at once
- Backend stores the image locally in `./data/images`
- Backend calls Ollama `api/chat` with the image to get **JSON** (category/colors/tags)
- Saves results into **SQLite** (`./data/vicevice.db`)
- Frontend shows the closet list

## Prerequisites
- **JDK 21** installed
- **Node.js + npm** installed
- **Ollama** running locally (daemon on `http://localhost:11434`)
  - Default model: `gemma4:31b-cloud`
  - To use another model, set `OLLAMA_MODEL` before starting the backend.
- Maven does **not** need to be installed globally; the backend includes Maven Wrapper scripts.

## Run the backend
From `backend/`:

```powershell
# Use JDK 21 for this shell session (adjust if yours differs)
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\mvnw.cmd spring-boot:run
```

Backend runs on `http://127.0.0.1:8080`.

Optional Ollama overrides:

```powershell
$env:OLLAMA_MODEL="gemma4:31b-cloud"
$env:OLLAMA_BASE_URL="http://localhost:11434"
$env:OLLAMA_CONNECT_TIMEOUT="5s"
$env:OLLAMA_READ_TIMEOUT="120s"
```

## Run the frontend
From `frontend/`:

```powershell
npm install
npm run dev
```

Frontend runs on `http://localhost:5173`.

## Notes
- The React dev server is allowed by CORS in `backend/src/main/java/.../config/WebConfig.java`.
- Ollama configuration is in `backend/src/main/resources/application.yml` under `app.ollama.*`.
- The default `gemma4:31b-cloud` model may send photos or closet metadata outside your machine. Set `OLLAMA_MODEL` to a local model if you want stricter local-first privacy.

