/*
 *    Copyright 2012 Weald Technology Trading Limited
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.wealdtech.hawk;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.wealdtech.DataError;
import com.wealdtech.ServerError;

import static com.wealdtech.Preconditions.*;

/**
 * The Hawk server. Note that this is not an HTTP server in itself, but provides
 * the backbone of any Hawk implementation within an HTTP server.
 */
public class HawkServer
{
  private static final Splitter WHITESPACESPLITTER = Splitter.onPattern("\\s+").limit(2);
  private static final Pattern FIELDPATTERN = Pattern.compile("([^=]*)\\s*=\\s*\"([^\"]*)[,\"\\s]*");
  private static final Pattern BEWITPATTERN = Pattern.compile("bewit=([^&]*)");
  private static final Splitter BEWITSPLITTER = Splitter.on('\\');
  private static final String BEWITREMOVEALMATCH = "bewit=[^&]*";

  private final HawkServerConfiguration configuration;
  private LoadingCache<String, Boolean> nonces;

  /**
   * Create an instance of the Hawk server with default configuration.
   */
  public HawkServer()
  {
    this.configuration = new HawkServerConfiguration.Builder().build();
    initializeCache();
  }

  /**
   * Create an instance of the Hawk server with custom configuration.
   *
   * @param configuration
   *          the specific configuration
   */
  public HawkServer(final HawkServerConfiguration configuration)
  {
    if (configuration == null)
    {
      this.configuration = new HawkServerConfiguration.Builder().build();
    }
    else
    {
      this.configuration = configuration;
    }
    initializeCache();
  }

  private final void initializeCache()
  {
    // TODO The cache does not have a maximum size, which could lead to a DDOS.
    // Consider the security/space trade-off
    this.nonces = CacheBuilder.newBuilder()
                              .expireAfterWrite(this.configuration.getTimestampSkew() * 2, TimeUnit.SECONDS)
                              .build(new CacheLoader<String, Boolean>() {
                                @Override
                                public Boolean load(String key) {
                                  return false;
                                }
                              });
  }

  /**
   * Authenticate a request using Hawk.
   * @param credentials the Hawk credentials against which to authenticate
   * @param uri the URI of the request
   * @param method the method of the request
   * @param authorizationheaders the Hawk authentication headers
   * @throws DataError if the authentication fails due to incorrect or missing data
   * @throws ServerError if there is a problem with the server whilst authenticating
   */
  public void authenticate(final HawkCredentials credentials, final URI uri, final String method, final ImmutableMap<String, String> authorizationheaders) throws DataError, ServerError
  {
    // Ensure that the required fields are present
    checkNotNull(authorizationheaders.get("ts"), "The timestamp was not supplied");
    checkNotNull(authorizationheaders.get("nonce"), "The nonce was not supplied");
    checkNotNull(authorizationheaders.get("id"), "The id was not supplied");
    checkNotNull(authorizationheaders.get("mac"), "The mac was not supplied");

    // Ensure that the timestamp passed in is within suitable bounds
    confirmTimestampWithinBounds(authorizationheaders.get("ts"));

    // Ensure that this is not a replay of a previous request
    confirmUniqueNonce(authorizationheaders.get("nonce"));

    final String mac = Hawk.calculateMAC(credentials, Hawk.AuthType.CORE, Long.valueOf(authorizationheaders.get("ts")), uri, authorizationheaders.get("nonce"), method, authorizationheaders.get("ext"));
    if (!timeConstantEquals(mac, authorizationheaders.get("mac")))
    {
      throw new DataError.Authentication("The MAC in the request does not match the server-calculated MAC");
    }
  }

  /**
   * Authenticate a request using a Hawk bewit.
   */
  public void authenticate(final HawkCredentials credentials, final URI uri)
  {
    final String bewit = extractBewit(uri);
    checkNotNull(bewit, ("The bewit was not supplied"));
    final String decodedBewit = new String(BaseEncoding.base64().decode(bewit));
    List<String> bewitfields = Lists.newArrayList(BEWITSPLITTER.split(decodedBewit));
    checkState((bewitfields.size() == 4), "The bewit did not contain the correct number of values");
    final String id = bewitfields.get(0);
    checkState((credentials.getKeyId().equals(id)), "The id in the bewit is not recognised");
    final Long expiry = Long.parseLong(bewitfields.get(1));
    checkState((System.currentTimeMillis() / 1000 <= expiry), "The bewit has expired");
    final String mac = bewitfields.get(2);
    final String ext = bewitfields.get(3);


    final URI strippedUri = stripBewit(uri);

    final String calculatedMac = Hawk.calculateMAC(credentials, Hawk.AuthType.BEWIT, expiry, strippedUri, null, null, ext);
    if (!timeConstantEquals(calculatedMac, mac))
    {
      throw new DataError.Authentication("The MAC in the request does not match the server-calculated MAC");
    }
  }

