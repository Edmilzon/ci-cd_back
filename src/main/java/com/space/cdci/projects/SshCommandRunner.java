package com.space.cdci.projects;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SshCommandRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger(SshCommandRunner.class);
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int COMMAND_TIMEOUT_MILLIS = 30_000;
	private static final int MAX_OUTPUT_BYTES = 128 * 1024;

	public SshResult execute(String host, int port, String username, String password, String hostKeyFingerprint, String command) {
		Session session = null;
		ChannelExec channel = null;
		long started = System.nanoTime();
		try {
			JSch jsch = new JSch();
			jsch.setHostKeyRepository(new SshHostKeyFingerprint(hostKeyFingerprint));
			session = jsch.getSession(username, host, port);
			session.setPassword(password);
			session.setConfig("StrictHostKeyChecking", "yes");
			session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
			session.setUserInfo(new PasswordUserInfo(password));
			session.setTimeout(CONNECT_TIMEOUT_MILLIS);
			session.connect(CONNECT_TIMEOUT_MILLIS);

			channel = (ChannelExec) session.openChannel("exec");
			channel.setCommand(command);
			channel.setInputStream(null);
			LimitedOutputStream stderr = new LimitedOutputStream(MAX_OUTPUT_BYTES);
			channel.setErrStream(stderr, true);

			InputStream stdout = channel.getInputStream();
			channel.connect(CONNECT_TIMEOUT_MILLIS);

			LimitedOutputStream output = new LimitedOutputStream(MAX_OUTPUT_BYTES);
			byte[] buffer = new byte[4096];
			long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MILLIS;
			while (true) {
				readAvailable(stdout, output, buffer);
				if (channel.isClosed()) break;
				if (System.currentTimeMillis() > deadline) {
					throw new SshException("El comando superó el tiempo máximo de ejecución de 30 segundos.");
				}
				Thread.sleep(40);
			}
			readAvailable(stdout, output, buffer);

			long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			return new SshResult(channel.getExitStatus(), output.text(), stderr.text(), durationMillis);
		} catch (JSchException exception) {
			LOGGER.warn("Falló la conexión SSH a {}@{}:{}: {}", username, host, port, exception.getMessage());
			throw new SshException(friendlyMessage(exception), exception);
		} catch (IOException exception) {
			LOGGER.warn("Falló la lectura de la sesión SSH a {}@{}:{}: {}", username, host, port, exception.getMessage());
			throw new SshException("No se pudo leer la respuesta del servidor SSH.", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new SshException("Se interrumpió la conexión SSH.", exception);
		} finally {
			if (channel != null) channel.disconnect();
			if (session != null) session.disconnect();
		}
	}

	private static void readAvailable(InputStream input, LimitedOutputStream output, byte[] buffer) throws IOException {
		while (input.available() > 0) {
			int read = input.read(buffer);
			if (read < 0) return;
			output.write(buffer, 0, read);
		}
	}

	private static String friendlyMessage(JSchException exception) {
		String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
		if (message.contains("auth fail") || message.contains("auth cancel")) {
			return "El servidor rechazó el usuario o la contraseña.";
		}
		if (message.contains("hostkey") || message.contains("host key")) {
			return "La huella del servidor no coincide con la registrada. Verifícala por un canal confiable antes de actualizarla.";
		}
		if (message.contains("connection refused")) {
			return "El servidor rechazó la conexión. Verifica el puerto SSH y que el servicio esté activo.";
		}
		if (message.contains("timeout") || message.contains("timed out")) {
			return "La conexión SSH agotó el tiempo de espera. Verifica la IP, el puerto y el firewall.";
		}
		if (message.contains("unknownhost") || message.contains("name or service not known")) {
			return "No se pudo resolver el host. Verifica la IP o el nombre del servidor.";
		}
		if (message.contains("connection reset")) {
			return "El servidor cerró la conexión inesperadamente.";
		}
		return "No se pudo establecer la conexión SSH. Verifica el host, el puerto y las credenciales.";
	}

	public record SshResult(int exitStatus, String stdout, String stderr, long durationMillis) {}

	public static class SshException extends RuntimeException {
		public SshException(String message) { super(message); }
		public SshException(String message, Throwable cause) { super(message, cause); }
	}

	private static final class PasswordUserInfo implements UserInfo {
		private final String password;

		private PasswordUserInfo(String password) { this.password = password; }

		@Override public String getPassphrase() { return null; }
		@Override public String getPassword() { return password; }
		@Override public boolean promptPassword(String message) { return true; }
		@Override public boolean promptPassphrase(String message) { return false; }
		@Override public boolean promptYesNo(String message) { return true; }
		@Override public void showMessage(String message) {}
	}

	private static final class LimitedOutputStream extends OutputStream {
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final int limit;
		private boolean truncated;

		private LimitedOutputStream(int limit) { this.limit = limit; }

		@Override public void write(int value) {
			if (buffer.size() < limit) buffer.write(value);
			else truncated = true;
		}

		@Override public void write(byte[] bytes, int offset, int length) {
			int remaining = limit - buffer.size();
			if (remaining > 0) buffer.write(bytes, offset, Math.min(length, remaining));
			if (length > remaining) truncated = true;
		}

		private String text() {
			String value = buffer.toString(StandardCharsets.UTF_8);
			return truncated ? value + "\n[Salida truncada a " + limit + " bytes]" : value;
		}
	}
}
