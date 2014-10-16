/*
 * Copyright 2013-2014, ApiFest project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apifest.oauth20;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apifest.oauth20.api.ExceptionEventHandler;
import com.apifest.oauth20.api.LifecycleHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Handler for requests received on the server.
 *
 * @author Rossitsa Borissova
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    protected static final String AUTH_CODE_GENERATE_URI = "/oauth20/auth-codes";
    protected static final String ACCESS_TOKEN_GENERATE_URI = "/oauth20/tokens";
    protected static final String ACCESS_TOKEN_VALIDATE_URI = "/oauth20/tokens/validate";
    protected static final String APPLICATION_URI = "/oauth20/applications";
    protected static final String ACCESS_TOKEN_REVOKE_URI = "/oauth20/tokens/revoke";
    protected static final String OAUTH_CLIENT_SCOPE_URI = "/oauth20/scopes";

    protected static final Pattern OAUTH_CLIENT_SCOPE_PATTERN = Pattern.compile("/oauth20/scopes/((\\p{Alnum}+-?_?)+$)");
    protected static final Pattern APPLICATION_PATTERN = Pattern.compile("/oauth20/applications/([a-f[0-9]]+)$");

    protected Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    protected static Logger accessTokensLog = LoggerFactory.getLogger("accessTokens");

    protected AuthorizationServer auth = new AuthorizationServer();

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
        final Channel channel = ctx.getChannel();
        Object message = e.getMessage();
        if (message instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) message;
            invokeRequestEventHandlers(req, null);

            HttpMethod method = req.getMethod();
            String rawUri = req.getUri();
            try {
                URI u = new URI(rawUri);
                rawUri = u.getRawPath();
            } catch (URISyntaxException e2) {
                log.error("URI syntax exception {}", rawUri);
                invokeExceptionHandler(e2, req);
            }

            HttpResponse response = null;
            if (APPLICATION_URI.equals(rawUri) && method.equals(HttpMethod.POST)) {
                response = handleRegister(req);
            } else if (AUTH_CODE_GENERATE_URI.equals(rawUri) && method.equals(HttpMethod.GET)) {
                response = handleAuthorize(req);
            } else if (ACCESS_TOKEN_GENERATE_URI.equals(rawUri) && method.equals(HttpMethod.POST)) {
                response = handleToken(req);
            } else if (ACCESS_TOKEN_VALIDATE_URI.equals(rawUri) && method.equals(HttpMethod.GET)) {
                response = handleTokenValidate(req);
            } else if (APPLICATION_URI.equals(rawUri) && method.equals(HttpMethod.GET)) {
                response = handleGetAllClientApplications(req);
            } else if (rawUri.startsWith(APPLICATION_URI) && method.equals(HttpMethod.GET)) {
                    response = handleGetClientApplication(req);
            } else if (ACCESS_TOKEN_REVOKE_URI.equals(rawUri) && method.equals(HttpMethod.POST)) {
                response = handleTokenRevoke(req);
            } else if (OAUTH_CLIENT_SCOPE_URI.equals(rawUri) && method.equals(HttpMethod.GET)) {
                response = handleGetAllScopes(req);
            } else if (OAUTH_CLIENT_SCOPE_URI.equals(rawUri) && method.equals(HttpMethod.POST)) {
                response = handleRegisterScope(req);
            } else if (rawUri.startsWith(OAUTH_CLIENT_SCOPE_URI) && method.equals(HttpMethod.PUT)) {
                response = handleUpdateScope(req);
            } else if (rawUri.startsWith(OAUTH_CLIENT_SCOPE_URI) && method.equals(HttpMethod.GET)) {
                response = handleGetScope(req);
            } else if (rawUri.startsWith(APPLICATION_URI) && method.equals(HttpMethod.PUT)) {
                response = handleUpdateClientApplication(req);
            } else if (rawUri.startsWith(OAUTH_CLIENT_SCOPE_URI) && method.equals(HttpMethod.DELETE)) {
                response = handleDeleteScope(req);
            } else {
                response = Response.createNotFoundResponse();
            }
            invokeResponseEventHandlers(req, response);
            ChannelFuture future = channel.write(response);
            future.addListener(ChannelFutureListener.CLOSE);
            return;

        } else {
            log.info("write response here from the BE");
        }
    }

    protected HttpResponse handleGetClientApplication(HttpRequest req) {
        HttpResponse response = null;
        Matcher m = APPLICATION_PATTERN.matcher(req.getUri());
        if (m.find()) {
            String clientId = m.group(1);
            ApplicationInfo appInfo = auth.getApplicationInfo(clientId);
            if (appInfo != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    String json = mapper.writeValueAsString(appInfo);
                    log.debug(json);
                    response = Response.createOkResponse(json);
                } catch (JsonGenerationException e) {
                    log.error("error get application info", e);
                    invokeExceptionHandler(e, req);
                } catch (JsonMappingException e) {
                    log.error("error get application info", e);
                    invokeExceptionHandler(e, req);
                } catch (IOException e) {
                    log.error("error get application info", e);
                    invokeExceptionHandler(e, req);
                }
            } else {
                response = Response.createResponse(HttpResponseStatus.NOT_FOUND, Response.CLIENT_APP_NOT_EXIST);
            }
        } else {
            response = Response.createNotFoundResponse();
        }
        return response;
    }

    protected HttpResponse handleTokenValidate(HttpRequest req) {
        HttpResponse response = null;
        QueryStringDecoder dec = new QueryStringDecoder(req.getUri());
        Map<String, List<String>> params = dec.getParameters();
        String tokenParam = QueryParameter.getFirstElement(params, "token");
        if (tokenParam == null || tokenParam.isEmpty()) {
            response = Response.createBadRequestResponse();
        } else {
            AccessToken token = auth.isValidToken(tokenParam);
            log.debug("token valid:" + token);
            if (token != null) {
                Gson gson = new Gson();
                String json = gson.toJson(token);
                log.debug(json);
                response = Response.createOkResponse(json);
            } else {
                response = Response.createUnauthorizedResponse();
            }
        }
        return response;
    }

    protected HttpResponse handleToken(HttpRequest request) {
        HttpResponse response = null;
        String contentType = request.headers().get(HttpHeaders.Names.CONTENT_TYPE);
        if (contentType.contains(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED)) {
            try {
                AccessToken accessToken = auth.issueAccessToken(request);
                if (accessToken != null) {
                    ObjectMapper mapper = new ObjectMapper();
                    String jsonString = mapper.writeValueAsString(accessToken);
                    log.debug("access token:" + jsonString);
                    response = Response.createOkResponse(jsonString);
                    accessTokensLog.debug("token {}", jsonString);
                }
            } catch (OAuthException ex) {
                response = Response.createOAuthExceptionResponse(ex);
                invokeExceptionHandler(ex, request);
            } catch (JsonGenerationException e1) {
                log.error("error handle token", e1);
                invokeExceptionHandler(e1, request);
            } catch (JsonMappingException e1) {
                log.error("error handle token", e1);
                invokeExceptionHandler(e1, request);
            } catch (IOException e1) {
                log.error("error handle token", e1);
                invokeExceptionHandler(e1, request);
            }
            if (response == null) {
                response = Response.createBadRequestResponse(Response.CANNOT_ISSUE_TOKEN);
            }
        } else {
            response = Response.createResponse(HttpResponseStatus.BAD_REQUEST, Response.UNSUPPORTED_MEDIA_TYPE);
        }
        return response;
    }

    protected void invokeRequestEventHandlers(HttpRequest request, HttpResponse response) {
        invokeHandlers(request, response, LifecycleEventHandlers.getRequestEventHandlers());
    }

    protected void invokeResponseEventHandlers(HttpRequest request, HttpResponse response) {
        invokeHandlers(request, response, LifecycleEventHandlers.getResponseEventHandlers());
    }

    protected void invokeExceptionHandler(Exception ex, HttpRequest request) {
        List<Class<ExceptionEventHandler>> handlers = LifecycleEventHandlers.getExceptionHandlers();
        for (int i = 0; i < handlers.size(); i++) {
            try {
                ExceptionEventHandler handler = handlers.get(i).newInstance();
                handler.handleException(ex, request);
            } catch (InstantiationException e) {
                log.error("cannot instantiate exception handler", e);
                invokeExceptionHandler(e, request);
            } catch (IllegalAccessException e) {
                log.error("cannot invoke exception handler", e);
                invokeExceptionHandler(ex, request);
            }
        }
    }

    protected void invokeHandlers(HttpRequest request, HttpResponse response, List<Class<LifecycleHandler>> handlers) {
        for (int i = 0; i < handlers.size(); i++) {
            try {
                LifecycleHandler handler = handlers.get(i).newInstance();
                handler.handle(request, response);
            } catch (InstantiationException e) {
                log.error("cannot instantiate handler", e);
                invokeExceptionHandler(e, request);
            } catch (IllegalAccessException e) {
                log.error("cannot invoke handler", e);
                invokeExceptionHandler(e, request);
            }
        }
    }

    protected HttpResponse handleAuthorize(HttpRequest req) {
        HttpResponse response = null;
        String contentType = req.headers().get(HttpHeaders.Names.CONTENT_TYPE);
        if (contentType.contains(HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED)) {
            try {
                String redirectURI = auth.issueAuthorizationCode(req);
                // TODO: validation http protocol?
                log.debug("redirectURI: {}", redirectURI);

                // return auth_code
                JsonObject obj = new JsonObject();
                obj.addProperty("redirect_uri", redirectURI);
                response = Response.createOkResponse(obj.toString());
                accessTokensLog.info("authCode {}", obj.toString());
            } catch (OAuthException ex) {
                response = Response.createOAuthExceptionResponse(ex);
                invokeExceptionHandler(ex, req);
            }
        } else {
            response = Response.createResponse(HttpResponseStatus.BAD_REQUEST, Response.UNSUPPORTED_MEDIA_TYPE);
        }
        return response;
    }

    protected HttpResponse handleRegister(HttpRequest req) {
        HttpResponse response = null;
        try {
            ClientCredentials creds = auth.issueClientCredentials(req);
            ObjectMapper mapper = new ObjectMapper();
            String jsonString = mapper.writeValueAsString(creds);
            log.debug("credentials:" + jsonString);
            response = Response.createOkResponse(jsonString);
        } catch (OAuthException ex) {
            response = Response.createOAuthExceptionResponse(ex);
            invokeExceptionHandler(ex, req);
        } catch (JsonGenerationException e1) {
            log.error("error handle register", e1);
            invokeExceptionHandler(e1, req);
        } catch (JsonMappingException e1) {
            log.error("error handle register", e1);
            invokeExceptionHandler(e1, req);
        } catch (IOException e1) {
            log.error("error handle register", e1);
            invokeExceptionHandler(e1, req);
        }
        if (response == null) {
            response = Response.createBadRequestResponse(Response.CANNOT_REGISTER_APP);
        }
        return response;
    }

    protected HttpResponse handleTokenRevoke(HttpRequest req) {
        boolean revoked = false;
        try {
            revoked = auth.revokeToken(req);
        } catch (OAuthException e) {
            log.error("cannot revoke token", e);
            invokeExceptionHandler(e, req);
        }
        String json = "{\"revoked\":\"" + revoked + "\"}";
        HttpResponse response = Response.createOkResponse(json);
        return response;
    }

    protected HttpResponse handleRegisterScope(HttpRequest req) {
        ScopeService scopeService = getScopeService();
        HttpResponse response = null;
        try {
            String responseMsg = scopeService.registerScope(req);
            response = Response.createOkResponse(responseMsg);
        } catch (OAuthException e) {
            invokeExceptionHandler(e, req);
            response = Response.createResponse(e.getHttpStatus(), e.getMessage());
        }
        return response;
    }

    protected HttpResponse handleUpdateScope(HttpRequest req) {
        HttpResponse response = null;
        Matcher m = OAUTH_CLIENT_SCOPE_PATTERN.matcher(req.getUri());
        if (m.find()) {
            String scopeName = m.group(1);
            ScopeService scopeService = getScopeService();
            try {
                String responseMsg = scopeService.updateScope(req, scopeName);
                response = Response.createOkResponse(responseMsg);
            } catch (OAuthException e) {
                invokeExceptionHandler(e, req);
                response = Response.createResponse(e.getHttpStatus(), e.getMessage());
            }
        } else {
            response = Response.createNotFoundResponse();
        }
        return response;
    }

    protected HttpResponse handleGetAllScopes(HttpRequest req) {
        ScopeService scopeService = getScopeService();
        HttpResponse response = null;
        try {
            String jsonString = scopeService.getScopes(req);
            response = Response.createOkResponse(jsonString);
        } catch (OAuthException e) {
            invokeExceptionHandler(e, req);
            response = Response.createResponse(e.getHttpStatus(), e.getMessage());
        }
        return response;
    }

    protected HttpResponse handleGetScope(HttpRequest req) {
        HttpResponse response = null;
        Matcher m = OAUTH_CLIENT_SCOPE_PATTERN.matcher(req.getUri());
        if (m.find()) {
            String scopeName = m.group(1);
            ScopeService scopeService = getScopeService();
            try {
                String responseMsg = scopeService.getScopeByName(scopeName);
                response = Response.createOkResponse(responseMsg);
            } catch (OAuthException e) {
                invokeExceptionHandler(e, req);
                response = Response.createResponse(e.getHttpStatus(), e.getMessage());
            }
        } else {
            response = Response.createNotFoundResponse();
        }
        return response;
    }

    protected HttpResponse handleDeleteScope(HttpRequest req) {
        HttpResponse response = null;
        Matcher m = OAUTH_CLIENT_SCOPE_PATTERN.matcher(req.getUri());
        if (m.find()) {
            String scopeName = m.group(1);
            ScopeService scopeService = getScopeService();
            try {
                String responseMsg = scopeService.deleteScope(scopeName);
                response = Response.createOkResponse(responseMsg);
            } catch (OAuthException e) {
                invokeExceptionHandler(e, req);
                response = Response.createResponse(e.getHttpStatus(), e.getMessage());
            }
        } else {
            response = Response.createNotFoundResponse();
        }
        return response;
    }

    protected ScopeService getScopeService() {
        return new ScopeService();
    }

    protected HttpResponse handleUpdateClientApplication(HttpRequest req) {
        HttpResponse response = null;
        Matcher m = APPLICATION_PATTERN.matcher(req.getUri());
        if (m.find()) {
            String clientId = m.group(1);
            try {
                if (auth.updateClientApp(req, clientId)) {
                    response = Response.createOkResponse("{\"status\":\"client application updated\"}");
                }
            } catch (OAuthException ex) {
                response = Response.createOAuthExceptionResponse(ex);
                invokeExceptionHandler(ex, req);
            }
        } else {
            response = Response.createNotFoundResponse();
        }
        return response;
    }

    protected HttpResponse handleGetAllClientApplications(HttpRequest req) {
        List<ClientCredentials> apps = DBManagerFactory.getInstance().getAllApplications();
        Gson gson = new Gson();
        String jsonString = gson.toJson(apps);
        return Response.createOkResponse(jsonString);
    }
}
