# CDCI Backend

API de CDCI con Spring Boot 4, Java 21, PostgreSQL, Spring Security y sesiones persistidas.

## Inicio local

1. Copia `.env.example` como `.env` y configura un correo y contraseña de administrador propios.
2. Inicia PostgreSQL con `docker compose up -d`.
3. Exporta esas variables al entorno de la terminal y arranca `gradlew bootRun` (en PowerShell: `./gradlew.bat bootRun`).
4. Configura el frontend en `cdci_front/.env.local` con `NEXT_PUBLIC_API_URL=http://localhost:8080`.

La aplicación crea el esquema mediante Flyway y carga los roles de sistema al iniciar. Solo crea la cuenta inicial si se definen `CDCI_BOOTSTRAP_ADMIN_EMAIL` y `CDCI_BOOTSTRAP_ADMIN_PASSWORD`; no hay una contraseña admin integrada. La contraseña inicial debe tener entre 12 y 128 caracteres. El correo no vuelve a recibir privilegios si ya existe.

## Configuración

| Variable | Uso | Valor local |
|---|---|---|
| `CDCI_DATABASE_URL` | JDBC de PostgreSQL | `jdbc:postgresql://localhost:5432/cdci` |
| `CDCI_DATABASE_USERNAME` / `CDCI_DATABASE_PASSWORD` | Usuario y contraseña de base de datos | `cdci` / `cdci_dev_only` |
| `CDCI_FRONTEND_ORIGIN` | Origen CORS permitido, explícito | `http://localhost:3000` |
| `CDCI_REGISTRATION_ENABLED` | Habilita el registro; el default de aplicación es cerrado, el ejemplo local lo activa | `false` en despliegues; `true` en `.env.example` |
| `CDCI_BOOTSTRAP_ADMIN_EMAIL` / `CDCI_BOOTSTRAP_ADMIN_PASSWORD` | Credenciales del primer admin | Sin valor por defecto |
| `CDCI_COOKIE_SECURE` | Marca `Secure` para cookie de sesión | `false` en localhost; `true` en HTTPS |
| `CDCI_COOKIE_SAME_SITE` | Política SameSite de la cookie | `lax`; si front y API están en sitios distintos usa `none` junto a HTTPS y `Secure` |
| `CDCI_GITHUB_CLIENT_ID` / `CDCI_GITHUB_CLIENT_SECRET` | Credenciales de la GitHub App | Sin valores por defecto |
| `CDCI_GITHUB_CALLBACK_URL` | URL registrada como callback OAuth de la App | `http://localhost:8080/api/integrations/github/callback` |
| `CDCI_GITHUB_TOKEN_ENCRYPTION_KEY` | Clave base64 de 32 bytes para AES-256-GCM en tokens/refresh tokens persistidos | Sin valor por defecto |
| `CDCI_SECRET_ENCRYPTION_KEY` | Clave base64 independiente de 32 bytes para secretos de pipeline cifrados con AES-256-GCM | Sin valor por defecto |
| `CDCI_RUNNER_TOKEN` | Token aleatorio compartido entre el backend y el worker de ejecuciones | Sin valor por defecto |
| `CDCI_RUN_RETENTION_DAYS` | Retención de runs terminales y sus logs en PostgreSQL | `90` días |
| `CDCI_RUNNER_DEFAULT_IMAGE` | Imagen por defecto para trabajos sin `image` | `node:22-bookworm-slim` |
| `CDCI_RUNNER_STEP_TIMEOUT_SECONDS` / `CDCI_RUNNER_RUN_TIMEOUT_SECONDS` | Límite por paso / por ejecución | `1800` / `21600` segundos |
| `CDCI_RUNNER_MEMORY_MB` / `CDCI_RUNNER_CPU_COUNT` / `CDCI_RUNNER_PIDS_LIMIT` | Límites de recursos de cada contenedor de trabajo | `2048` / `1` / `256` |

Genera `CDCI_RUNNER_TOKEN` con un secreto aleatorio distinto por ambiente. En PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. Define el mismo valor en el proceso del backend y en `cdci_back/.env`; no lo guardes en Git.

Genera `CDCI_SECRET_ENCRYPTION_KEY` con el mismo comando de PowerShell, pero usa una clave diferente de la clave GitHub y conserva un respaldo seguro de ella. Si se pierde o rota sin migrar/re-cifrar los datos, los secretos existentes no podrán descifrarse. La API solo permite administrar metadatos y nunca devuelve valores en claro.

