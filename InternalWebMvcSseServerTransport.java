/*
 * Copyright 2024-2024 the original author or authors.
 */

package com.agent.bookkeeper.application.base.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerResponse.SseBuilder;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Server-side implementation of the Model Context Protocol (MCP) transport layer using
 * HTTP with Server-Sent Events (SSE) through Spring WebMVC. This implementation provides
 * a bridge between synchronous WebMVC operations and reactive programming patterns to
 * maintain compatibility with the reactive transport interface.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Implements bidirectional communication using HTTP POST for client-to-server
 * messages and SSE for server-to-client messages</li>
 * <li>Manages client sessions with unique IDs for reliable message delivery</li>
 * <li>Supports graceful shutdown with proper session cleanup</li>
 * <li>Provides JSON-RPC message handling through configured endpoints</li>
 * <li>Includes built-in error handling and logging</li>
 * </ul>
 *
 * <p>
 * The transport operates on two main endpoints:
 * <ul>
 * <li>{@code /sse} - The SSE endpoint where clients establish their event stream
 * connection</li>
 * <li>A configurable message endpoint where clients send their JSON-RPC messages via HTTP
 * POST</li>
 * </ul>
 *
 * <p>
 * This implementation uses {@link ConcurrentHashMap} to safely manage multiple client
 * sessions in a thread-safe manner. Each client session is assigned a unique ID and
 * maintains its own SSE connection.
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see RouterFunction
 */
@Slf4j
public class InternalWebMvcSseServerTransport implements ServerMcpTransport {

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	private final ObjectMapper objectMapper;

	private final String messageEndpointPrefix;

	private final String sseEndpointPrefix;

    /**
     * -- GETTER --
     *  Returns the RouterFunction that defines the HTTP endpoints for this transport. The
     *  router function handles two endpoints:
     *  <ul>
     *  <li>GET /sse - For establishing SSE connections</li>
     *  <li>POST [messageEndpoint] - For receiving JSON-RPC messages from clients</li>
     *  </ul>
     *
     * @return The configured RouterFunction for handling HTTP requests
     */
    @Getter
    private final RouterFunction<ServerResponse> routerFunction;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * The function to process incoming JSON-RPC messages and produce responses.
	 */
	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	private UserContextSetter userContextSetter;

	/**
	 * Constructs a new WebMvcSseServerTransport instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param messageEndpointPrefix The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP POST. This endpoint will be communicated to clients through the
	 * SSE connection's initial endpoint event.
	 * @throws IllegalArgumentException if either objectMapper or messageEndpoint is null
	 */
	public InternalWebMvcSseServerTransport(ObjectMapper objectMapper, String messageEndpointPrefix, String sseEndpointPrefix, UserContextSetter userContextSetter) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(messageEndpointPrefix, "Message endpoint must not be null");
		Assert.notNull(sseEndpointPrefix, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
		this.messageEndpointPrefix = messageEndpointPrefix;
		this.sseEndpointPrefix = sseEndpointPrefix;
		this.routerFunction = RouterFunctions.route()
			.GET(this.sseEndpointPrefix + "/{token}", this::handleSseConnection)
			.POST(this.messageEndpointPrefix + "/{token}", this::handleMessage)
			.build();
		this.userContextSetter = userContextSetter;
	}

	/**
	 * Sets up the message handler for this transport. In the WebMVC SSE implementation,
	 * this method only stores the handler for later use, as connections are initiated by
	 * clients rather than the server.
	 * @param connectionHandler The function to process incoming JSON-RPC messages and
	 * produce responses
	 * @return An empty Mono since the server doesn't initiate connections
	 */
	@Override
	public Mono<Void> connect(
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectionHandler) {
		this.connectHandler = connectionHandler;
		// Server-side transport doesn't initiate connections
		return Mono.empty();
	}

