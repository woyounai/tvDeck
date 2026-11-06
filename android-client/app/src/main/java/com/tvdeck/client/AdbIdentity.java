package com.tvdeck.client;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

final class AdbIdentity extends AbsAdbConnectionManager {
    private final Context context;
    private final PrivateKey key;
    private final Certificate certificate;

    AdbIdentity(Context context) throws Exception {
        this.context=context.getApplicationContext();
        AtomicFile identity = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity"));
        PrivateKey loadedKey;
        Certificate loadedCertificate;
        if (identity.getBaseFile().exists()) {
            try (DataInputStream in = new DataInputStream(identity.openRead())) {
                int keySize = in.readInt();
                if (keySize <= 0 || keySize > 8192) throw new IOException("Invalid identity");
                byte[] bytes = new byte[keySize]; in.readFully(bytes);
                loadedKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
                loadedCertificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair(); loadedKey = pair.getPrivate();
            long now = System.currentTimeMillis();
            X500Name name = new X500Name("CN=TVDeck");
            loadedCertificate = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(name, new BigInteger(128, new SecureRandom()),
                    new Date(now - 86400000L), new Date(now + 10L * 365 * 86400000L), name, pair.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(loadedKey)));
            FileOutputStream file = identity.startWrite();
            try {
                DataOutputStream out = new DataOutputStream(file);
                byte[] encoded = loadedKey.getEncoded(); out.writeInt(encoded.length); out.write(encoded);
                out.write(loadedCertificate.getEncoded()); out.flush(); identity.finishWrite(file);
            } catch (Exception error) { identity.failWrite(file); throw error; }
        }
        key = loadedKey; certificate = loadedCertificate;
        setApi(35); setTimeout(60, java.util.concurrent.TimeUnit.SECONDS);
    }
    @Override protected javax.net.SocketFactory getSocketFactory(String host) {return LanRouting.socketFactory(context,host);}
    @Override protected PrivateKey getPrivateKey() { return key; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "TVDeck"; }
}
