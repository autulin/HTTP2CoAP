/*******************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Martin Lanter - architect and re-implementation
 *    Francesco Corazza - HTTP cross-proxy
 *    Paul LeMarquand - fix content type returned from getHttpEntity(), cleanup
 ******************************************************************************/
package com.example.http2coap;

import org.apache.http.entity.ContentType;
import org.eclipse.californium.core.coap.*;
import org.eclipse.californium.core.coap.CoAP.Code;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.CoAP.Type;
import org.eclipse.californium.core.coap.OptionNumberRegistry.optionFormats;
import org.eclipse.californium.proxy.InvalidFieldException;
import org.eclipse.californium.proxy.InvalidMethodException;
import org.eclipse.californium.proxy.MappingProperties;
import org.eclipse.californium.proxy.TranslationException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;


/**
 * Class providing the translations (mappings) from the HTTP message
 * representations to the CoAP message representations and vice versa.
 */
public final class MyHttpTranslator {

    private static final String KEY_COAP_CODE = "coap.response.code.";
    private static final String KEY_COAP_OPTION = "coap.message.option.";
    private static final String KEY_COAP_MEDIA = "coap.message.media.";
    private static final String KEY_HTTP_CODE = "http.response.code.";
    private static final String KEY_HTTP_METHOD = "http.request.method.";
    private static final String KEY_HTTP_HEADER = "http.message.header.";
    private static final String KEY_HTTP_CONTENT_TYPE = "http.message.content-type.";

    /**
     * Property file containing the mappings between coap messages and http
     * messages.
     */
    public static final Properties HTTP_TRANSLATION_PROPERTIES = new MappingProperties("Proxy.properties");

    // Error constants
    public static final int STATUS_TIMEOUT = HttpServletResponse.SC_GATEWAY_TIMEOUT;
    public static final int STATUS_NOT_FOUND = HttpServletResponse.SC_BAD_GATEWAY;
    public static final int STATUS_TRANSLATION_ERROR = HttpServletResponse.SC_BAD_GATEWAY;
    public static final int STATUS_URI_MALFORMED = HttpServletResponse.SC_BAD_REQUEST;
    public static final int STATUS_WRONG_METHOD = HttpServletResponse.SC_NOT_IMPLEMENTED;

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    protected static final Logger LOGGER = Logger.getLogger(MyHttpTranslator.class.getName());

    /**
     * Gets the coap media type associated to the http entity. Firstly, it looks
     * for a valid mapping in the property file. If this step fails, then it
     * tries to explicitly map/parse the declared mime/type by the http entity.
     * If even this step fails, it sets application/octet-stream as
     * content-type.
     *
     * @param httpRequest
     *
     *
     * @return the coap media code associated to the http message entity. * @see
     *         HttpHeader, ContentType, MediaTypeRegistry
     */
    public static int getCoapMediaType(HttpServletRequest httpRequest) {

        // set the content-type with a default value
        int coapContentType = MediaTypeRegistry.UNDEFINED;

        // get the content-type from the entity
        String httpContentTypeString = httpRequest.getContentType();

        // check if there is an associated content-type with the current http
        // message
        if (httpContentTypeString != null) {
            // delete the last part (if any)
            httpContentTypeString = httpContentTypeString.split(";")[0];

            // retrieve the mapping from the property file
            String coapContentTypeString = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_HTTP_CONTENT_TYPE + httpContentTypeString);

            if (coapContentTypeString != null) {
                coapContentType = Integer.parseInt(coapContentTypeString);
            } else {
                // try to parse the media type if the property file has given to
                // mapping
                coapContentType = MediaTypeRegistry.parse(httpContentTypeString);
            }
        }

        // if not recognized, the content-type should be
        // application/octet-stream (draft-castellani-core-http-mapping 6.2)
        if (coapContentType == MediaTypeRegistry.UNDEFINED) {
            coapContentType = MediaTypeRegistry.APPLICATION_OCTET_STREAM;
        }

