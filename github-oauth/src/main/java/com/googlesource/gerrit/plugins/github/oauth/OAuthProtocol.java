package com.googlesource.gerrit.plugins.github.oauth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class OAuthProtocol {
  
  public static enum Scope {
    DEFAULT(""),
    USER("user"),
    USER_EMAIL("user:email"),
    USER_FOLLOW("user:follow"),
    PUBLIC_REPO("public_repo"),
    REPO("repo"),
    REPO_STATUS("repo_status"),
    DELETE_REPO("delete_repo"),
    NOTIFICATIONS("notifications"),
    GIST("gist");

    private final String value;

    public String getValue() {
      return value;
    }

    private Scope(final String value) {
      this.value = value;
    }
  }
  private static final String ME_SEPARATOR = ",";
  private static final Logger LOG = LoggerFactory
      .getLogger(OAuthProtocol.class);

  private final GitHubOAuthConfig config;
  private final HttpClient http;
  private final Gson gson;

  public static class AccessToken {
    public String access_token;
    public String token_type;

    public AccessToken() {
    }

    public AccessToken(String token) {
      this(token, "");
    }

    public AccessToken(String token, String type, Scope... scopes) {
      this();
      this.access_token = token;
      this.token_type = type;
    }

    @Override
    public String toString() {
      return "AccessToken [access_token=" + access_token + ", token_type="
          + token_type + "]";
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result =
          prime * result
              + ((access_token == null) ? 0 : access_token.hashCode());
      result =
          prime * result + ((token_type == null) ? 0 : token_type.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      AccessToken other = (AccessToken) obj;
      if (access_token == null) {
        if (other.access_token != null) return false;
      } else if (!access_token.equals(other.access_token)) return false;
      if (token_type == null) {
        if (other.token_type != null) return false;
      } else if (!token_type.equals(other.token_type)) return false;
      return true;
    }
  }

  @Inject
  public OAuthProtocol(GitHubOAuthConfig config, GitHubHttpProvider httpClientProvider, Gson gson) {
    this.config = config;
    this.http = httpClientProvider.get();
    this.gson = gson;
  }

  public void loginPhase1(HttpServletRequest request,
      HttpServletResponse response, Set<Scope> scopes) throws IOException {

    String scopesString = getScope(scopes);
    LOG.debug("Initiating GitHub Login for ClientId=" + config.gitHubClientId + " Scopes=" + scopesString);
    response.sendRedirect(String.format(
        "%s?client_id=%s%s&redirect_uri=%s&state=%s%s", config.gitHubOAuthUrl,
        config.gitHubClientId, scopesString,
        getURLEncoded(config.oAuthFinalRedirectUrl),
        me(), getURLEncoded(request.getRequestURI().toString())));
  }

  private String getScope(Set<Scope> scopes) {
    if(scopes.size() <= 0) {
      return "";
    }
    
    StringBuilder out = new StringBuilder();
    for (Scope scope : scopes) {
      if(out.length() > 0) {
        out.append(",");
      }
      out.append(scope.getValue());
    }
    return "&" + "scope=" + out.toString();
  }

  public static boolean isOAuthFinal(HttpServletRequest request) {
    return Strings.emptyToNull(request.getParameter("code")) != null;
  }
  
  public static boolean isOAuthFinalForOthers(HttpServletRequest request) {
    String targetUrl = getTargetUrl(request);
    if(targetUrl.equals(request.getRequestURI())) {
      return false;
    }
    
    return Strings.emptyToNull(request.getParameter("code")) != null;
  }

  public String me() {
    return "" + hashCode() + ME_SEPARATOR;
  }

  public static boolean isOAuthLogin(HttpServletRequest request) {
    return request.getRequestURI().indexOf(GitHubOAuthConfig.OAUTH_LOGIN) >= 0;
  }

  public static boolean isOAuthLogout(HttpServletRequest request) {
    return request.getRequestURI().indexOf(GitHubOAuthConfig.OAUTH_LOGOUT) >= 0;
  }

  public static boolean isOAuthRequest(HttpServletRequest httpRequest) {
    return OAuthProtocol.isOAuthLogin(httpRequest) || OAuthProtocol.isOAuthFinal(httpRequest);
  }

  public AccessToken loginPhase2(HttpServletRequest request,
      HttpServletResponse response) throws IOException {

    HttpPost post = null;

    post = new HttpPost(config.gitHubOAuthAccessTokenUrl);
    post.setHeader("Accept", "application/json");
    List<NameValuePair> nvps = new ArrayList<NameValuePair>();
    nvps.add(new BasicNameValuePair("client_id", config.gitHubClientId));
    nvps.add(new BasicNameValuePair("client_secret", config.gitHubClientSecret));
    nvps.add(new BasicNameValuePair("code", request.getParameter("code")));
    post.setEntity(new UrlEncodedFormEntity(nvps, Charsets.UTF_8));

    try {
      HttpResponse postResponse = http.execute(post);
      if (postResponse.getStatusLine().getStatusCode() != HttpURLConnection.HTTP_OK) {
        LOG.error("POST " + config.gitHubOAuthAccessTokenUrl
            + " request for access token failed with status "
            + postResponse.getStatusLine());
        response.sendError(HttpURLConnection.HTTP_UNAUTHORIZED,
            "Request for access token not authorised");
        EntityUtils.consume(postResponse.getEntity());
        return null;
      }

      AccessToken token =
          gson.fromJson(new InputStreamReader(postResponse.getEntity()
              .getContent(), "UTF-8"), AccessToken.class);
      return token;
    } catch (IOException e) {
      LOG.error("POST " + config.gitHubOAuthAccessTokenUrl
          + " request for access token failed", e);
      response.sendError(HttpURLConnection.HTTP_UNAUTHORIZED,
          "Request for access token not authorised");
      return null;
    }
  }

  private static String getURLEncoded(String url) {
    try {
      return URLEncoder.encode(url, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // UTF-8 is hardcoded, cannot fail
      return null;
    }
  }

  public static String getTargetUrl(ServletRequest request) {
    int meEnd = state(request).indexOf(ME_SEPARATOR);
    if (meEnd > 0) {
      return state(request).substring(meEnd+1);
    } else {
      return "";
    }
  }

  private static String state(ServletRequest request) {
    return Strings.nullToEmpty(request.getParameter("state"));
  }

  public static String getTargetOAuthFinal(HttpServletRequest httpRequest) {
    String targetUrl = getTargetUrl(httpRequest);
    String code = getURLEncoded(httpRequest.getParameter("code"));
    String state = getURLEncoded(httpRequest.getParameter("state"));
    return targetUrl + (targetUrl.indexOf('?') < 0 ? '?' : '&') + "code="
        + code + "&state=" + state;
  }

  @Override
  public String toString() {
    return "OAuthProtocol";
  }

}
