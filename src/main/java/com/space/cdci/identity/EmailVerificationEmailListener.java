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
public class EmailVerificationEmailListener {
	private static final Logger log = LoggerFactory.getLogger(EmailVerificationEmailListener.class);
	private final JavaMailSender mailSender;
	private final String fromAddress;

	public EmailVerificationEmailListener(JavaMailSender mailSender,
			@Value("${cdci.email.from:no-reply@cdci.local}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void send(EmailVerificationRequested event) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(event.recipient());
		message.setSubject("Verifica tu correo de CDCI");
		message.setText("Confirma el correo de tu cuenta CDCI con este enlace, válido durante 24 horas:\n\n"
				+ event.verificationUrl() + "\n\nSi no creaste esta cuenta, puedes ignorar este mensaje.");
		try {
			mailSender.send(message);
		} catch (MailException exception) {
			log.warn("No se pudo entregar un correo de verificación de cuenta.");
		}
	}
}
