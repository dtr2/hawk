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

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.wealdtech.DataError;
import com.wealdtech.ServerError;
import com.wealdtech.utils.StringUtils;

public class HawkClient
{
  private final HawkCredentials credentials;

  private final String pathPrefix;

  public HawkClient(final HawkCredentials credentials)
  {
    this(credentials, null);
  }

  @Inject
  public HawkClient(final HawkCredentials credentials, @Named(value="hawkpathprefix") final String pathPrefix)
  {
    this.credentials = credentials;
    this.pathPrefix = pathPrefix;
  }

  /**
   * Generate the value for the Hawk authorization header.
   *
   * @param uri the URI for the request
   * @param method the request for the method
   * @param hash a hash of the request's payload, or <code>null</code> if payload authentication is not required
   * @param ext extra data, or <code>null</code> if none
   * @return The value for the Hawk authorization header.
   * @throws DataError If there is a problem with the data passed in which makes it impossible to generate a valid authorization header
   * @throws ServerError If there is a server problem whilst generating the authorization header
   */
  public String generateAuthorizationHeader(final URI uri,
                                            final String method,
                                            final String hash,
                                            final String ext) throws DataError, ServerError
  {
    long timestamp = System.currentTimeMillis() / 1000;
    final String nonce = StringUtils.generateRandomString(6);
    final String mac = Hawk.calculateMAC(this.credentials, Hawk.AuthType.HEADER, timestamp, uri, nonce, method, hash, ext);

    final StringBuilder sb = new StringBuilder(1024);
    sb.append("Hawk id=\"");
    sb.append(this.credentials.getKeyId());
    sb.append("\", ts=\"");
    sb.append(timestamp);
    sb.append("\", nonce=\"");
    sb.append(nonce);
    if ((ext != null) && (!"".equals(ext)))
    {
      sb.append("\", ext=\"");
      sb.append(ext);
    }
    sb.append("\", mac=\"");
    sb.append(mac);
    sb.append('"');

    return sb.toString();
  }

  public boolean isValidFor(final String path)
  {
    return ((this.pathPrefix == null) ||
            ((path == null) || (path.startsWith(this.pathPrefix))));
  }
}
