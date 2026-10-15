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
| `CDCI_REGISTRATION_ENABLED` | Habilita el registro público | `true` |
| `CDCI_BOOTSTRAP_ADMIN_EMAIL` / `CDCI_BOOTSTRAP_ADMIN_PASSWORD` | Credenciales del primer admin | Sin valor por defecto |
| `CDCI_COOKIE_SECURE` | Marca `Secure` para cookie de sesión | `false` en localhost; `true` en HTTPS |
| `CDCI_COOKIE_SAME_SITE` | Política SameSite de la cookie | `lax`; si front y API están en sitios distintos usa `none` junto a HTTPS y `Secure` |
| `CDCI_GITHUB_CLIENT_ID` / `CDCI_GITHUB_CLIENT_SECRET` | Credenciales de la GitHub App | Sin valores por defecto |
| `CDCI_GITHUB_CALLBACK_URL` | URL registrada como callback OAuth de la App | `http://localhost:8080/api/integrations/github/callback` |
| `CDCI_GITHUB_TOKEN_ENCRYPTION_KEY` | Clave base64 de 32 bytes para AES-256-GCM en tokens/refresh tokens persistidos | Sin valor por defecto |
| `CDCI_RUNNER_TOKEN` | Token aleatorio compartido entre el backend y el worker de ejecuciones | Sin valor por defecto |

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
  - push
  - pull_request
jobs:
  build:
    name: Build
    steps:
      - name: Install dependencies
        run: npm ci
      - name: Build
        run: npm run build
```

Por ahora se admiten `triggers` `push`, `pull_request` y `manual`; de 1 a 20 trabajos y de 1 a 50 pasos por trabajo. Los pasos usan `run` y texto YAML plano; no se admiten acciones `uses`, expresiones ni variables secretas. El validador limita la definición a 64 KB y rechaza claves duplicadas y etiquetas YAML de clases Java. La versión guarda el texto exacto y su hash SHA-256.

- `GET /api/projects/{projectId}/pipelines` lista definiciones.
- `POST /api/projects/{projectId}/pipelines/validate` valida `{ "yaml": "..." }` sin guardar.
- `POST /api/projects/{projectId}/pipelines` crea `{ "repositoryId": "...", "yaml": "..." }` como versión 1.
- `POST /api/projects/{projectId}/pipelines/{pipelineId}/versions` guarda una versión nueva.
- `GET /api/projects/{projectId}/pipelines/{pipelineId}/versions` y `.../versions/{number}` consultan historial y contenido.
- `DELETE /api/projects/{projectId}/pipelines/{pipelineId}` elimina definición e historial.

Las ejecuciones manuales persisten una solicitud `QUEUED` con snapshot de versión, rama y logs del sistema. El contrato del worker está expuesto en `/api/runner`: `POST /claim`, `POST /runs/{id}/logs` y `POST /runs/{id}/complete`. Usa `Authorization: Bearer <CDCI_RUNNER_TOKEN>`. La ruta de claim responde `204` si la cola está vacía; en caso contrario entrega la definición, el repositorio y el token GitHub de lectura al proceso worker autenticado. Ese token solo debe estar en memoria del worker confiable durante checkout; nunca se debe pasarlo al contenedor o al comando del pipeline. Expón el worker por una red privada con TLS y usa un secreto aleatorio distinto por entorno.

El backend no ejecuta los comandos: todavía falta el proceso runner que reclama trabajos, obtiene el código sin filtrar credenciales y los ejecuta aislados. Hasta conectar ese worker, las solicitudes manuales permanecen `QUEUED`. Los campos `triggers` siguen siendo declarativos y no inician ejecuciones automáticas.

Referencias oficiales: [autorización de usuario para GitHub Apps](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [permisos de lectura de repositorios](https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user), [renovar tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens) y [revocar una autorización](https://docs.github.com/en/rest/apps/oauth-applications#delete-an-app-authorization).
