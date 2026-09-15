package dev.pratik.poi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e, HttpServletRequest request) {
    return response(e.status, e.code, e.getMessage(), request);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
      MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
  ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
    return response(400, "INVALID_REQUEST", "Request body or fields are invalid.", request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> uploadTooLarge(Exception e, HttpServletRequest request) {
    return response(413, "UPLOAD_TOO_LARGE", "Workbook or multipart request exceeds its upload limit.", request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied(Exception e, HttpServletRequest request) {
    return response(403, "FORBIDDEN", "This role cannot perform that operation.", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e, HttpServletRequest request) {
    return response(
        409,
        "CONFLICT",
        "A concurrent command already changed this record. Refresh and try again.",
        request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
    return response(500, "INTERNAL_ERROR", "The request could not be completed.", request);
  }

  private ResponseEntity<?> response(
      int status, String code, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "requestId",
                String.valueOf(request.getAttribute("requestId"))));
  }
}
