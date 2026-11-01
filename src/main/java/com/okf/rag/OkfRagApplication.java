package com.okf.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OkfRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(OkfRagApplication.class, args);
	}

}
