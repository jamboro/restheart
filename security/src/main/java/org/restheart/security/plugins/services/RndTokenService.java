/*-
 * ========================LICENSE_START=================================
 * restheart-security
 * %%
 * Copyright (C) 2018 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.security.plugins.services;

import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.undertow.security.idm.Account;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import java.util.Map;
import org.restheart.ConfigurationException;
import org.restheart.exchange.JsonRequest;
import org.restheart.exchange.JsonResponse;
import org.restheart.idm.BaseAccount;
import org.restheart.plugins.ConfigurablePlugin;
import org.restheart.plugins.InjectConfiguration;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.JsonService;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import static org.restheart.plugins.security.TokenManager.AUTH_TOKEN_HEADER;
import static org.restheart.plugins.security.TokenManager.AUTH_TOKEN_LOCATION_HEADER;
import static org.restheart.plugins.security.TokenManager.AUTH_TOKEN_VALID_HEADER;
import org.restheart.utils.HttpStatus;

/**
 * allows to get and invalidate the user auth token generated by RndTokenManager
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
@RegisterPlugin(
        name = "rndTokenService",
        description = "allows to get and invalidate the user auth token generated by RndTokenManager",
        enabledByDefault = true,
        defaultURI = "/tokens"
)
public class RndTokenService implements JsonService {

    // used to compare the requested URI containing escaped chars
    private static final Escaper ESCAPER = UrlEscapers.urlPathSegmentEscaper();

    private Map<String, Object> confArgs = null;
    
    private PluginsRegistry pluginRegistry;
    
    @InjectPluginsRegistry
    public void setPluginRegistry(PluginsRegistry pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }
    
    /**
     * init the service
     * @param confArgs
     * @throws org.restheart.ConfigurationException
     */
    @InjectConfiguration
    public void init(Map<String, Object> confArgs)
            throws ConfigurationException {
        this.confArgs = confArgs;
    }

    /**
     * @param request JsonRequest
     * @param response JsonResponse
     * @throws Exception in case of any error
     */
    @Override
    public void handle(JsonRequest request, JsonResponse response) throws Exception {
        var exchange = request.getExchange();

        if (request.getPath().startsWith(getUri())
                && request.getPath().length() >= (getUri().length() + 2)
                && Methods.OPTIONS.equals(exchange.getRequestMethod())) {
            response.getHeaders().put(HttpString.tryFromString("Access-Control-Allow-Methods"), "GET, DELETE")
                    .put(HttpString.tryFromString("Access-Control-Allow-Headers"),
                            "Accept, Accept-Encoding, Authorization, Content-Length, "
                            + "Content-Type, Host, Origin, X-Requested-With, "
                            + "User-Agent, No-Auth-Challenge");

            response.setStatusCode(HttpStatus.SC_OK);
            return;
        }

        if (request.getAuthenticatedAccount() == null
                || request.getAuthenticatedAccount().getPrincipal() == null) {
            response.setStatusCode(HttpStatus.SC_UNAUTHORIZED);
            return;
        }

        if (!((getUri() + "/" + exchange.getSecurityContext()
                .getAuthenticatedAccount().getPrincipal().getName())
                .equals(exchange.getRequestURI()))
                && !(ESCAPER.escape(getUri() + "/" + exchange.getSecurityContext()
                        .getAuthenticatedAccount().getPrincipal().getName()))
                        .equals(exchange.getRequestURI())) {
            response.setStatusCode(HttpStatus.SC_FORBIDDEN);
            return;
        }

        if (Methods.GET.equals(exchange.getRequestMethod())) {
            JsonObject resp = new JsonObject();

            resp.add("auth_token", new JsonPrimitive(response.getHeader(AUTH_TOKEN_HEADER)));

            resp.add("auth_token_valid_until", new JsonPrimitive(response.getHeader(AUTH_TOKEN_VALID_HEADER)));

            response.setStatusCode(HttpStatus.SC_OK);
            response.setContent(resp);
        } else if (Methods.DELETE.equals(exchange.getRequestMethod())) {
            BaseAccount account = new BaseAccount(exchange.getSecurityContext()
                    .getAuthenticatedAccount().getPrincipal().getName(),
                    null);

            invalidate(account);

            removeAuthTokens(exchange);
            response.setStatusCode(HttpStatus.SC_NO_CONTENT);
        } else {
            response.setStatusCode(HttpStatus.SC_METHOD_NOT_ALLOWED);
        }
    }

    private void invalidate(Account account) {
        var tokenManager = this.pluginRegistry
                .getTokenManager();

        if (tokenManager == null) {
            throw new IllegalStateException("Error, cannot invalidate, "
                    + "token manager not active");
        }

        tokenManager.getInstance().invalidate(account);
    }

    private void removeAuthTokens(HttpServerExchange exchange) {
        exchange.getResponseHeaders().remove(AUTH_TOKEN_HEADER);
        exchange.getResponseHeaders().remove(AUTH_TOKEN_VALID_HEADER);
        exchange.getResponseHeaders().remove(AUTH_TOKEN_LOCATION_HEADER);
    }

    private String getUri() {
        if (confArgs == null) {
            return "/tokens";
        }

        try {
            return ConfigurablePlugin.argValue(confArgs, "uri");
        }
        catch (ConfigurationException ex) {
            return "/tokens";
        }
    }
}
