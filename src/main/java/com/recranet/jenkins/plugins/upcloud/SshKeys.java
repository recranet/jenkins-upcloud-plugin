package com.recranet.jenkins.plugins.upcloud;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.signature.KeyAlgorithm;
import com.trilead.ssh2.signature.KeyAlgorithmManager;
import hudson.util.Secret;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

final class SshKeys {

    private SshKeys() {}

    /** Derives the public key of SSH credentials in the format of an {@code authorized_keys} line. */
    static String publicKey(SSHUserPrivateKey credentials) throws IOException {
        List<String> privateKeys = credentials.getPrivateKeys();
        if (privateKeys.isEmpty()) {
            throw new IOException("SSH credentials '" + credentials.getId() + "' hold no private key");
        }
        KeyPair keyPair = PEMDecoder.decodeKeyPair(
                privateKeys.get(0).toCharArray(), Secret.toString(credentials.getPassphrase()));
        for (KeyAlgorithm<PublicKey, PrivateKey> algorithm : KeyAlgorithmManager.getSupportedAlgorithms()) {
            if (algorithm.supportsKey(keyPair.getPrivate())) {
                byte[] encoded = algorithm.encodePublicKey(keyPair.getPublic());
                // The encoding starts with the key type as a length-prefixed string.
                String type = new String(encoded, 4, ByteBuffer.wrap(encoded).getInt(), StandardCharsets.US_ASCII);
                return type + " " + Base64.getEncoder().encodeToString(encoded);
            }
        }
        throw new IOException("SSH credentials '" + credentials.getId() + "' hold a key of an unsupported type");
    }
}
