package ctbrec.sites.stripchat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;

import com.google.common.primitives.Bytes;
import com.iheartradio.m3u8.data.TrackData;

import java.util.Base64;

import ctbrec.recorder.download.hls.MouflonDecrypter;

public class StripchatMouflonDecrypter implements MouflonDecrypter {
    private final MessageDigest DIGEST;    
    private final String mKey;
    private final byte[] hash_bytes;
    
    public StripchatMouflonDecrypter(String key) throws NoSuchAlgorithmException {
        mKey = key;
        DIGEST = MessageDigest.getInstance("SHA-256");
        hash_bytes = computeHash(mKey);
    }
    
    private byte[] computeHash(String key) {
        DIGEST.reset();
        return DIGEST.digest(key.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String encrypted_b64) {
        var encrypted_data = Base64.getDecoder().decode((encrypted_b64).getBytes(StandardCharsets.UTF_8));

        List<Byte> decrypted_bytes = new ArrayList<>();
        for (var i = 0; i < encrypted_data.length; i++) {
            var decrypted_byte = (byte)(encrypted_data[i] ^ hash_bytes[i % hash_bytes.length]);
            decrypted_bytes.add(decrypted_byte);
        }

        return new String(Bytes.toArray(decrypted_bytes), StandardCharsets.UTF_8);
    }
    
    
}
