package com.example.http2coap;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.proxy.resources.ForwardingResource;
import org.eclipse.californium.proxy.resources.ProxyCoapClientResource;
import org.eclipse.californium.proxy.resources.ProxyHttpClientResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Coap2HttpServer {


    @Value("${coapserver.port}")
    private int port;

    private CoapServer coapProxy;

    public void start() {
        ForwardingResource coap2coap = new ProxyCoapClientResource("coap2coap");
        ForwardingResource coap2http = new ProxyHttpClientResource("coap2http");

        // Create CoAP Server on PORT with proxy resources form CoAP to CoAP and HTTP
        coapProxy = new CoapServer(port);

        coapProxy.setMessageDeliverer(new ProxyMessageDeliverer(coapProxy.getRoot(), coap2coap, coap2http));

        coapProxy.add(new TargetResource("test"));
        coapProxy.start();
    }


    /**
     * A simple resource that responds to GET requests with a small response
     * containing the resource's name.
     */
    private static class TargetResource extends CoapResource {

        private int counter = 0;

        public TargetResource(String name) {
            super(name);
        }

        @Override
        public void handleGET(CoapExchange exchange) {
            exchange.respond("Response "+(++counter)+" from resource " + getName());
        }
    }

}
