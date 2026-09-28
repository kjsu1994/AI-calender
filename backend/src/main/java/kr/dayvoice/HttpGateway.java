package kr.dayvoice;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;

@Component
class HttpGateway {
    record Reply(int status, JsonNode body) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    Reply send(String method, String url, String body, Map<String,String> headers) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(75));
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body().isBlank() ? Json.object() : Json.read(response.body()));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiException(503,"요청이 중단되었습니다. 다시 시도해 주세요."); }
        catch (Exception e) { throw new ApiException(503, "외부 서비스에 연결하지 못했습니다. 잠시 후 재시도해 주세요."); }
    }
    Reply form(String url, Map<String,String> fields) {
        return send("POST", url, fields.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&")),
            Map.of("Content-Type", "application/x-www-form-urlencoded"));
    }
}
