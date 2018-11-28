package com.example.http2coap;

import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.proxy.TranslationException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@WebServlet(urlPatterns = "/proxy-ob/*", description = "HTTP转发到CoAP")
public class ObServlet extends HttpServlet {
    private final static Logger LOGGER = Logger.getLogger(ObServlet.class.getCanonicalName());
    private static final String PROXY_RESOURCE_NAME = "/proxy-ob/";

    private static String Result = "";
    private static Subject subject;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (subject == null) {
            subject = new Subject();
            createObserver(req);
        }
        resp.setHeader("eTag", subject.etag);
        PrintWriter writer = resp.getWriter();
        writer.println(subject.data);

    }

    private void createObserver(HttpServletRequest req) {
        try {
            Request coapReq = MyHttpTranslator.getCoapRequest(req, PROXY_RESOURCE_NAME);
            CoapClient client = new CoapClient(coapReq.getOptions().getProxyUri());
            client.observe(
                    new CoapHandler() {
                        @Override
                        public void onLoad(CoapResponse response) {
                            String content = response.getResponseText();
                            LOGGER.info(content);

                            subject.data = content;
                            subject.date = new Date();
                            subject.etag = String.valueOf(new Date().getTime());
                        }

                        @Override
                        public void onError() {
                            System.err.println("-Failed--------");
                        }
                    });
        } catch (TranslationException e) {
            e.printStackTrace();
        }

    }

    class Subject {
        private String data = "";
        private Date date;
        private String etag = "";

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Date getDate() {
            return date;
        }

        public void setDate(Date date) {
            this.date = date;
        }

        public String getEtag() {
            return etag;
        }

        public void setEtag(String etag) {
            this.etag = etag;
        }
    }
}