	/**
	 * Broadcasts a message to all connected clients through their SSE connections. The
	 * message is serialized to JSON and sent as an SSE event with type "message". If any
	 * errors occur during sending to a particular client, they are logged but don't
	 * prevent sending to other clients.
	 * @param message The JSON-RPC message to broadcast to all connected clients
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			if (sessions.isEmpty()) {
				log.debug("No active sessions to broadcast message to");
				return;
			}

			try {
				String jsonText = objectMapper.writeValueAsString(message);
				log.debug("Attempting to broadcast message to {} active sessions", sessions.size());

				sessions.values().forEach(session -> {
					try {
						session.sseBuilder.id(session.id).event(MESSAGE_EVENT_TYPE).data(jsonText);
					}
					catch (Exception e) {
						log.error("Failed to send message to session {}: {}", session.id, e.getMessage());
						session.sseBuilder.error(e);
					}
				});
			}
			catch (IOException e) {
				log.error("Failed to serialize message: {}", e.getMessage());
			}
		});
	}

	/**
	 * Handles new SSE connection requests from clients by creating a new session and
	 * establishing an SSE connection. This method:
	 * <ul>
	 * <li>Generates a unique session ID</li>
	 * <li>Creates a new ClientSession with an SSE builder</li>
	 * <li>Sends an initial endpoint event to inform the client where to send
	 * messages</li>
	 * <li>Maintains the session in the sessions map</li>
	 * </ul>
	 * @param request The incoming server request
	 * @return A ServerResponse configured for SSE communication, or an error response if
	 * the server is shutting down or the connection fails
	 */
	private ServerResponse handleSseConnection(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String auth = request.path().substring(sseEndpointPrefix.length() + 1);
		log.debug("Creating new SSE connection for auth: {}", auth);

		// Send initial endpoint event
		try {
			return ServerResponse.sse(sseBuilder -> {

				ClientSession session = new ClientSession(auth, sseBuilder);
				this.sessions.put(auth, session);

				try {
					session.sseBuilder.id(session.id).event(ENDPOINT_EVENT_TYPE).data(messageEndpointPrefix + "/" + auth);
				}
				catch (Exception e) {
					log.error("Failed to poll event from session queue: {}", e.getMessage());
					sseBuilder.error(e);
				}
			});
		}
		catch (Exception e) {
			log.error("Failed to send initial endpoint event to auth {}: {}", auth, e.getMessage());
			sessions.remove(auth);
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. This method:
	 * <ul>
	 * <li>Deserializes the request body into a JSON-RPC message</li>
	 * <li>Processes the message through the configured connect handler</li>
	 * <li>Returns appropriate HTTP responses based on the processing result</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A ServerResponse indicating success (200 OK) or appropriate error status
	 * with error details in case of failures
	 */
	private ServerResponse handleMessage(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			String auth = request.path().substring(messageEndpointPrefix.length() + 1);

			userContextSetter.set(auth);

			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Convert the message to a Mono, apply the handler, and block for the
			// response
			@SuppressWarnings("unused")
			McpSchema.JSONRPCMessage response = Mono.just(message).transform(connectHandler).block();

			return ServerResponse.ok().build();
		}
		catch (IllegalArgumentException | IOException e) {
			log.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			log.error("Error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		} finally {
			userContextSetter.clear();
		}
	}

	/**
	 * Represents an active client session with its associated SSE connection. Each
	 * session maintains:
	 * <ul>
	 * <li>A unique session identifier</li>
	 * <li>An SSE builder for sending server events to the client</li>
	 * <li>Logging of session lifecycle events</li>
	 * </ul>
	 */
	private static class ClientSession {

		private final String id;

		private final SseBuilder sseBuilder;

		/**
		 * Creates a new client session with the specified ID and SSE builder.
		 * @param id The unique identifier for this session
		 * @param sseBuilder The SSE builder for sending server events to the client
		 */
		ClientSession(String id, SseBuilder sseBuilder) {
			this.id = id;
			this.sseBuilder = sseBuilder;
			log.debug("Session {} initialized with SSE emitter", id);
		}

		/**
		 * Closes this session by completing the SSE connection. Any errors during
		 * completion are logged but do not prevent the session from being marked as
		 * closed.
		 */
		void close() {
			log.debug("Closing session: {}", id);
			try {
				sseBuilder.complete();
				log.debug("Successfully completed SSE emitter for session {}", id);
			}
			catch (Exception e) {
				log.warn("Failed to complete SSE emitter for session {}: {}", id, e.getMessage());
				// sseBuilder.error(e);
			}
		}

	}

	/**
	 * Converts data from one type to another using the configured ObjectMapper. This is
	 * particularly useful for handling complex JSON-RPC parameter types.
	 * @param data The source data object to convert
	 * @param typeRef The target type reference
	 * @return The converted object of type T
	 * @param <T> The target type
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Initiates a graceful shutdown of the transport. This method:
	 * <ul>
	 * <li>Sets the closing flag to prevent new connections</li>
	 * <li>Closes all active SSE connections</li>
	 * <li>Removes all session records</li>
	 * </ul>
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			log.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

			sessions.values().forEach(session -> {
				String sessionId = session.id;
				session.close();
				sessions.remove(sessionId);
			});

			log.debug("Graceful shutdown completed");
		});
	}

}
