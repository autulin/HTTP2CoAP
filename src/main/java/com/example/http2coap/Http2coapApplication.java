package com.example.http2coap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;

@SpringBootApplication
@ServletComponentScan
public class Http2coapApplication implements CommandLineRunner {

    @Autowired
    Coap2HttpServer coap2HttpServer;

    public static void main(String[] args) {
        SpringApplication.run(Http2coapApplication.class, args);
    }


    @Override
    public void run(String... args) throws Exception {
        coap2HttpServer.start();
    }
}
