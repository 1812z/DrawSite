package com.z1812.drawsite.common.exception;

import com.z1812.drawsite.common.api.ApiResponse;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotPermissionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleDisconnected(org.springframework.web.context.request.async.AsyncRequestNotUsableException exception) {
        // 连接已失效，不再写入响应
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("UNAUTHORIZED", "请先登录"));
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<ApiResponse<Void>> handleForbidden(Exception exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("FORBIDDEN", "没有操作权限"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "请求参数不合法"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, jakarta.servlet.http.HttpServletResponse response) {
        if (org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(exception)) return null;
        if (response.isCommitted() || (response.getContentType() != null && response.getContentType().startsWith("text/event-stream"))) {
            log.debug("Streaming response ended", exception);
            return null;
        }
        if (exception instanceof ErrorResponse error) {
            String message = switch (error.getStatusCode().value()) {
                case 400 -> "请求格式或参数不合法";
                case 404 -> "资源不存在";
                case 405 -> "不支持该请求方法";
                case 415 -> "不支持该请求内容类型";
                default -> "请求无法处理";
            };
            return ResponseEntity.status(error.getStatusCode())
                    .body(ApiResponse.error("HTTP_" + error.getStatusCode().value(), message));
        }
        log.error("Unhandled request exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }
}
