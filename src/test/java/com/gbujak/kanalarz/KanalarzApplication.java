package com.gbujak.kanalarz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class KanalarzApplication {

	public static void main(String[] args) {
		SpringApplication.run(KanalarzApplication.class, args);
	}
}
