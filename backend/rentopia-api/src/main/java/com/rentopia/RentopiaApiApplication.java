package com.rentopia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RentopiaApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(RentopiaApiApplication.class, args);
	}

}
