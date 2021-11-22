/*
 * Copyright The Stargate Authors
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
package io.stargate.sgv2.restsvc.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;

// Copy of "RESTResponseWrapper" of StargateV1
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Sgv2RESTResponse<T> {
  @JsonProperty("data")
  private T data;

  @ApiModelProperty(value = "Response data returned by the request.")
  public T getData() {
    return data;
  }

  public Sgv2RESTResponse setData(T data) {
    this.data = data;
    return this;
  }

  @JsonCreator
  public Sgv2RESTResponse(@JsonProperty("data") final T data) {
    this.data = data;
  }
}