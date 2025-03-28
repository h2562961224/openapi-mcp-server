package com.agent.bookkeeper.application.base.mcp;

import com.agent.bookkeeper.common.utils.ObjectConvertor;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.api.AbstractOpenApiResource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author chuzhen
 * @since 2025/3/27 17:24
 */
@Slf4j
public class RestApiToolProvider {

    private final RequestMappingHandlerMapping handlerMapping;
    private final AbstractOpenApiResource abstractOpenApiResource;
    private Method getOpenApiMethod;

    @SneakyThrows
    public RestApiToolProvider(RequestMappingHandlerMapping handlerMapping, AbstractOpenApiResource abstractOpenApiResource) {
        this.handlerMapping = handlerMapping;
        this.abstractOpenApiResource = abstractOpenApiResource;
        getOpenApiMethod = AbstractOpenApiResource.class.getDeclaredMethod("getOpenApi", Locale.class);
        getOpenApiMethod.setAccessible(true);
    }

    @SneakyThrows
    private OpenAPI getOpenApi() {
        // 通过反射调用父类的getOpenApi方法
        return (OpenAPI) getOpenApiMethod.invoke(abstractOpenApiResource, Locale.getDefault());
    }

    public List<McpServerFeatures.SyncToolRegistration> getTools() {
        // 获取所有的请求映射
        Map<RequestMappingInfo, HandlerMethod> mappings = handlerMapping.getHandlerMethods();

        List<McpServerFeatures.SyncToolRegistration> tools = Lists.newArrayListWithCapacity(mappings.size());

        OpenAPI openAPI = getOpenApi();
        Map<String, Schema> parameters = openAPI.getComponents().getSchemas();
        Map<String, PathItem> paths = openAPI.getPaths();
        Map<String, String> tags = openAPI.getTags().stream().collect(Collectors.toMap(Tag::getName, Tag::getDescription));

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();

            // 在这里添加转换到MCP的逻辑
            McpServerFeatures.SyncToolRegistration syncToolRegistration = convertToMcpEndpoint(mappingInfo, handlerMethod, parameters, paths, tags);
            if (syncToolRegistration == null) {
                continue;
            }
            tools.add(syncToolRegistration);
        }
        return tools;
    }

    private McpServerFeatures.SyncToolRegistration convertToMcpEndpoint(RequestMappingInfo mappingInfo, HandlerMethod handlerMethod, Map<String, Schema> parameters, Map<String, PathItem> paths, Map<String, String> tags) {
        // 1. 提取端点基本信息
        String path = Optional.ofNullable(mappingInfo.getPathPatternsCondition()).map(PathPatternsRequestCondition::getPatterns).map(Iterable::iterator).map(Iterator::next).map(PathPattern::getPatternString).orElse(null);
        if (StringUtils.isBlank(path)) {
            return null;
        }
        PathItem pathItem = paths.get(path);
        if (Objects.isNull(pathItem)) {
            return null;
        }
        String name = handlerMethod.getMethod().getDeclaringClass().getSimpleName() + "." + handlerMethod.getMethod().getName();
        String tag = pathItem.getPost().getTags().stream().map(tags::get).filter(Objects::nonNull).collect(Collectors.joining(";"));
        String methodDescription = tag + "\n" + pathItem.getPost().getDescription();
        String paramRef = Optional.ofNullable(pathItem.getPost()).map(Operation::getRequestBody).map(RequestBody::getContent).map(map -> map.get("application/json")).map(MediaType::getSchema).map(Schema::get$ref).orElse(null);
        if (StringUtils.isBlank(paramRef)) {
            return null;
        }
        String paramName = paramRef.substring(paramRef.lastIndexOf("/") + 1);
        Schema schema = parameters.get(paramName);
        Parameter parameter = handlerMethod.getMethodParameters()[0].getParameter();
        // 转成 parameter
        // 调用 handlerMethod
        HandlerMethod finalHandlerMethod = handlerMethod.createWithResolvedBean();
        return new McpServerFeatures.SyncToolRegistration(
                new McpSchema.Tool(name, methodDescription, JSON.toJSONString(schema)),
                param -> {

                    Class paramClass = parameter.getType();
                    // 调用 handlerMethod
                    try {
                        // 转成 parameter
                        Object paramObject = ObjectConvertor.convertor(param, paramClass);
                        Object result = finalHandlerMethod.getMethod().invoke(finalHandlerMethod.getBean(), paramObject);
                        return new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent(JSON.toJSONString(result))), Boolean.FALSE);
                    } catch (Exception e) {
                        log.error("Error invoking method: {}, para:{}", name, param, e);
                        return new McpSchema.CallToolResult(Collections.singletonList(new McpSchema.TextContent("Error: " + e.getMessage())), Boolean.TRUE);
                    }
                }
        );
    }
}
