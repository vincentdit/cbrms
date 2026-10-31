package tz.go.tirdo.cbrms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CbrmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbrmsApplication.class, args);
    }
}
