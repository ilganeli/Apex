/**
 * Copyright (C) 2015 DataTorrent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.stram.webapp;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * <p>WebServices class.</p>
 *
 * @since 0.9.0
 */
@Path(WebServices.PATH)
public class WebServices
{
  public static final String VERSION = "v2";
  public static final String PATH = "/ws";

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public JSONObject getVersion() throws JSONException
  {
    return new JSONObject("{\"version\": \"" + VERSION + "\"}");
  }
}
