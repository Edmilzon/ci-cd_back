package com.space.cdci.identity;

public class EmailAlreadyRegisteredException extends RuntimeException {
	public EmailAlreadyRegisteredException() { super("Ya existe una cuenta con este correo."); }
}
