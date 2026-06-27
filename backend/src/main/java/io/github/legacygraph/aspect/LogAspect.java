package io.github.legacygraph.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.annotation.Log;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

/**
 * 操作日志切面
 * 记录请求入参、返回结果、执行时间等信息
 */
@Slf4j
@Aspect
@Component
public class LogAspect {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 切入点：所有标注 @Log 注解的方法
     */
    @Pointcut("@annotation(io.github.legacygraph.annotation.Log)")
    public void logPointcut() {
    }

    @Around("logPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Log logAnnotation = method.getAnnotation(Log.class);
        
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = method.getName();
        String operation = logAnnotation.value();
        Log.OperationType operationType = logAnnotation.type();

        // 获取请求信息
        String requestUri = "";
        String httpMethod = "";
        String ip = "";
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                requestUri = request.getRequestURI();
                httpMethod = request.getMethod();
                ip = getClientIp(request);
            }
        } catch (Exception e) {
            log.warn("获取请求信息失败", e);
        }

        // 记录请求参数
        if (logAnnotation.logParams()) {
            try {
                Object[] args = joinPoint.getArgs();
                String[] paramNames = signature.getParameterNames();
                StringBuilder paramsBuilder = new StringBuilder();
                for (int i = 0; i < args.length && i < paramNames.length; i++) {
                    if (args[i] != null && !isSensitiveParam(paramNames[i])) {
                        String paramValue = toJsonString(args[i]);
                        paramsBuilder.append(paramNames[i]).append("=").append(paramValue).append(", ");
                    }
                }
                String params = paramsBuilder.length() > 0 
                    ? paramsBuilder.substring(0, paramsBuilder.length() - 2) 
                    : "";
                
                log.info("[{}] [开始] {} - {}#{}, URI={}, IP={}, 参数=[{}]", 
                    traceId, operationType, className, methodName, requestUri, ip, params);
            } catch (Exception e) {
                log.warn("[{}] 记录请求参数失败", traceId, e);
            }
        } else {
            log.info("[{}] [开始] {} - {}#{}, URI={}, IP={}", 
                traceId, operationType, className, methodName, requestUri, ip);
        }

        Object result = null;
        Throwable throwable = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            throwable = t;
            throw t;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            
            // 记录返回结果
            if (logAnnotation.logResult() && result != null) {
                try {
                    String resultStr = toJsonString(result);
                    if (resultStr.length() > 2000) {
                        resultStr = resultStr.substring(0, 2000) + "...(truncated)";
                    }
                    log.info("[{}] [结束] {}#{}, 耗时={}ms, 返回={}", 
                        traceId, className, methodName, duration, resultStr);
                } catch (Exception e) {
                    log.warn("[{}] 记录返回结果失败", traceId, e);
                }
            } else {
                log.info("[{}] [结束] {}#{}, 耗时={}ms", traceId, className, methodName, duration);
            }
            
            // 慢请求告警
            if (duration > logAnnotation.slowRequestThreshold()) {
                log.warn("[{}] [慢请求] {}#{}, 耗时={}ms, 阈值={}ms, URI={}", 
                    traceId, className, methodName, duration, logAnnotation.slowRequestThreshold(), requestUri);
            }
            
            // 记录异常
            if (throwable != null) {
                log.error("[{}] [异常] {}#{}, 异常={}, 消息={}", 
                    traceId, className, methodName, throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
            }
        }
    }

    /**
     * 判断是否为敏感参数（密码、token等）
     */
    private boolean isSensitiveParam(String paramName) {
        if (paramName == null) {
            return false;
        }
        String lowerName = paramName.toLowerCase();
        return lowerName.contains("password") 
            || lowerName.contains("token") 
            || lowerName.contains("secret")
            || lowerName.contains("key")
            || lowerName.contains("credential");
    }

    /**
     * 对象转JSON字符串（处理异常情况）
     */
    private String toJsonString(Object obj) {
        try {
            if (obj == null) {
                return "null";
            }
            if (obj instanceof String) {
                return (String) obj;
            }
            if (obj.getClass().isPrimitive() || obj instanceof Number || obj instanceof Boolean) {
                return String.valueOf(obj);
            }
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理时取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