  // Strip the bewit query parameter from a URI
  private URI stripBewit(final URI uri)
  {
    String uristr = uri.toString().replaceAll(BEWITREMOVEALMATCH, "");
    // If the bewit was the first parameter...
    uristr = uristr.replaceAll("\\?&", "?");
    // If the bewit was the only parameter...
    uristr = uristr.replaceAll("\\?$", "");
    // If the bewit was a middle parameter...
    uristr = uristr.replaceAll("&&", "&");
    // If the bewit was the last parameter...
    uristr = uristr.replaceAll("&$", "");
    try
    {
      return new URI(uristr);
    }
    catch (URISyntaxException use)
    {
      throw new ServerError("Failed to remove bewit from query string");
    }
  }

  private void confirmUniqueNonce(final String nonce) throws DataError
  {
    if (this.nonces.getUnchecked(nonce) == true)
    {
      throw new DataError.Bad("The nonce supplied is the same as one seen previously");
    }
    this.nonces.put(nonce, true);
  }

  private void confirmTimestampWithinBounds(final String ts) throws DataError
  {
    Long timestamp;
    try
    {
      timestamp = Long.valueOf(ts);
    }
    catch (Exception e)
    {
      throw new DataError.Bad("The timestamp is in the wrong format; we expect seconds since the epoch");
    }
    long now = System.currentTimeMillis() / 1000;
    if (Math.abs(now - timestamp) > configuration.getTimestampSkew())
    {
      throw new DataError.Bad("The timestamp is too far from the current time to be acceptable");
    }
  }

  /**
   * Generate text for a WWW-Authenticate header after a failed authentication.
   * <p>
   * Note that this generates the header's contents, and not the header itself.
   *
   * @return text suitable for placement in a WWW-Authenticate header
   */
  public String generateAuthenticateHeader()
  {
    StringBuilder sb = new StringBuilder(64);
    sb.append("Hawk ts=\"");
    sb.append(String.valueOf(System.currentTimeMillis() / 1000));
    sb.append("\", ntp=\"");
    sb.append(this.configuration.getNtpServer());
    sb.append('"');

    return sb.toString();
  }

  private static boolean timeConstantEquals(final String first, final String second)
  {
    if ((first == null) || (second == null))
    {
      // null always returns false
      return false;
    }
    if (first.length() != second.length())
    {
      return false;
    }
    int res = 0;
    int l = first.length();
    for (int i = 0; i < l; i++)
    {
      if (first.charAt(i) != second.charAt(i))
      {
        res++;
      }
    }
    return (res == 0);
  }

  public ImmutableMap<String, String> splitAuthorizationHeader(final String authorizationheader) throws DataError
  {
    checkNotNull(authorizationheader, "No authorization header");
    List<String> headerfields = Lists.newArrayList(WHITESPACESPLITTER.split(authorizationheader));
    if (headerfields.size() != 2)
    {
      throw new DataError.Bad("The authorization header does not contain the expected number of fields");
    }
    if (!"hawk".equals(headerfields.get(0).toLowerCase(Locale.ENGLISH)))
    {
      throw new DataError.Bad("The authorization header is not a Hawk authorization header");
    }

    Map<String, String> fields = new HashMap<>();
    Matcher m = FIELDPATTERN.matcher(headerfields.get(1));
    while (m.find())
    {
      String key = m.group(1);
      String value = m.group(2);
      fields.put(key, value);
    }
    return ImmutableMap.copyOf(fields);
  }

  /**
   * Extract a bewit from a URI.
   * @param uri the URI from which to pull the bewit
   * @return the bewit
   * @throws DataError if there is an issue with the data that prevents obtaining the bewit
   */
  public String extractBewit(final URI uri)
  {
    checkNotNull(uri, "URI is required but not supplied");
    final String uristr = uri.toString();

    Matcher m = BEWITPATTERN.matcher(uristr);
    if (!m.find())
    {
      throw new DataError.Bad("The query string did not contain a bewit");
    }
    final String bewit = m.group(1);
    System.err.println("Bewit is " + bewit);
    return  bewit;
  }

}
