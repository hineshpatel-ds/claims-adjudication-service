package com.hines.claims;

import org.springframework.boot.SpringApplication;

public class TestClaimsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(ClaimsServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
