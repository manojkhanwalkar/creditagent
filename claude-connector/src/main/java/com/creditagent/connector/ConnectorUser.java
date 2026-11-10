package com.creditagent.connector;

import com.creditagent.common.Ssn;
import java.util.Arrays;

/**
 * The user an MCP request acts for, from their access token. The SSN never comes from Claude: it is
 * what the web server stored inside the encrypted token when the user consented.
 *
 * @param accessToken the user's token, passed on to the web server for lock changes
 * @param resource the audience the token was issued for
 * @param scope the scopes granted, space separated
 */
public record ConnectorUser(String userId, Ssn ssn, String accessToken, String resource, String scope) {

    public boolean hasScope(String wanted) {
        return scope != null && Arrays.asList(scope.split(" ")).contains(wanted);
    }

    @Override
    public String toString() {
        return "ConnectorUser[userId=" + userId + "]";
    }
}