Las contraseñas se guardan con Argon2id. Spring Security administra la cookie de sesión `HttpOnly`; las rutas mutables requieren el token CSRF entregado por `GET /api/auth/csrf`. No guardes la cookie de sesión en almacenamiento web. En despliegues, usa secretos gestionados para credenciales de base de datos y bootstrap, TLS, orígenes CORS exactos y cookie `Secure`.

## API de identidad

- `GET /api/auth/csrf` prepara y devuelve el token CSRF.
- `POST /api/auth/register` crea una cuenta nueva con rol básico de lectura.
- `POST /api/auth/login` recibe `application/x-www-form-urlencoded` con `email` y `password`.
- `GET /api/auth/me` devuelve el perfil de la sesión activa.
- `POST /api/auth/logout` invalida la sesión actual.
- `PUT /api/auth/password` valida la contraseña actual, cambia la contraseña y cierra las otras sesiones.
## Roles y permisos

Al iniciar, CDCI crea `ADMIN`, `MAINTAINER`, `DEVELOPER` y `VIEWER` con un catálogo explícito de permisos. Los roles de sistema son de solo lectura; un administrador puede crear roles personalizados, asignarles permisos y conceder esos roles a cuentas existentes. Una cuenta nueva solo recibe `VIEWER`.

- `GET /api/admin/users` lista cuentas paginadas y admite búsqueda por nombre/correo.
- `PATCH /api/admin/users/{id}/status` habilita o suspende una cuenta.
- `PUT /api/admin/users/{id}/roles` reemplaza sus roles.
- `/api/admin/roles` permite consultar y administrar roles personalizados.
- `GET /api/admin/permissions` devuelve el catálogo de permisos.
- `GET /api/admin/audit` lista cambios de acceso.

Cada operación administrativa comprueba permisos en el servidor. Los cambios de estado, roles y permisos invalidan las sesiones afectadas; el sistema impide suspender o degradar al último administrador activo.

El backend es la autoridad para identidad y autorización; ocultar acciones en la UI no sustituye la validación de permisos del servidor.

## Proyectos y GitHub

La API permite crear/editar/archivar proyectos, administrar miembros con acceso `OWNER`, `MAINTAINER`, `DEVELOPER` o `VIEWER`, y vincular repositorios GitHub. Solo se pueden agregar cuentas CDCI existentes. Las rutas `/api/projects` verifican membresía y nivel de acceso en el servidor; los cambios se registran en la bitácora de auditoría.

La primera integración usa una **GitHub App** y tokens de usuario con permisos de lectura de metadatos y contenidos. No uses un personal access token. Configúrala así:

1. Registra la App y agrega `CDCI_GITHUB_CALLBACK_URL` como callback del flujo web OAuth. Activa tokens de usuario expirables y autoriza `offline_access` para permitir renovación.
2. Concede permisos de repositorio `Metadata: read-only` y `Contents: read-only`; instala la App en la cuenta u organización y repositorios que se usarán.
3. Copia su Client ID y Client Secret al entorno del backend. El Client ID es el de la App; no es el App ID.
4. Genera `CDCI_GITHUB_TOKEN_ENCRYPTION_KEY` con PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. Guárdala en el gestor de secretos del entorno. La misma clave debe mantenerse para poder leer conexiones existentes; perderla requiere reconectar GitHub.
5. Conecta desde `/projects`. Desvincula todos los repositorios antes de desconectar la cuenta; CDCI revoca el grant de GitHub al hacerlo.

El callback externo conserva la sesión CDCI del navegador. En producción, registra exactamente la URL HTTPS publicada y configura cookie `Secure`; si el frontend y la API están en sitios distintos, usa `SameSite=None` y HTTPS.

## Pipelines YAML

Cada pipeline pertenece a un proyecto y a uno de sus repositorios conectados. CDCI valida la definición antes de guardarla y conserva cada cambio como una versión inmutable en PostgreSQL. El borrado de un pipeline elimina también su historial; un repositorio no se puede desconectar mientras tenga pipelines asociados.

Formato inicial soportado:

