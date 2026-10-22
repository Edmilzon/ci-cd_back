# CDCI Backend

API de CDCI con Spring Boot 4, Java 21, PostgreSQL, Spring Security y sesiones persistidas.

## Inicio local

1. Copia `.env.example` como `.env` y configura un correo y contraseña de administrador propios.
2. Inicia PostgreSQL con `docker compose up -d`.
3. Exporta esas variables al entorno de la terminal y arranca `gradlew bootRun` (en PowerShell: `./gradlew.bat bootRun`).
4. Configura el frontend en `cdci_front/.env.local` con `NEXT_PUBLIC_API_URL=http://localhost:8080`.

La aplicación crea el esquema mediante Flyway y carga los roles de sistema al iniciar. Solo crea la cuenta inicial si se definen `CDCI_BOOTSTRAP_ADMIN_EMAIL` y `CDCI_BOOTSTRAP_ADMIN_PASSWORD`; no hay una contraseña admin integrada. La contraseña inicial debe tener entre 12 y 128 caracteres. El correo no vuelve a recibir privilegios si ya existe.

## Configuración

Para publicar releases manuales por GitHub Actions en `staging` y `production`, configurar los Environments y preparar rollback automático, sigue el [runbook de releases](ops/RELEASE.md).

| Variable | Uso | Valor local |
|---|---|---|
| `CDCI_DATABASE_URL` | JDBC de PostgreSQL | `jdbc:postgresql://localhost:5432/cdci` |
| `CDCI_DATABASE_USERNAME` / `CDCI_DATABASE_PASSWORD` | Usuario y contraseña de base de datos | `cdci` / `cdci_dev_only` |
| `CDCI_FRONTEND_ORIGIN` | Origen CORS permitido, explícito | `http://localhost:3000` |
| `CDCI_REGISTRATION_ENABLED` | Habilita el registro; al activarlo, MFA se exige por defecto y se valida al iniciar la aplicación | `false`; abrirlo solo después de preparar correo y MFA |
| `CDCI_MFA_REQUIRED` | Exige un segundo factor a todas las cuentas al iniciar sesión | Hereda `CDCI_REGISTRATION_ENABLED`; fuerza `true` para exigirlo antes de abrir el registro |
| `CDCI_MFA_ENCRYPTION_KEY` | Clave Base64 independiente de 32 bytes para cifrar secretos TOTP con AES-256-GCM | Sin valor; obligatoria cuando MFA es requerida |
| `CDCI_ABSOLUTE_SESSION_LIFETIME` | Límite absoluto de una sesión además del vencimiento por inactividad | `12h` |
| `CDCI_EMAIL_ENABLED` | Activa el envío de correos de recuperación | `false`; requiere configurar SMTP |
| `CDCI_EMAIL_VERIFICATION_REQUIRED` | Impide iniciar sesión hasta verificar el correo de cuentas nuevas | `false` local; activar junto con correo y SMTP antes de abrir registro público |
| `CDCI_EMAIL_FROM` | Remitente de correo de CDCI | `no-reply@cdci.local` local |
| `CDCI_SMTP_HOST` / `CDCI_SMTP_PORT` | Servidor SMTP para recuperación | `localhost:1025` |
| `CDCI_SMTP_USERNAME` / `CDCI_SMTP_PASSWORD` | Credenciales SMTP cuando el servidor exige autenticación | Sin valor por defecto |
| `CDCI_SMTP_AUTH` / `CDCI_SMTP_STARTTLS` | Activa autenticación y STARTTLS según el proveedor | `false` local; activar según SMTP |
| `CDCI_PASSWORD_RESET_TTL_MINUTES` | Vigencia del enlace de recuperación (acotada de 5 a 60 minutos) | `20` |
| `CDCI_IDENTITY_RATE_LIMIT_HMAC_KEY` | Clave HMAC de al menos 32 bytes para guardar límites sin correos ni IPs en claro | Obligatoria; usa una clave aleatoria distinta por ambiente |
| `CDCI_BOOTSTRAP_ADMIN_EMAIL` / `CDCI_BOOTSTRAP_ADMIN_PASSWORD` | Credenciales del primer admin | Sin valor por defecto |
| `CDCI_COOKIE_SECURE` | Marca `Secure` para cookie de sesión | `false` en localhost; `true` en HTTPS |
| `CDCI_COOKIE_SAME_SITE` | Política SameSite de la cookie | `lax`; si front y API están en sitios distintos usa `none` junto a HTTPS y `Secure` |
| `CDCI_GITHUB_CLIENT_ID` / `CDCI_GITHUB_CLIENT_SECRET` | Credenciales de la GitHub App | Sin valores por defecto |
| `CDCI_GITHUB_CALLBACK_URL` | URL registrada como callback OAuth de la App | `http://localhost:8080/api/integrations/github/callback` |
| `CDCI_GITHUB_TOKEN_ENCRYPTION_KEY` | Clave base64 de 32 bytes para AES-256-GCM en tokens/refresh tokens persistidos | Sin valor por defecto |
| `CDCI_SECRET_ENCRYPTION_KEY` | Clave base64 independiente de 32 bytes para secretos de pipeline cifrados con AES-256-GCM | Sin valor por defecto |
| `CDCI_RUNNER_TOKEN` | Token aleatorio compartido entre el backend y el worker de ejecuciones | Sin valor por defecto |
| `CDCI_RUN_RETENTION_DAYS` | Retención de runs terminales y sus logs en PostgreSQL | `90` días |
| `CDCI_AUDIT_RETENTION_DAYS` | Retención de eventos de auditoría antes de purgarlos | `365` días |
| `CDCI_RUNNER_DEFAULT_IMAGE` | Imagen por defecto para trabajos sin `image` | `node:22-bookworm-slim` |
| `CDCI_RUNNER_STEP_TIMEOUT_SECONDS` / `CDCI_RUNNER_RUN_TIMEOUT_SECONDS` | Límite por paso / por ejecución | `1800` / `21600` segundos |
| `CDCI_RUNNER_MEMORY_MB` / `CDCI_RUNNER_CPU_COUNT` / `CDCI_RUNNER_PIDS_LIMIT` | Límites de recursos de cada contenedor de trabajo | `2048` / `1` / `256` |

