# Releases de CDCI por GitHub Actions

Los workflows `.github/workflows/release.yml` de `cdci_back` y `cdci_front` publican paquetes versionados en los ambientes `staging` y `production`. El paquete se construye una vez por ejecución, queda en GitHub Actions por 90 días y se despliega desde un tag anotado `vMAJOR.MINOR.PATCH`. El workflow exige que el tag apunte a un commit ya integrado en `main`. El despliegue cambia `current` mediante un symlink atómico, intenta readiness hasta 30 veces con pausas de dos segundos y vuelve al symlink anterior si el servicio no queda sano. Conserva las diez versiones más recientes y siempre protege la actual y la anterior. Una reejecución de un tag que aún está en el host reutiliza su paquete ya desplegado si el SHA fuente coincide, incluso cuando un build nuevo tenga otro checksum.

El build del frontend usa un `NEXT_PUBLIC_API_URL` vacío: Nginx publica UI y API bajo el mismo origen, de modo que el mismo artefacto sirve para staging y producción. La API sigue recibiendo sus secretos desde el archivo local del servidor; ninguna clave de aplicación entra al build o al runner de empaquetado.

## Preparar GitHub

Realiza estos pasos **en ambos repositorios**:

1. Protege `main` con revisión de cambios y exige que pase el check `Backend CI / test` o `Frontend CI / verify` antes de integrar cambios. Agrega una regla de tags `v*` que prohíba actualizar o borrar tags publicados. El workflow rechaza tags ligeros, versiones fuera de `vMAJOR.MINOR.PATCH` y commits que no estén en `origin/main`.
2. Crea los ambientes GitHub `staging` y `production` antes de ejecutar el workflow. Limita los deployments a ejecuciones desde `main`.
3. En `production`, configura revisores requeridos que no sean quien inició la publicación. En `staging`, la aprobación puede quedar desactivada. La protección de GitHub Environments depende del plan y visibilidad del repositorio; comprueba que la regla aparece activa antes de usar production.
4. En cada ambiente configura las variables y secretos de la tabla. Usa hosts y claves independientes para staging y producción; guarda `CDCI_DEPLOY_SSH_KEY` y `CDCI_DEPLOY_KNOWN_HOSTS` como secretos de Environment, nunca como variables de repositorio.
5. Crea un tag anotado y publícalo en GitHub después de integrar los cambios:

   ```sh
   git tag -a v1.0.0 -m "CDCI 1.0.0"
   git push origin v1.0.0
   ```

6. Desde **Actions → Backend release** o **Frontend release**, inicia `Run workflow`, selecciona la rama `main`, escribe el tag y selecciona el ambiente. El job de production queda esperando aprobación del revisor requerido.

| Tipo | Nombre en GitHub Environment | Contenido |
|---|---|---|
| Variable | `CDCI_DEPLOY_HOST` | DNS o IPv4 del host SSH del ambiente. |
| Variable | `CDCI_DEPLOY_USER` | Cuenta de despliegue restringida, por ejemplo `cdci-deploy`. |
| Variable | `CDCI_DEPLOY_PORT` | Puerto SSH; `22` si no se define. |
| Variable | `CDCI_PUBLIC_URL` | URL HTTPS del ambiente; aparece como enlace en el Deployment de GitHub. |
| Secreto | `CDCI_DEPLOY_SSH_KEY` | Clave privada Ed25519 de la cuenta de despliegue. |
| Secreto | `CDCI_DEPLOY_KNOWN_HOSTS` | Línea de `known_hosts` con la clave pública fijada para host/puerto. |

Obtén la huella de la clave pública del servidor y compárala por un canal confiable antes de guardar `CDCI_DEPLOY_KNOWN_HOSTS`. El workflow no usa `ssh-keyscan` como fuente de confianza; comprueba estrictamente la clave guardada. El host debe aceptar conexión desde el runner GitHub-hosted `ubuntu-24.04`. Si la red destino no es accesible desde runners GitHub-hosted, configura conectividad privada para Actions o cambia el job de despliegue a un runner privado administrado por el equipo.

## Preparar cada host Linux

Usa un host Ubuntu 24.04 x86_64 por ambiente, con OpenSSH, `tar`, `sha256sum`, `curl`, `sudo` y `systemd`. Instala Java 21 para el backend y Node.js 22 para el frontend. El host debe tener un reverse proxy HTTPS (por ejemplo Nginx), servicios systemd y una cuenta SSH de despliegue separada de la cuenta de runtime. Los workflows no instalan ni alteran infraestructura.

Prepara estas rutas, propiedad del usuario de despliegue, legibles por el grupo de runtime (`cdci-backend`/`cdci-frontend`) y con el grupo de runtime agregado a `cdci-deploy`:

```text
/srv/cdci/backend/releases
/srv/cdci/frontend/releases
```

El backend ejecuta `/srv/cdci/backend/current/app.jar` como `cdci-backend.service`. Mantén la configuración de Spring, conexión PostgreSQL, GitHub App, claves de cifrado, token del runner y bootstrap del administrador en `/etc/cdci/backend.env`, propiedad de root y modo `0600`; configura el servicio con `EnvironmentFile=/etc/cdci/backend.env`, `User=cdci-backend`, `Group=cdci-backend`, `WorkingDirectory=/srv/cdci/backend/current` y `ExecStart=/usr/bin/java -jar /srv/cdci/backend/current/app.jar`.

El frontend ejecuta `/srv/cdci/frontend/current/server.js` con `User=cdci-frontend`, `WorkingDirectory=/srv/cdci/frontend/current`, `Environment=NODE_ENV=production`, `Environment=PORT=3000`, `Environment=HOSTNAME=127.0.0.1` y `ExecStart=/usr/bin/node /srv/cdci/frontend/current/server.js`.

Configura `sudoers` para permitir a `cdci-deploy` reiniciar y detener **solo** `cdci-backend.service` o **solo** `cdci-frontend.service` en el host correspondiente. El script usa `sudo -n`; no debe pedir contraseña. Mantén separados los usuarios runtime/deploy y evita dar shell de root a la clave SSH. El directorio de caché `.next/cache` queda escribible para el servicio frontend; el resto del release es de solo lectura para runtime.

En Nginx termina TLS y enruta `/api/` a `127.0.0.1:8080` y el resto a `127.0.0.1:3000`. Configura en el backend `CDCI_FRONTEND_ORIGIN` con el origen público exacto, `CDCI_COOKIE_SECURE=true`, registro cerrado y el callback OAuth HTTPS registrado en la GitHub App. Los secretos del backend permanecen en el host, nunca en GitHub Actions.

## Recuperación

Si falla el health check, el workflow restaura la versión anterior, reinicia el servicio y marca la publicación como fallida. Revisa el estado y logs con `systemctl status` y `journalctl -u cdci-backend.service` o `journalctl -u cdci-frontend.service`; no copies secretos a los logs. Para una reversión manual, vuelve a ejecutar el workflow con el tag anotado anterior en el mismo ambiente. La aprobación de `production` se exige también para esa reversión.

Los paquetes de GitHub Actions expiran según `retention-days: 90`; los directorios remotos conservan las diez versiones recientes más la actual y la versión anterior. El SHA-256 se verifica después de descargar desde Actions y nuevamente en el servidor. El tag protegido y el `SOURCE_SHA` guardado con cada release permiten rastrear cada servicio a un commit exacto.