```yaml
name: Build frontend
triggers:
  - manual
jobs:
  build:
    name: Build
    image: node:22-bookworm-slim
    network: bridge
    steps:
      - name: Install dependencies
        run: npm ci
      - name: Build
        run: npm run build
```

En v1 se admite únicamente `triggers: [manual]`; los triggers `push` y `pull_request` fallan la validación. Hay de 1 a 20 trabajos y de 1 a 50 pasos por trabajo. Los trabajos se ejecutan en el orden declarado y aceptan `image`, `network: none|bridge` y una lista opcional `secrets`; sin imagen usan la predeterminada. `bridge` habilita acceso de salida a Internet sin una lista de destinos permitidos; úsalo solo cuando el trabajo necesite descargar dependencias. Los pasos usan `run` y texto YAML plano; no se admiten acciones `uses` ni expresiones. El validador limita la definición a 64 KB y rechaza claves duplicadas y etiquetas YAML de clases Java. La versión guarda el texto exacto y su hash SHA-256.

Los mantenedores del proyecto pueden crear, reemplazar y eliminar secretos en **Proyecto → Secretos de CI**. Se guardan cifrados con AES-256-GCM y una clave dedicada. La lista solo expone nombre, fecha y actor. Un job debe declarar, por ejemplo, `secrets: [NPM_TOKEN]`; el worker resuelve el valor al reclamar el run y solo pasa al contenedor el conjunto declarado. Configura cada nombre en el proyecto antes de encolar el run. Los valores se enmascaran en la salida del worker, incluidos secretos que crucen fragmentos de log, pero un job puede transformar o codificar una credencial: nunca imprimas, registres ni compartas secretos en la salida. Usa workers dedicados y código confiable.

- `GET /api/projects/{projectId}/pipelines` lista definiciones.
- `POST /api/projects/{projectId}/pipelines/validate` valida `{ "yaml": "..." }` sin guardar.
- `POST /api/projects/{projectId}/pipelines` crea `{ "repositoryId": "...", "yaml": "..." }` como versión 1.
- `POST /api/projects/{projectId}/pipelines/{pipelineId}/versions` guarda una versión nueva.
- `GET /api/projects/{projectId}/pipelines/{pipelineId}/versions` y `.../versions/{number}` consultan historial y contenido.
- `DELETE /api/projects/{projectId}/pipelines/{pipelineId}` elimina definición e historial.

Las ejecuciones manuales persisten una solicitud `QUEUED` con snapshot de versión, rama y logs del sistema. El contrato del worker está expuesto en `/api/runner`: `POST /claim`, `POST /runs/{id}/logs`, `POST /runs/{id}/heartbeat` y `POST /runs/{id}/complete`. Usa `Authorization: Bearer <CDCI_RUNNER_TOKEN>`. Claim responde `204` si la cola está vacía; si hay trabajo entrega la definición, el repositorio y el token GitHub de lectura al worker autenticado. Ese token solo se usa durante el checkout y nunca se monta ni se pasa al contenedor o al comando del pipeline. Leases renuevan el worker activo y cierran como fallidas las ejecuciones cuyo worker deja de reportar.

Para desarrollo, configura el mismo `CDCI_RUNNER_TOKEN` para el backend y `cdci_back/.env`, luego inicia el worker: `docker compose --profile runner up --build -d runner`. El perfil levanta un daemon Docker-in-Docker privado para jobs efímeros; cada paso corre como UID no root, sin capacidades Linux, con filesystem base de solo lectura, límite de memoria/CPU/PIDs y timeout. La red del job es `none` por defecto; `bridge` permite egress irrestricto. Usa esta modalidad en una máquina Linux dedicada al runner, no junto a servicios de producción: ejecutar código de repositorio sigue siendo una frontera de seguridad. El perfil no publica puertos del daemon Docker. En producción usa `CDCI_RUNNER_API_URL` con HTTPS/TLS; el valor `host.docker.internal` es para desarrollo local.

Los campos `triggers` permiten solo ejecuciones manuales. No se suben artefactos y todavía no se implementa un destino de despliegue. Los runs terminales y sus logs se purgan después de 90 días.

Referencias oficiales: [autorización de usuario para GitHub Apps](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [permisos de lectura de repositorios](https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user), [renovar tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens) y [revocar una autorización](https://docs.github.com/en/rest/apps/oauth-applications#delete-an-app-authorization).