Genera `CDCI_RUNNER_TOKEN` con un secreto aleatorio distinto por ambiente. En PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. Define el mismo valor en el proceso del backend y en `cdci_back/.env`; no lo guardes en Git.

Genera `CDCI_IDENTITY_RATE_LIMIT_HMAC_KEY` con al menos 32 bytes aleatorios por ambiente y guárdala en el gestor de secretos; usa el mismo valor en todas las réplicas de un ambiente, cámbialo entre ambientes y no uses el texto de ejemplo en producción. Activa `CDCI_EMAIL_ENABLED=true` solo después de configurar un SMTP confiable y TLS/autenticación según el proveedor. El registro público debe seguir cerrado hasta habilitar verificación de correo y MFA. Para abrirlo, fija `CDCI_REGISTRATION_ENABLED=true`, `CDCI_MFA_REQUIRED=true`, `CDCI_EMAIL_VERIFICATION_REQUIRED=true` y genera claves independientes de 32 bytes Base64 para `CDCI_MFA_ENCRYPTION_KEY` y las demás claves de cifrado; la aplicación rechaza el arranque si se abre el registro sin la política MFA o su clave.

MFA usa TOTP RFC 6238 (6 dígitos, pasos de 30 s), almacena el secreto con AES-256-GCM, evita aceptar dos veces el mismo paso y limita los intentos de desafío en PostgreSQL. Al activarla se generan diez códigos de recuperación de 128 bits; CDCI guarda sus hashes, los muestra una sola vez y consume cada código atómicamente. Las sesiones vencen después de 30 minutos sin actividad y, por defecto, a las 12 horas absolutas desde el inicio de sesión. Configura la hora del host con NTP y conserva la clave MFA en el gestor de secretos: perderla impide descifrar los TOTP existentes.

Genera `CDCI_SECRET_ENCRYPTION_KEY` con el mismo comando de PowerShell, pero usa una clave diferente de la clave GitHub y conserva un respaldo seguro de ella. Si se pierde o rota sin migrar/re-cifrar los datos, los secretos existentes no podrán descifrarse. La API solo permite administrar metadatos y nunca devuelve valores en claro.

Las contraseñas se guardan con Argon2id. Spring Security administra la cookie de sesión `HttpOnly`; las rutas mutables requieren el token CSRF entregado por `GET /api/auth/csrf`. No guardes la cookie de sesión en almacenamiento web. En despliegues, usa secretos gestionados para credenciales de base de datos y bootstrap, TLS, orígenes CORS exactos y cookie `Secure`.

## API de identidad

