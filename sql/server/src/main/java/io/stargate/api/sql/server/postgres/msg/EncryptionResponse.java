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
package io.stargate.api.sql.server.postgres.msg;

import io.netty.buffer.ByteBuf;

public class EncryptionResponse extends PGServerMessage {
  private final boolean accepted;

  public EncryptionResponse(boolean accepted) {
    this.accepted = accepted;
  }

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void write(ByteBuf out) {
    if (accepted) {
      out.writeByte('S');
    } else {
      out.writeByte('N');
    }
  }
}