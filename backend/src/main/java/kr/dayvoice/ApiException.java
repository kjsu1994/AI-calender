package kr.dayvoice;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ApiException extends RuntimeException {
    final int status;
    ApiException(int status, String message) { super(message); this.status = status; }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missingResource(Exception e) { return ResponseEntity.status(404).body(Map.of("message", "요청한 주소를 찾을 수 없습니다. 서비스 접속 주소를 확인해 주세요.")); }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> known(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "입력 형식이나 파일 크기를 확인해 주세요.")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Request failed: {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("message", "처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
