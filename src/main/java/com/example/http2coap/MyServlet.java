package com.example.http2coap;

import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.proxy.TranslationException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.logging.Logger;

@WebServlet(urlPatterns="/proxy/*", description="HTTP转发到CoAP")
public class MyServlet extends HttpServlet{

    private static final long serialVersionUID = -8685285401859800066L;
    private static final String PROXY_RESOURCE_NAME = "/proxy/";

    private final static Logger LOGGER = Logger.getLogger(MyServlet.class.getCanonicalName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            long st = System.currentTimeMillis();
            Request coapRequest = MyHttpTranslator.getCoapRequest(req, PROXY_RESOURCE_NAME);
            long et1 = System.currentTimeMillis();
            coapRequest.setURI(coapRequest.getOptions().getProxyUri());
            coapRequest.send();
            Response coapResponse = coapRequest.waitForResponse();
            long et2 = System.currentTimeMillis();
            MyHttpTranslator.getHttpResponse(req, coapResponse, resp);
            long et3 = System.currentTimeMillis();

            LOGGER.info(String.format("http->coap cost: %d ms, coap request-response cost: %d ms, coap->http: %d ms", et1 - st, et2-et1, et3-et2));
        } catch (TranslationException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
//        OutputStream outputStream = resp.getOutputStream();
//        outputStream.write("hehehhe".getBytes());
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

}
