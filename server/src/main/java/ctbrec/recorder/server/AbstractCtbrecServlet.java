package ctbrec.recorder.server;

import ctbrec.Config;
import ctbrec.Hmac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

public abstract class AbstractCtbrecServlet extends HttpServlet {
    public static final String INTERNAL_SERVER_ERROR = "Internal Server Error";
    protected static final String HMAC_ERROR_DOCUMENT = "{\"status\": \"error\", \"msg\": \"HMAC does not match\"}";
    private static final Logger LOG = LoggerFactory.getLogger(AbstractCtbrecServlet.class);

    boolean checkAuthentication(HttpServletRequest req, String body) throws InvalidKeyException, NoSuchAlgorithmException {
        boolean authenticated;
        if (Config.getInstance().getSettings().key != null) {
            String reqParamHmac = req.getParameter("hmac");
            String httpHeaderHmac = req.getHeader("CTBREC-HMAC");
            String hmac = null;
            String url = req.getRequestURI();
            url = url.substring(getServletContext().getContextPath().length());

            if (reqParamHmac != null) {
                hmac = reqParamHmac;
            }
            if (httpHeaderHmac != null) {
                hmac = httpHeaderHmac;
            }

            byte[] key = Config.getInstance().getSettings().key;
            String msg = reqParamHmac != null ? url : body;
            authenticated = Hmac.validate(msg, key, hmac);
        } else {
            authenticated = true;
        }
        return authenticated;
    }

    boolean checkAuthentication(HttpServletRequest req, byte[] body) throws InvalidKeyException, NoSuchAlgorithmException {
        boolean authenticated;
        if (Config.getInstance().getSettings().key != null) {
            String reqParamHmac = req.getParameter("hmac");
            String httpHeaderHmac = req.getHeader("CTBREC-HMAC");
            String hmac = null;
            String url = req.getRequestURI();
            url = url.substring(getServletContext().getContextPath().length());

            if (reqParamHmac != null) {
                hmac = reqParamHmac;
            }
            if (httpHeaderHmac != null) {
                hmac = httpHeaderHmac;
            }

            byte[] key = Config.getInstance().getSettings().key;
            if (reqParamHmac != null) {
                authenticated = Hmac.validate(url, key, hmac);
            } else {
                authenticated = Hmac.validate(body, key, hmac);
            }
        } else {
            authenticated = true;
        }
        return authenticated;
    }


    String body(HttpServletRequest req) throws IOException {
        byte[] bytes = bodyAsByteArray(req);
        return new String(bytes, UTF_8);
    }

    byte[] bodyAsByteArray(HttpServletRequest req) throws IOException {
        int len = req.getContentLength();
        if (len < 0) {
            len = 1024;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(len);
        req.getInputStream().transferTo(bos);
        return bos.toByteArray();
    }

    void sendResponse(HttpServletResponse resp, int httpStatus, String message) {
        try {
            resp.setStatus(httpStatus);
            resp.setCharacterEncoding(UTF_8.toString());
            resp.getWriter().print(message);
        } catch (IOException e) {
            LOG.error("Couldn't write response", e);
            resp.setStatus(SC_INTERNAL_SERVER_ERROR);
        }
    }
}
