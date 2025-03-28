# 概述
能直接让使用openapi（swagger）作为接口文档的接口支持mcp，应用成为一个mcp server。

* 多Session支持
* 认证支持

价值在于有mcp client（如cursor，claude desktop等）就能完成业务操作，而不用写前端页面（Claude Sonnet 3.7实测无敌）
另外未来一定有基于AI的超级app出现。在基于语言的新的交互方式下，我们只需要写后端接口就行了

# 要求
* Java 17+
* SpringBoot
* SpringMVC

# 快速开始
1. 加入依赖，mcp原生依赖
```xml
<dependency>
  <groupId>io.modelcontextprotocol.sdk</groupId>
  <artifactId>mcp-spring-webmvc</artifactId>
  <version>0.7.0</version>
</dependency>
```

2. 复制代码
`InternalWebMvcSseServerTransport.java`
`McpConfig.java`
`RestApiToolProvider.java`
`UserContextSetter.java`

3. 实现`UserContextSetter`接口
Demo
```java
@Component
@AllArgsConstructor
public class MyUserContextSetter implements UserContextSetter{

    private JwtConfig jwtConfig;

    @Override
    public void set(String auth) {
        UserContext.set(jwtConfig.getUserContextFromTokenWithoutException(auth));
    }

    @Override
    public void clear() {
        UserContext.clear();
    }
}
```

4. 补充其他基础设施（拦截器等）以满足业务需求

5. 客户端Demo
```ts
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";

const transport = new SSEClientTransport(new URL("http://localhost:8080/mcp/sse/eyJhbGciOiJIUzI1NiJ9.eyJ0ZW5hbnRfaWQiOjIsImlzX2FkbWluIjpmYWxzZSwic3ViIjoiMiIsImlhdCI6MTc0MzA4MTE5MiwiZXhwIjoxNzQzMTY3NTkyfQ.newz2wj0l5nWk1U_lHNg575bi1FubWSLXqji4o2W-P0"));

const client = new Client(
  {
    name: "MCP Server",
    version: "1.0.0"
  }
);

await client.connect(transport);

const tools = await client.listTools();
// List all tools
console.log("Tools: ", JSON.stringify(tools));

// Call a tool
const result = await client.callTool({
  name: "TenantController.pageUser",
  arguments: {
    current: 1,
    pageSize: 10
  }
});

console.log("Result: ", JSON.stringify(result));
```
