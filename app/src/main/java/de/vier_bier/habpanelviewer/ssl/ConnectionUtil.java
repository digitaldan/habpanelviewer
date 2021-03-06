package de.vier_bier.habpanelviewer.ssl;

import android.content.Context;
import android.net.http.SslCertificate;
import android.os.Bundle;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.security.auth.x500.X500Principal;

import de.vier_bier.habpanelviewer.R;

/**
 * SSL related utility methods.
 */
public class ConnectionUtil {
    private static ConnectionUtil mInstance;

    private final String TRUSTSTORE_PASSWORD = "secret";
    private final ArrayList<CertChangedListener> mListeners = new ArrayList<>();

    private File localTrustStoreFile;
    private LocalTrustManager mTrustManager;
    private SSLContext mSslContext;

    private final AtomicBoolean mInitialized = new AtomicBoolean();
    private final CountDownLatch mInitLatch = new CountDownLatch(1);

    public static synchronized ConnectionUtil getInstance() {
        if (mInstance == null) {
            mInstance = new ConnectionUtil();
        }

        return mInstance;
    }

    public synchronized void setContext(Context ctx) throws GeneralSecurityException, IOException {
        if (!mInitialized.getAndSet(true)) {
            try {
                localTrustStoreFile = new File(ctx.getFilesDir(), "localTrustStore.bks");
                if (!localTrustStoreFile.exists()) {
                    try (InputStream in = ctx.getResources().openRawResource(R.raw.mytruststore)) {
                        copy(in, localTrustStoreFile);
                    }
                }

                System.setProperty("javax.net.ssl.trustStore", localTrustStoreFile.getAbsolutePath());

                SSLContext sslContext = createSslContext();
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            } finally {
                mInitLatch.countDown();
            }
        }
    }

    public synchronized void addCertificate(SslCertificate certificate) throws GeneralSecurityException, IOException {
        if (!mInitialized.get()) {
            throw new GeneralSecurityException("Certificate Store not yet initialized!");
        }
        KeyStore localTrustStore = loadTrustStore();
        X509Certificate x509Certificate = getX509CertFromSslCertHack(certificate);

        String alias = hashName(x509Certificate.getSubjectX500Principal());
        localTrustStore.setCertificateEntry(alias, x509Certificate);

        saveTrustStore(localTrustStore);

        // reset fields so the keystore gets read again
        mTrustManager = null;
        mSslContext = null;

        // notify listeners
        synchronized (mListeners) {
            for (CertChangedListener l : mListeners) {
                l.certAdded();
            }
        }
    }

    public void addCertListener(CertChangedListener l) {
        synchronized (mListeners) {
            if (!mListeners.contains(l)) {
                mListeners.add(l);
            }
        }
    }

    public void removeCertListener(CertChangedListener l) {
        synchronized (mListeners) {
            mListeners.remove(l);
        }
    }

    public synchronized HttpURLConnection createUrlConnection(final String urlStr) throws IOException, GeneralSecurityException {
        if (!mInitialized.get() && urlStr.toLowerCase().startsWith("https://")) {
            throw new GeneralSecurityException("Certificate Store not yet initialized!");
        }

        final URL url = new URL(urlStr);
        SSLContext sslCtx = createSslContext();
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        if (urlConnection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) urlConnection).setSSLSocketFactory(sslCtx.getSocketFactory());

            HostnameVerifier hostnameVerifier = (hostname, session) -> hostname.equalsIgnoreCase(url.getHost());
            ((HttpsURLConnection) urlConnection).setHostnameVerifier(hostnameVerifier);
        }
        urlConnection.setConnectTimeout(200);

        return urlConnection;
    }

    public synchronized boolean isTrusted(SslCertificate cert) {
        try {
            mInitLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (mTrustManager == null) {
            KeyStore trustStore = loadTrustStore();
            mTrustManager = new LocalTrustManager(trustStore);
        }

        try {
            mTrustManager.checkClientTrusted(new X509Certificate[]{getX509CertFromSslCertHack(cert)}, "generic");
        } catch (CertificateException e) {
            return false;
        }

        return true;
    }

    public synchronized SSLContext createSslContext() throws GeneralSecurityException {
        if (mSslContext != null) {
            return mSslContext;
        }

        if (mTrustManager == null) {
            KeyStore trustStore = loadTrustStore();
            mTrustManager = new LocalTrustManager(trustStore);
        }

        TrustManager[] tms = new TrustManager[]{mTrustManager};

        mSslContext = SSLContext.getInstance("TLS");
        mSslContext.init(null, tms, null);

        return mSslContext;
    }

    private KeyStore loadTrustStore() {
        try {
            KeyStore localTrustStore = KeyStore.getInstance("BKS");
            try (InputStream in = new FileInputStream(localTrustStoreFile)) {
                localTrustStore.load(in, TRUSTSTORE_PASSWORD.toCharArray());
            }

            return localTrustStore;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void copy(InputStream in, File dst) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            // Transfer bytes from in to out
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private X509Certificate getX509CertFromSslCertHack(SslCertificate sslCert) {
        X509Certificate x509Certificate;

        Bundle bundle = SslCertificate.saveState(sslCert);
        byte[] bytes = bundle.getByteArray("x509-certificate");

        if (bytes == null) {
            x509Certificate = null;
        } else {
            try {
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(bytes));
                x509Certificate = (X509Certificate) cert;
            } catch (CertificateException e) {
                x509Certificate = null;
            }
        }

        return x509Certificate;
    }

    private void saveTrustStore(KeyStore localTrustStore)
            throws IOException, GeneralSecurityException {
        FileOutputStream out = new FileOutputStream(localTrustStoreFile);
        localTrustStore.store(out, TRUSTSTORE_PASSWORD.toCharArray());
    }

    private String hashName(X500Principal principal) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    principal.getEncoded());

            String result = Integer.toString(leInt(digest), 16);
            if (result.length() > 8) {
                StringBuilder builder = new StringBuilder();
                int padding = 8 - result.length();
                for (int i = 0; i < padding; i++) {
                    builder.append("0");
                }
                builder.append(result);

                return builder.toString();
            }

            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private int leInt(byte[] bytes) {
        int offset = 0;
        return ((bytes[offset++] & 0xff))
                | ((bytes[offset++] & 0xff) << 8)
                | ((bytes[offset++] & 0xff) << 16)
                | ((bytes[offset] & 0xff) << 24);
    }

    public interface CertChangedListener {
        void certAdded();
    }
}