- `GET /api/auth/csrf` prepara y devuelve el token CSRF.
- `POST /api/auth/register` crea una cuenta nueva con rol básico de lectura.
- `POST /api/auth/login` recibe `application/x-www-form-urlencoded` con `email` y `password`.
- Si la cuenta tiene MFA o la política la exige, el login devuelve un desafío no autenticado; `POST /api/auth/mfa/login` completa TOTP o un código de recuperación. `GET /api/auth/mfa/status`, `POST /api/auth/mfa/setup`, `POST /api/auth/mfa/confirm`, `POST /api/auth/mfa/recovery-codes` y `POST /api/auth/mfa/disable` gestionan la configuración y requieren CSRF en escrituras.
- `GET /api/auth/me` devuelve el perfil de la sesión activa.
- `POST /api/auth/logout` invalida la sesión actual.
- `PUT /api/auth/password` valida la contraseña actual, cambia la contraseña y cierra las otras sesiones.
- `GET /api/auth/password-reset/status` indica si la recuperación por correo está habilitada en este ambiente.
- `POST /api/auth/password-reset/request` acepta `{ "email": "..." }` y siempre responde con el mismo mensaje para evitar revelar si una cuenta existe. Envía un enlace solo cuando el correo está habilitado y la cuenta está activa.
- `POST /api/auth/password-reset/confirm` acepta `{ "token": "...", "newPassword": "..." }`, consume un token una sola vez, cambia la contraseña y cierra todas las sesiones existentes.
- `GET /api/auth/email-verification/status` indica si las cuentas nuevas requieren verificación.
- `POST /api/auth/email-verification/resend` acepta `{ "email": "..." }` y mantiene una respuesta genérica para cuentas existentes, verificadas o no registradas.
- `POST /api/auth/email-verification/confirm` acepta `{ "token": "..." }`; verifica la cuenta solo una vez y el enlace vence a las 24 horas.

Los intentos de login y registro tienen límites compartidos por PostgreSQL; la recuperación y verificación también limitan por IP/correo. Los buckets usan upsert atómico y HMAC para operar entre réplicas sin guardar los identificadores en claro. Configura límites de borde y considera que los usuarios tras el mismo proxy comparten el límite por IP.
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

`GET /api/dashboard` entrega métricas y hasta 50 ejecuciones recientes únicamente de los proyectos visibles para la sesión. Las métricas de día usan `America/La_Paz`; la tasa de éxito y la mediana usan runs terminados de los últimos 30 días.

La integración usa una **GitHub App** y tokens de usuario con permisos de lectura de metadatos/contenidos y escritura de estados de despliegue. No uses personal access tokens para conectar repositorios. Configúrala así:

1. Registra la App y agrega `CDCI_GITHUB_CALLBACK_URL` como callback del flujo web OAuth. Activa tokens de usuario expirables y autoriza `offline_access` para permitir renovación.
2. Concede permisos de repositorio `Metadata: read-only`, `Contents: read-only` y `Deployments: read and write`; instala la App en la cuenta u organización y repositorios que se usarán. El administrador de la instalación debe aprobar el permiso nuevo; las cuentas CDCI conectadas deben volver a autorizar GitHub para recibirlo.
3. Copia su Client ID y Client Secret al entorno del backend. El Client ID es el de la App; no es el App ID.
4. Genera `CDCI_GITHUB_TOKEN_ENCRYPTION_KEY` con PowerShell: `$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`. Guárdala en el gestor de secretos del entorno. La misma clave debe mantenerse para poder leer conexiones existentes; perderla requiere reconectar GitHub.
5. Conecta desde `/projects`. Desvincula todos los repositorios antes de desconectar la cuenta; CDCI revoca el grant de GitHub al hacerlo.

El callback externo conserva la sesión CDCI del navegador. En producción, registra exactamente la URL HTTPS publicada y configura cookie `Secure`; si el frontend y la API están en sitios distintos, usa `SameSite=None` y HTTPS.

