package ctbrec.sites.stripchat;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchAlgorithmException;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import ctbrec.ReflectionUtil;
import ctbrec.sites.mfc.MyFreeCamsClient;


public class CorruptionSchemeTest {

    @Test
    public void testMouflonDecrypter() throws Exception {
        var dec = new StripchatMouflonDecrypter("Quean4cai9boJa5a");
        
        assertEquals("221338608_480p_h264_6269_pjW0grfzLnLTWZ56_1761831772.mp4", dec.decrypt("06LeXTazQiecl6jrv3WfI6cgFPPH7/fpkcyLVYKC9Qub3IEiUdwuIpKXreS5NPh4pCEXnt+wseQ"));
        
        // repeatability test
        for (var i = 0; i<10; i++) {
            assertEquals("221414840_1080p_h264_8367_MV2f6MSlaTVeN4bS_1761846487.mp4", dec.decrypt("06LeWjS/TCOUl63jtzWwFP0kFpiu5fLm+eOsVICDsSCy/I46U+46I8abw+K4M/FzoSAUlMbzrKD6"));
        }
        // assertEquals("", dec.decrypt("2KPZXza/Ryf7+6jlvVqiLc9PTfmyh5i2hMSEWvan2FzWpt5WMbJEIp3m8aO7"));
    }

    @Test
    public void testPreprocessor() throws Exception {
        var dec = new StripchatMouflonDecrypter("Quean4cai9boJa5a");
        var pp = new StripchatPlaylistPreprocessor();
        
        assert(pp.checkText("93613430_3462_bfZYmUCZYfJxeXDB_1761849059.mp4"));        
        assertEquals("221414840_1080p_h264_8367_MV2f6MSlaTVeN4bS_1761846487.mp4", 
                    dec.decrypt("#EXT-X-MOUFLON:FILE:06LeWjS/TCOUl63jtzWwFP0kFpiu5fLm+eOsVICDsSCy/I46U+46I8abw+K4M/FzoSAUlMbzrKD6".substring(StripchatPlaylistPreprocessor.FILE_TAG.length())));
        
        var src =   "#EXTM3U\n" + //
                    "#EXT-X-VERSION:6\n" + //
                    "#EXT-X-MOUFLON:PSCH:v1:Zokee2OhPh9kugh4\n" + //
                    "#EXT-X-MOUFLON:FILE:0KXeXjy4TSKSl6jrv3WfI6cgFPPJ7PnokduQQcSn3R+4oN4Fdbkzdf6XreS5NPh4piMWnt+wseQ\n" + //
                    "https://media-hls.doppiocdn.net/b-hls-27/151093956/media.mp4\n" +
                    "https://media-hls.doppiocdn.net/b-hls-27/151093956/media.mp4";
        
        var dst =   "#EXTM3U\n" + //
                    "#EXT-X-VERSION:6\n" + //
                    "#EXT-X-MOUFLON:PSCH:v1:Zokee2OhPh9kugh4\n" + //
                    "#EXT-X-MOUFLON:FILE:0KXeXjy4TSKSl6jrv3WfI6cgFPPJ7PnokduQQcSn3R+4oN4Fdbkzdf6XreS5NPh4piMWnt+wseQ\n" + //
                    "https://media-hls.doppiocdn.net/b-hls-27/151093956/151093956_480p_h264_8188_gqCvBZrY01kp2GbZ_1761833562.mp4\n" +
                    "https://media-hls.doppiocdn.net/b-hls-27/151093956/media.mp4";
        
        // first media.mp4 is replaced, second is not (it has to have its own EXT-X-MOUFLON:FILE tag)
        assertEquals(dst, pp.process(src));
    }

    @Test
    public void testKeys() throws Exception {
        var dec = new StripchatMouflonDecrypter("ubahjae7goPoodi6");
            
        assertEquals("211447945_160p_h264_882_B19JmjjuqNqYsSvk_1766156180.mp4", 
                    dec.decrypt("#EXT-X-MOUFLON:FILE:KvFOeD3kux9KV2/vJvCyptaILDS5OacrVsecQSY9iM5pjg4VeoD0QCA5ae8gsdj41YYoRexxoQ".substring(StripchatPlaylistPreprocessor.FILE_TAG.length())));
                    
        // dec = new StripchatMouflonDecrypter("8iPRUU0AnxoOSif9");            
        // assertEquals("", 
        //             dec.decrypt("#EXT-X-MOUFLON:FILE:nCC76dtGzL/uqnhao9jxpeCL/KP8k9Z40aykFEE1uh2XXruxuxiU4ITEflqlmZv744j+0qnU0g".substring(StripchatPlaylistPreprocessor.FILE_TAG.length())));
    }

    @Test
    public void testV2() throws Exception {
        var dec = new StripchatMouflonDecrypter("EQueeGh2kaewa3ch"); // for Ook7quaiNgiyuhai
        // "giqjN1f4Bhojtukw6kwP2z" reverse => "z2Pwk6wkutjohB4f1Njqig"
        assertEquals("arzNCUOS3qWsGpDG", dec.decrypt("z2Pwk6wkutjohB4f1Njqig"));
    }

    // @Test
    public void testPreprocessorMaster() throws IOException {
        var dec = new StripchatPlaylistPreprocessor();
        
        var src =   "#EXTM3U\n" + //
                        "#EXT-X-VERSION:6\n" + //
                        "#EXT-X-MOUFLON:PSCH:v1:Zokee2OhPh9kugh4\n" + //
                        "#EXT-X-TARGETDURATION:2\n" + //
                        "#EXT-X-INDEPENDENT-SEGMENTS\n" + //
                        "#EXT-X-MEDIA-SEQUENCE:8112\n" + //
                        "#EXT-X-DISCONTINUITY-SEQUENCE:0\n" + //
                        "#EXT-X-MAP:URI=\"https://media-hls.doppiocdn.net/b-hls-22/173520285/173520285_init_N9UIPiAuUzvODCn4.mp4\"\n" + //
                        "#EXT-X-PROGRAM-DATE-TIME:2025-10-30T18:28:10.718+0000\n" + //
                        "#EXTINF:2.000\n" + //
                        "#EXT-X-MOUFLON:FILE:0KfcWze7Ri+Rl6TivjefPdlebcSku/aquo2GU8GUyzLQp9lfPb9ML534sr7/MQ\n" + //
                        "https://media-hls.doppiocdn.net/b-hls-22/173520285/media.mp4\n" + //
                        "#EXT-X-PROGRAM-DATE-TIME:2025-10-30T18:28:12.692+0000\n" + //
                        "#EXTINF:2.000\n" + //
                        "#EXT-X-MOUFLON:FILE:0KfcWze7Ri+Rl6TivjafJdZZSJy6kKeboNWtVOSX0zLQp9lfPb9ML536sr7/MQ\n" + //
                        "https://media-hls.doppiocdn.net/b-hls-22/173520285/media.mp4\n" + //
                        "#EXT-X-PROGRAM-DATE-TIME:2025-10-30T18:28:14.692+0000\n" + //
                        "#EXTINF:2.000\n" + //
                        "#EXT-X-MOUFLON:FILE:0KfcWze7Ri+Rl6TivjGfDdBnRu6miZihg4zVR8Co1jLQp9lfPb9ML538sr7/MQ\n" + //
                        "https://media-hls.doppiocdn.net/b-hls-22/173520285/media.mp4";
        
        var dst =   "";
        
        // first media.mp4 is replaced, second is not (it has to have its own EXT-X-MOUFLON:FILE tag)
        assertEquals(dst, dec.process(src));
    }
}
