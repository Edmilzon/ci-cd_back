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