Los mantenedores pueden revisar la preparación de cada repositorio desde el detalle del proyecto. `POST /api/projects/{projectId}/repositories/{repositoryId}/readiness` valida acceso a metadatos, estado del repositorio, rama predeterminada y el indicador de rama protegida; también informa las políticas de CDCI y permisos todavía no confirmados. Cada intento queda en la actividad/auditoría del proyecto para medir uso real. GitHub expone el estado `protected` en la consulta de rama con `Contents: read` ([Get a branch](https://docs.github.com/en/rest/branches/branches#get-a-branch)); para ver las reglas detalladas de protección se requiere `Administration: read` ([Get branch protection](https://docs.github.com/en/rest/branches/branch-protection#get-branch-protection)), que no se solicita en la integración v1. `Deployments: write` se valida al crear el primer despliegue, ya que la revisión manual no genera cambios en GitHub.

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

Las ejecuciones manuales persisten una solicitud `QUEUED` con snapshot de versión, rama y logs del sistema. El contrato del worker está expuesto en `/api/runner`: `POST /claim`, `/runs/{id}/source`, `/runs/{id}/logs`, `/runs/{id}/heartbeat`, `/runs/{id}/deployments` y `/runs/{id}/complete`. Usa `Authorization: Bearer <CDCI_RUNNER_TOKEN>`. Claim responde `204` si la cola está vacía; si hay trabajo entrega la definición, el repositorio y el token GitHub de lectura al worker autenticado. Ese token solo se usa durante el checkout y nunca se monta ni se pasa al contenedor o al comando del pipeline. Leases renuevan el worker activo y cierran como fallidas las ejecuciones cuyo worker deja de reportar.

Los endpoints públicos de monitorización son `GET /api/health/live` (proceso) y `GET /api/health/ready` (proceso y consulta a PostgreSQL). Ambos evitan caché y la respuesta de readiness no expone detalles de conexión. El [runbook operativo](ops/RUNBOOK.md) define alarmas, retención, backups PostgreSQL cifrados y un procedimiento de restauración en una base aislada.

Para desarrollo, configura el mismo `CDCI_RUNNER_TOKEN` para el backend y `cdci_back/.env`, luego inicia el worker: `docker compose --profile runner up --build -d runner`. El perfil levanta un daemon Docker-in-Docker privado para jobs efímeros; cada paso corre como UID no root, sin capacidades Linux, con filesystem base de solo lectura, límite de memoria/CPU/PIDs y timeout. La red del job es `none` por defecto; `bridge` permite egress irrestricto. Usa esta modalidad en una máquina Linux dedicada al runner, no junto a servicios de producción: ejecutar código de repositorio sigue siendo una frontera de seguridad. El perfil no publica puertos del daemon Docker. En producción usa `CDCI_RUNNER_API_URL` con HTTPS/TLS; el valor `host.docker.internal` es para desarrollo local.

### Despliegue por entorno y rollback

Agrega `environment` a un trabajo para que CDCI registre el ciclo en GitHub Deployments y ejecute los pasos YAML en el runner:

```yaml
deploy-staging:
  image: alpine:3.22
  network: bridge
  environment: staging
  secrets: [STAGING_DEPLOY_TOKEN]
  steps:
    - name: Publicar en staging
      run: ./deploy.sh staging "$CDCI_COMMIT_SHA"

deploy-production:
  image: alpine:3.22
  network: bridge
  environment: production
  secrets: [PRODUCTION_DEPLOY_TOKEN]
  steps:
    - name: Publicar en production
      run: ./deploy.sh production "$CDCI_COMMIT_SHA"
```

Los trabajos se procesan en el orden YAML. Staging queda aprobado automáticamente; production espera la decisión de OWNER/MAINTAINER/admin en CDCI y quien solicitó la ejecución no puede aprobarla. El inicio del deploy crea un Deployment y estado `in_progress` en GitHub; al terminar se envía `success` o `failure`. CDCI serializa trabajos activos por repositorio y entorno. `CDCI_COMMIT_SHA`, `CDCI_ENVIRONMENT` y `CDCI_ROLLBACK` se entregan al contenedor de cada job. El proyecto debe configurar los comandos y credenciales de destino como pasos/secretos propios; CDCI no aprovisiona servidores ni hace build/push automático a GHCR.

Desde el historial del proyecto, una persona mantenedora puede encolar rollback de un despliegue exitoso. CDCI usa el SHA inmutable registrado, el mismo snapshot de pipeline y la rama original; production vuelve a esperar aprobación. GitHub registra estados y enlaza al historial de CDCI.

Endpoints de usuario: `GET /api/projects/{projectId}/deployments`, `POST .../{deploymentId}/approve`, `POST .../{deploymentId}/reject` y `POST .../{deploymentId}/rollback`. La pantalla muestra hasta 100 despliegues por proyecto y el dashboard hasta 20 despliegues recientes de proyectos visibles. Runs terminales, logs y despliegues asociados se purgan después de 90 días.

Los campos `triggers` permiten solo ejecuciones manuales. Artefactos y build/push genérico a GHCR quedan fuera de esta iteración.

Referencias oficiales: [autorización de usuario para GitHub Apps](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app), [permisos de lectura de repositorios](https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user), [renovar tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens) y [revocar una autorización](https://docs.github.com/en/rest/apps/oauth-applications#delete-an-app-authorization).
