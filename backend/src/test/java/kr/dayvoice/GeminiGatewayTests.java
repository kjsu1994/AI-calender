package kr.dayvoice;

import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeminiGatewayTests {
    AppConfig.Settings settings(String key) {
        return new AppConfig.Settings("http://localhost:8085",true,"","","","",key,"test-model",System.getenv().getOrDefault("FFMPEG_PATH","ffmpeg"));
    }
    @Test void missingKeyDoesNotContactProvider() {
        var http=mock(HttpGateway.class);var gemini=new Gemini(settings(""),http);
        var failure=assertThrows(ApiException.class,()->gemini.interpret("내일 일정",null,"{}"));
        assertTrue(failure.getMessage().contains("GEMINI_API_KEY"));verifyNoInteractions(http);
    }
    @Test void quotaErrorDoesNotExposeProviderPayloadOrKey() {
        var http=mock(HttpGateway.class);var gemini=new Gemini(settings("private-key"),http);
        when(http.send(anyString(),anyString(),anyString(),anyMap())).thenReturn(new HttpGateway.Reply(429,Json.read("{\"error\":\"private-provider-detail\"}")));
        var failure=assertThrows(ApiException.class,()->gemini.interpret("내일 일정",null,"{}"));
        assertTrue(failure.getMessage().contains("사용량 제한"));assertFalse(failure.getMessage().contains("private"));
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FFMPEG_PATH",matches=".+")
    void realAudioConversionProducesWavInlineInputAndStructuredResponse() {
        var http=mock(HttpGateway.class);var gemini=new Gemini(settings("test-key"),http);
        when(http.send(anyString(),anyString(),anyString(),anyMap())).thenAnswer(call -> {
            var body=Json.read(call.getArgument(2));
            var audio=body.path("contents").path(0).path("parts").path(0).path("inline_data");
            assertEquals("audio/wav",Json.str(audio,"mime_type"));
            byte[] converted=Base64.getDecoder().decode(Json.str(audio,"data"));
            assertEquals("RIFF",new String(Arrays.copyOf(converted,4),java.nio.charset.StandardCharsets.US_ASCII));
            assertTrue(converted.length>30000);
            assertEquals("application/json",Json.str(body.path("generationConfig"),"responseMimeType"));
            var parsed=Json.object().put("action","READ").put("transcript","오늘 일정 알려 줘");
            var response=Map.of("candidates",List.of(Map.of("content",Map.of("parts",List.of(Map.of("text",Json.write(parsed)))))));
            return new HttpGateway.Reply(200,Json.read(Json.write(response)));
        });
        var result=gemini.interpret("",sineWave(),"{}");
        assertEquals("READ",Json.str(result,"action"));
    }
    private byte[] sineWave() {
        int samples=16000;
        ByteBuffer wav=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36+samples*2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
            .putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(samples*2);
        for(int i=0;i<samples;i++)wav.putShort((short)(Math.sin(i*2*Math.PI*440/16000)*8000));
        return wav.array();
    }
}