        return coapContentType;
    }

    /**
     * Gets the coap options starting from an array of http headers. The
     * content-type is not handled by this method. The method iterates over an
     * array of headers and for each of them tries to find a mapping in the
     * properties file, if the mapping does not exists it skips the header
     * ignoring it. The method handles separately certain headers which are
     * translated to options (such as accept or cache-control) whose content
     * should be semantically checked or requires ad-hoc translation. Otherwise,
     * the headers content is translated with the appropriate format required by
     * the mapped option.
     *
     *
     */
    public static List<Option> getCoapOptions(HttpServletRequest req) {
        Enumeration<String> headers = req.getHeaderNames();
        if (!headers.hasMoreElements()) {
            throw new IllegalArgumentException("httpMessage == null");
        }

        List<Option> optionList = new LinkedList<Option>();

        // iterate over the headers
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            try {
                String headerName = header.toLowerCase();

                // FIXME: CoAP does no longer support multiple accept-options.
                // If an HTTP request contains multiple accepts, this method
                // fails. Therefore, we currently skip accepts at the moment.
                if (headerName.startsWith("accept"))
                    continue;

                // get the mapping from the property file
                String optionCodeString = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_HTTP_HEADER + headerName);

                // ignore the header if not found in the properties file
                if (optionCodeString == null || optionCodeString.isEmpty()) {
                    continue;
                }

                // get the option number
                int optionNumber = OptionNumberRegistry.RESERVED_0;
                try {
                    optionNumber = Integer.parseInt(optionCodeString.trim());
                } catch (Exception e) {
                    LOGGER.warning("Problems in the parsing: " + e.getMessage());
                    // ignore the option if not recognized
                    continue;
                }

                // ignore the content-type because it will be handled in the payload processing
                if (optionNumber == OptionNumberRegistry.CONTENT_FORMAT) {
                    continue;
                }

                // get the value of the current header
                String headerValue = req.getHeader(header).trim();

                // if the option is accept, it needs to translate the
                // values
                if (optionNumber == OptionNumberRegistry.ACCEPT) {
                    // remove the part where the client express the weight of each
                    // choice
                    headerValue = headerValue.trim().split(";")[0].trim();

                    // iterate for each content-type indicated
                    for (String headerFragment : headerValue.split(",")) {
                        // translate the content-type
                        Integer[] coapContentTypes = { MediaTypeRegistry.UNDEFINED };
                        if (headerFragment.contains("*")) {
                            coapContentTypes = MediaTypeRegistry.parseWildcard(headerFragment);
                        } else {
                            coapContentTypes[0] = MediaTypeRegistry.parse(headerFragment);
                        }

                        // if is present a conversion for the content-type, then add
                        // a new option
                        for (int coapContentType : coapContentTypes) {
                            if (coapContentType != MediaTypeRegistry.UNDEFINED) {
                                // create the option
                                Option option = new Option(optionNumber, coapContentType);
                                optionList.add(option);
                            }
                        }
                    }
                } else if (optionNumber == OptionNumberRegistry.MAX_AGE) {
                    int maxAge = 0;
                    if (!headerValue.contains("no-cache")) {
                        for (String headerValueItem : headerValue.split(",")) {
                            headerValueItem = headerValueItem.trim();

                            if (headerValueItem.startsWith("max-age")) {
                                int index = headerValueItem.indexOf('=');
                                try {
                                    maxAge = Integer.parseInt(headerValueItem.substring(index + 1).trim());
                                } catch (NumberFormatException e) {
                                    LOGGER.warning("Cannot convert cache control in max-age option");
//                                    continue headerLoop;
                                    break;
                                }
                            }
                        }
                    }
                    // create the option
                    Option option = new Option(optionNumber, maxAge);
                    // option.setValue(headerValue.getBytes(Charset.forName("ISO-8859-1")));
                    optionList.add(option);
                } else {
                    // create the option
                    Option option = new Option(optionNumber);
                    switch (OptionNumberRegistry.getFormatByNr(optionNumber)) {
                        case INTEGER:
                            option.setIntegerValue(Integer.parseInt(headerValue));
                            break;
                        case OPAQUE:
                            option.setValue(headerValue.getBytes(ISO_8859_1));
                            break;
                        case STRING:
                        default:
                            option.setStringValue(headerValue);
                            break;
                    }
                    // option.setValue(headerValue.getBytes(Charset.forName("ISO-8859-1")));
                    optionList.add(option);
                }
            } catch (RuntimeException e) {
                // Martin: I have added this try-catch block. The problem is
                // that HTTP support multiple Accepts while CoAP does not. A
                // headder line might look like this:
                // Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
                // This cannot be parsed into a single CoAP Option and yields a
                // NumberFormatException
                LOGGER.warning("Could not parse header line "+header);
            }
        } // while (headerIterator.hasNext())

        return optionList;
    }

    /**
     * Method to map the http entity of a http message in a coherent payload for
     * the coap message. The method simply gets the bytes from the entity and,
     * if needed changes the charset of the obtained bytes to UTF-8.
     *
     *
     * @return byte[]
     * @throws TranslationException the translation exception
     */
    public static byte[] getCoapPayload(HttpServletRequest req) throws TranslationException {
        int len = req.getContentLength();
        if (len <= 0) {
            return null;
        }

        byte[] payload = new byte[len];
        try {
            // get the bytes from the entity
            InputStream inputStream = req.getInputStream();
            inputStream.read(payload, 0, len);

            // get the charset for the http entity
//			req.getCharacterEncoding();
            // translate the payload to the utf-8 charset
//					payload = changeCharset(payload, httpCharset, CoAP.UTF8_CHARSET);

        } catch (IOException e) {
            LOGGER.warning("Cannot get the content of the http entity: " + e.getMessage());
            throw new TranslationException("Cannot get the content of the http entity", e);
        }

        return payload;
    }

    /**
     * Gets the coap request. Creates the CoAP request from the HTTP method and
     * mapping it through the properties file. The uri is translated using
     * regular expressions, the uri format expected is either the embedded
     * mapping (http://proxyname.domain:80/proxy/coapserver:5683/resource
     * converted in coap://coapserver:5683/resource) or the standard uri to
     * indicate a local request not to be forwarded. The method uses a decoder
     * to translate the application/x-www-form-urlencoded format of the uri. The
     * CoAP options are set translating the headers. If the HTTP message has an
     * enclosing entity, it is converted to create the payload of the CoAP
     * message; finally the content-type is set accordingly to the header and to
     * the entity type.
     *
     * @param httpRequest   the http request
     * @param proxyResource the proxy resource, if present in the uri, indicates the need
     *                      of forwarding for the current request
     * @return the coap request * @throws TranslationException the translation
     * exception
     */
    public static Request getCoapRequest(HttpServletRequest httpRequest, String proxyResource) throws TranslationException {

        byte[] payload = getCoapPayload(httpRequest);

        // get the http method
        String httpMethod = httpRequest.getMethod().toLowerCase();

        // get the coap method
        String coapMethodString = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_HTTP_METHOD + httpMethod);
        if (coapMethodString == null || coapMethodString.contains("error")) {
            throw new InvalidMethodException(httpMethod + " method not mapped");
        }

        int coapMethod = 0;
        try {
            coapMethod = Integer.parseInt(coapMethodString.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning("Cannot convert the http method in coap method: " + e);
            throw new TranslationException("Cannot convert the http method in coap method", e);
        }

        // create the request -- since HTTP is reliable use CON
        Request coapRequest = new Request(Code.valueOf(coapMethod), Type.CON);

        // get the uri
        String uriString = httpRequest.getRequestURI();

        // decode the uri to translate the application/x-www-form-urlencoded
        // format
        try {
            uriString = URLDecoder.decode(uriString, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOGGER.warning("Failed to decode the uri: " + e.getMessage());
            throw new TranslationException("Failed decoding the uri: " + e.getMessage());
        } catch (Throwable e) {
            LOGGER.warning("Malformed uri: " + e.getMessage());
            throw new InvalidFieldException("Malformed uri: " + e.getMessage());
        }

        // if the uri contains the proxy resource name, the request should be
        // forwarded and it is needed to get the real requested coap server's
        // uri
        // e.g.:
        // /proxy/vslab-dhcp-17.inf.ethz.ch:5684/helloWorld
        // proxy resource: /proxy
        // coap server: vslab-dhcp-17.inf.ethz.ch:5684
        // coap resource: helloWorld
        if (uriString.startsWith(proxyResource)) {

            // extract embedded URI
            uriString = uriString.substring(proxyResource.length());

            // if the uri hasn't the indication of the scheme, add it
            if (!uriString.matches("^coaps?://.*")) {
                uriString = "coap://" + uriString;
            }

            // the proxy internally always uses the Proxy-Uri option
            coapRequest.getOptions().setProxyUri(uriString);

        } else {
            LOGGER.warning("Malrouted request: " + httpRequest.getRequestURI());
            return null;
        }

        // translate the http headers in coap options
        List<Option> coapOptions = getCoapOptions(httpRequest);
        for (Option option : coapOptions)
            coapRequest.getOptions().addOption(option);

        // translate the http entity in coap payload

        coapRequest.setPayload(payload);

        // set the content-type
        int coapContentType = getCoapMediaType(httpRequest);
        coapRequest.getOptions().setContentFormat(coapContentType);

        LOGGER.info("Translated CoapRequest " + coapRequest.toString());
        return coapRequest;
    }

    /**
     * Generates an HTTP entity starting from a CoAP request. If the coap
     * message has no payload, it returns a null http entity. It takes the
     * payload from the CoAP message and encapsulates it in an entity. If the
     * content-type is recognized, and a mapping is present in the properties
     * file, it is translated to the correspondent in HTTP, otherwise it is set
     * to application/octet-stream. If the content-type has a charset, namely it
     * is printable, the payload is encapsulated in a StringEntity, if not it a
     * ByteArrayEntity is used.
     *
     * @param coapMessage the coap message
     * @param outputStream
     * @return null if the request has no payload * @throws TranslationException
     * the translation exception
     */
    public static void setHttpEntity(Message coapMessage, HttpServletResponse httpResponse) throws TranslationException {
        if (coapMessage == null) {
            throw new IllegalArgumentException("coapMessage == null");
        }
        // check if coap request has a payload
        byte[] payload = coapMessage.getPayload();

        // todo 注意一下是否需要转换
        if (payload != null && payload.length != 0) {

            String contentType = null;
            Charset charset = null;

            // if the content type is not set, translate with octect-stream
            if (!coapMessage.getOptions().hasContentFormat()) {
                contentType = APPLICATION_OCTET_STREAM;
            } else {
                int coapContentType = coapMessage.getOptions().getContentFormat();
                // search for the media type inside the property file
                String coapContentTypeString = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_COAP_MEDIA + coapContentType);

                // if the content-type has not been found in the property file,
                // try to get its string value (expressed in mime type)
                if (coapContentTypeString == null || coapContentTypeString.isEmpty()) {
                    coapContentTypeString = MediaTypeRegistry.toString(coapContentType);

                    // if the coap content-type is printable, it is needed to
                    // set the default charset (i.e., UTF-8)
                    if (MediaTypeRegistry.isPrintable(coapContentType)) {
                        coapContentTypeString += "; charset=UTF-8";
                    }
                }

                // parse the content type
                try {
                    // simple parser
                    String[] strings = coapContentTypeString.split(";\\s*charset=");
                    contentType = strings[0];
                    charset = Charset.forName(strings[1]);
                } catch (UnsupportedCharsetException e) {
                    LOGGER.finer("Cannot convert string to ContentType: " + e.getMessage());
                    contentType = APPLICATION_OCTET_STREAM;
                }
            }

            // if there is a charset, means that the content is not binary
            /*if (charset != null) {

                // according to the class ContentType the default content-type
                // with UTF-8 charset is application/json. If the content-type
                // parsed is different and is not iso encoded, a translation is
                // needed
                Charset isoCharset = ISO_8859_1;
                if (!charset.equals(isoCharset) && !contentType.equals("application/json")) {
                    byte[] newPayload = changeCharset(payload, charset, isoCharset);

                    // since ISO-8859-1 is a subset of UTF-8, it is needed to
                    // check if the mapping could be accomplished, only if the
                    // operation is successful the payload and the charset should
                    // be changed
                    if (newPayload != null) {
                        payload = newPayload;
                        // if the charset is changed, also the entire
                        // content-type must change
                        charset = isoCharset;
                    }
                }
            }*/

            // set the content-type
            if (charset != null)
                httpResponse.setContentType(contentType + "; charset=" + charset.name());
            else
                httpResponse.setContentType(contentType);
        }

        try {
            OutputStream out = httpResponse.getOutputStream();
            out.write(payload);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets the http headers from a list of CoAP options. The method iterates
     * over the list looking for a translation of each option in the properties
     * file, this process ignores the proxy-uri and the content-type because
     * they are managed differently. If a mapping is present, the content of the
     * option is mapped to a string accordingly to its original format and set
     * as the content of the header.
     *
     * @param optionList the coap message
     * @return Header[]
     */
    public static void setHttpHeaders(List<Option> optionList, HttpServletResponse httpResponse) {
        if (optionList == null) {
            throw new IllegalArgumentException("coapMessage == null");
        }

        // iterate over each option
        for (Option option : optionList) {
            // skip content-type because it should be translated while handling the payload
            // skip ETag for correct formatting
            int optionNumber = option.getNumber();
            if (optionNumber != OptionNumberRegistry.CONTENT_FORMAT && optionNumber != OptionNumberRegistry.ETAG) {
                // get the mapping from the property file
                String headerName = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_COAP_OPTION + optionNumber);

                // set the header
                if (headerName != null && !headerName.isEmpty()) {
                    // format the value
                    String stringOptionValue = null;
                    if (OptionNumberRegistry.getFormatByNr(optionNumber) == optionFormats.STRING) {
                        stringOptionValue = option.getStringValue();
                    } else if (OptionNumberRegistry.getFormatByNr(optionNumber) == optionFormats.INTEGER) {
                        stringOptionValue = Integer.toString(option.getIntegerValue());
                    } else if (OptionNumberRegistry.getFormatByNr(optionNumber) == optionFormats.OPAQUE) {
                        stringOptionValue = option.toValueString();
                    } else {
                        // if the option is not formattable, skip it
                        continue;
                    }

                    // custom handling for max-age
                    // format: cache-control: max-age=60
                    if (optionNumber == OptionNumberRegistry.MAX_AGE) {
                        stringOptionValue = "max-age=" + stringOptionValue;
                    }

                    httpResponse.setHeader(headerName, stringOptionValue);
                }
            } else if (optionNumber == OptionNumberRegistry.ETAG) {
                httpResponse.setHeader("etag", "\"" + option.toValueString().substring(2) + "\"");
            }
        }
    }

    /**
     * Sets the parameters of the incoming http response from a CoAP response.
     * The status code is mapped through the properties file and is set through
     * the StatusLine. The options are translated to the corresponding headers
     * and the max-age (in the header cache-control) is set to the default value
     * (60 seconds) if not already present. If the request method was not HEAD
     * and the coap response has a payload, the entity and the content-type are
     * set in the http response.
     *
     * @param coapResponse the coap response
     * @param httpResponse
     * @param httpRequest  HttpRequest
     * @throws TranslationException the translation exception
     */
    public static void getHttpResponse(HttpServletRequest httpRequest, Response coapResponse, HttpServletResponse httpResponse) throws TranslationException, IOException {
        if (httpRequest == null) {
            throw new IllegalArgumentException("httpRequest == null");
        }
        if (coapResponse == null) {
            throw new IllegalArgumentException("coapResponse == null");
        }
        if (httpResponse == null) {
            throw new IllegalArgumentException("httpResponse == null");
        }

        // get/set the response code
        ResponseCode coapCode = coapResponse.getCode();
        String httpCodeString = HTTP_TRANSLATION_PROPERTIES.getProperty(KEY_COAP_CODE + coapCode.value);

        if (httpCodeString == null || httpCodeString.isEmpty()) {
            LOGGER.warning("httpCodeString == null");
            throw new TranslationException("httpCodeString == null");
        }

        int httpCode = 0;
        try {
            httpCode = Integer.parseInt(httpCodeString.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning("Cannot convert the coap code in http status code" + e);
            throw new TranslationException("Cannot convert the coap code in http status code", e);
        }
        // create the http response and set the status line
        httpResponse.setStatus(httpCode);
        // set the headers
        setHttpHeaders(coapResponse.getOptions().asSortedList(), httpResponse);

        // set max-age if not already set
        if (!httpResponse.containsHeader("cache-control")) {
            httpResponse.setHeader("cache-control", "max-age=" + Long.toString(OptionNumberRegistry.Defaults.MAX_AGE));
        }

        // get the http entity if the request was not HEAD
        if (!httpRequest.getMethod().equalsIgnoreCase("head")) {

            // if the content-type is not set in the coap response and if the
            // response contains an error, then the content-type should set to
            // text-plain
            if (coapResponse.getOptions().getContentFormat() == MediaTypeRegistry.UNDEFINED
                    && (ResponseCode.isClientError(coapCode)
                    || ResponseCode.isServerError(coapCode))) {
                LOGGER.info("Set contenttype to TEXT_PLAIN");
                coapResponse.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);
            }

            setHttpEntity(coapResponse, httpResponse);
        }
        LOGGER.info("Translated CoapResponse " + coapResponse);
//        LOGGER.info("To " + );
    }

    /**
     * Change charset.
     *
     * @param payload     the payload
     * @param fromCharset the from charset
     * @param toCharset   the to charset
     * @return the byte[] the translation
     */
    private static byte[] changeCharset(byte[] payload, Charset fromCharset, Charset toCharset) {
        return new String(payload, fromCharset).getBytes(toCharset);
    }

    /**
     * The Constructor is private because the class is an helper class and
     * cannot be instantiated.
     */
    private MyHttpTranslator() {

    }

}
