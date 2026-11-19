package ctbrec.sites.stripchat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import ctbrec.io.PlaylistPreprocessor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StripchatPlaylistPreprocessor extends PlaylistPreprocessor {
    public final static String FILE_TAG = "#EXT-X-MOUFLON:FILE:";
    public final static String URI_TAG = "#EXT-X-MOUFLON:URI:";
    public final static Pattern PSCH_TAG_PAT = Pattern.compile("^#EXT-X-MOUFLON:PSCH:(\\w+):(.+)$", Pattern.MULTILINE);
    public final static Pattern HLS_URI_PAT = Pattern.compile("URI=\"(.*?)\"");
    public final static Pattern V2_PART_PAT = Pattern.compile("_([^_]+)_(\\d+(?:_part\\d+)?)\\.mp4(?:[?#].*)?$");
    
    public StripchatPlaylistPreprocessor() {        
        super();
    }
    
    public boolean checkText(String decrypted) {
        try {
            URI.create("http://b/" + decrypted).toURL();
            return true;
        } catch (MalformedURLException | IllegalArgumentException e) {
            return false;
        }
    }
    
    @Override
    public String process(String input) throws IOException {
        String version = null;
        String key = null;
        StripchatMouflonDecrypter dec = null;
        boolean pschFound = false;
        
        // find mouflon lines and replace placeholder text in next line
        var lines = input.split("\r?\n");
        for (int i = 0; i < lines.length; ++i) {
            // check if this line sets new scheme.
            // TODO: find what multiple schemes mean
            var psch = PSCH_TAG_PAT.matcher(lines[i]);
            if (psch.matches()) {
                pschFound = true;
                var tversion = psch.group(1);
                var tkey = psch.group(2);
                
                // choose scheme if known
                var tdec = Stripchat.getMOUFLON_DECRYPTERS().get(new Stripchat.MouflonKey(tversion, tkey));
                
                if (tdec != null) {
                    version = tversion;
                    key = tkey;
                    dec = tdec;
                } else {
                    log.debug("Unknown playlist corruption scheme: {}:{}. Skipping", tversion, tkey);
                }
            } else if (lines[i].startsWith(FILE_TAG) && lines[i].length() > FILE_TAG.length() && i+1 < lines.length) {
                if (dec != null) {
                    var decrypted = dec.decrypt(lines[i].substring(FILE_TAG.length()));
                    
                    if (!checkText(decrypted)) {
                        // log.warn("Unable to properly decrypt MOUFLON:FILE:\n  PSCH: {}:{}\n  line: {}", version, key, lines[i]);
                        // continue;
                        throw new IOException("Unable to properly decrypt MOUFLON:FILE:\n  PSCH: " + version + ":" + key + "\n  line: " + lines[i]);
                    }
                    
                    lines[i+1] = lines[i+1].replace("media.mp4", decrypted);
                } else {
                    log.warn("Encountered a {} tag but no decryptor was chosen. This is probably because no known scheme was found", FILE_TAG);
                }
            } else if (lines[i].startsWith(URI_TAG) && lines[i].length() > URI_TAG.length() && i+1 < lines.length) {
                if (dec != null) {
                    String decryptedUri = null;
                    var matcher = V2_PART_PAT.matcher(lines[i].substring(URI_TAG.length()));
                    
                    if (matcher.find()) {
                        final var decCopy = dec;
                        decryptedUri = matcher.replaceFirst(m -> 
                            "_" + decCopy.decrypt(new StringBuilder(matcher.group(1)).reverse().toString())
                            + "_" + m.group(2) + ".mp4"
                        );
                            
                        if (lines[i+1].startsWith("#")) {
                            lines[i+1] = lines[i+1].replaceFirst(HLS_URI_PAT.pattern(), "URI=\"" + decryptedUri + '"');                        
                        } else {
                            // regular segment, whole line is just the URL
                            lines[i+1] = decryptedUri;
                        }                    
                    } else {
                        log.debug("Unknown MOUFLON-URI: {}", lines[i].substring(URI_TAG.length()));
                    }                    
                } else {
                    log.warn("Encountered a {} tag but no decryptor was chosen. This is probably because no known scheme was found", URI_TAG);
                }
            }
        } 
        
        if (pschFound && dec == null) {
            log.warn("Corruption scheme was specified but no decryptor chosen. Recording may not work");
        }
            
        return String.join("\n", lines);
    }
    
}
