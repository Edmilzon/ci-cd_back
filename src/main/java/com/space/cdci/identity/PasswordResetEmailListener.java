package com.space.cdci.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PasswordResetEmailListener {
	private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailListener.class);
	private final JavaMailSender mailSender;
	private final String fromAddress;

	public PasswordResetEmailListener(JavaMailSender mailSender,
			@Value("${cdci.email.from:no-reply@cdci.local}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void send(PasswordResetEmailRequested event) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(event.recipient());
		message.setSubject("Recupera tu contraseña de CDCI");
		message.setText("Recibimos una solicitud para cambiar la contraseña de tu cuenta CDCI.\n\n"
				+ "Abre este enlace dentro de los próximos " + event.lifetimeMinutes() + " minutos:\n" + event.resetUrl()
				+ "\n\nSi no solicitaste este cambio, ignora este mensaje. El enlace solo se puede usar una vez.");
		try {
			mailSender.send(message);
		} catch (MailException exception) {
			log.warn("No se pudo entregar un correo de recuperación de contraseña.");
		}
	}
}
