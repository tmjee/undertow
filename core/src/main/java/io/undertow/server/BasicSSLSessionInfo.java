package io.undertow.server;

import io.undertow.UndertowMessages;
import io.undertow.util.FlexBase64;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.CertificateException;
import javax.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.cert.Certificate;

/**
 * Basic SSL session information. This information is generally provided by a front end proxy.
 *
 * @author Stuart Douglas
 */
public class BasicSSLSessionInfo implements SSLSessionInfo {

    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    private final byte[] sessionId;
    private final String cypherSuite;
    private final java.security.cert.Certificate peerCertificate;
    private final X509Certificate certificate;

    /**
     *
     * @param sessionId The SSL session ID
     * @param cypherSuite The cypher suite name
     * @param certificate A string representation of the client certificate
     * @throws java.security.cert.CertificateException If the client cert could not be decoded
     * @throws CertificateException If the client cert could not be decoded
     */
    public BasicSSLSessionInfo(byte[] sessionId, String cypherSuite, String certificate) throws java.security.cert.CertificateException, CertificateException {
        this.sessionId = sessionId;
        this.cypherSuite = cypherSuite;

        if (certificate != null) {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            byte[] certificateBytes = certificate.getBytes(US_ASCII);
            ByteArrayInputStream stream = new ByteArrayInputStream(certificateBytes);
            peerCertificate = cf.generateCertificate(stream);
            this.certificate = X509Certificate.getInstance(certificateBytes);
        } else {
            this.peerCertificate = null;
            this.certificate = null;
        }
    }
    /**
     *
     * @param sessionId The Base64 encoded SSL session ID
     * @param cypherSuite The cypher suite name
     * @param certificate A string representation of the client certificate
     * @throws java.security.cert.CertificateException If the client cert could not be decoded
     * @throws CertificateException If the client cert could not be decoded
     */
    public BasicSSLSessionInfo(String sessionId, String cypherSuite, String certificate) throws java.security.cert.CertificateException, CertificateException {
        this(base64Decode(sessionId), cypherSuite, certificate);
    }

    @Override
    public byte[] getSessionId() {
        final byte[] copy = new byte[sessionId.length];
        System.arraycopy(sessionId, 0, copy, 0, copy.length);
        return copy;
    }

    @Override
    public String getCipherSuite() {
        return cypherSuite;
    }

    @Override
    public java.security.cert.Certificate[] getPeerCertificates(boolean forceRenegotiate) throws SSLPeerUnverifiedException {
        if (certificate == null) {
            throw UndertowMessages.MESSAGES.peerUnverified();
        }
        return new Certificate[]{peerCertificate};
    }

    @Override
    public X509Certificate[] getPeerCertificateChain(boolean forceRenegotiate) throws SSLPeerUnverifiedException {
        if (certificate == null) {
            throw UndertowMessages.MESSAGES.peerUnverified();
        }
        return new X509Certificate[]{certificate};
    }


    private static byte[] base64Decode(String sessionId) {
        try {
            ByteBuffer sessionIdBuffer = FlexBase64.decode(sessionId);
            byte[] sessionIdData;
            if (sessionIdBuffer.hasArray()) {
                sessionIdData = sessionIdBuffer.array();
            } else {
                sessionIdData = new byte[sessionIdBuffer.remaining()];
                sessionIdBuffer.get(sessionIdData);
            }
            return sessionIdData;
        } catch (IOException e) {
            throw new RuntimeException(e); //won't happen
        }
    }
}
