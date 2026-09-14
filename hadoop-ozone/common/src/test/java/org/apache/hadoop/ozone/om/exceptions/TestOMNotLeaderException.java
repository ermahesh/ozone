/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.exceptions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.apache.hadoop.util.StringUtils;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.NotLeaderException;
import org.junit.jupiter.api.Test;

/**
 * Test {@link OMNotLeaderException}.
 */
public class TestOMNotLeaderException {

  private static final RaftPeerId CURRENT_PEER = RaftPeerId.valueOf("om1");
  private static final RaftPeerId SUGGESTED_LEADER = RaftPeerId.valueOf("om2");

  @Test
  public void noStackTrace() {
    assertArrayEquals(new StackTraceElement[0],
        new OMNotLeaderException(CURRENT_PEER).getStackTrace());
    assertArrayEquals(new StackTraceElement[0],
        new OMNotLeaderException(CURRENT_PEER, SUGGESTED_LEADER).getStackTrace());
    assertArrayEquals(new StackTraceElement[0],
        new OMNotLeaderException("om1 is not the leader.").getStackTrace());
  }

  @Test
  public void convertedExceptionHasNoStackTrace() {
    RaftPeer leader = RaftPeer.newBuilder()
        .setId(SUGGESTED_LEADER)
        .setAddress("om2:9872")
        .build();
    NotLeaderException notLeaderException = new NotLeaderException(
        RaftGroupMemberId.valueOf(CURRENT_PEER, RaftGroupId.randomId()),
        leader, Collections.emptyList());

    OMNotLeaderException ex = OMNotLeaderException
        .convertToOMNotLeaderException(notLeaderException, CURRENT_PEER);

    assertArrayEquals(new StackTraceElement[0], ex.getStackTrace());
    assertEquals(SUGGESTED_LEADER.toString(), ex.getSuggestedLeaderNodeId());
    assertEquals("om2:9872", ex.getSuggestedLeaderAddress());
  }

  /**
   * The RPC server ships {@link StringUtils#stringifyException} output to the
   * client as the {@code RemoteException} message, so that text is what the
   * client ends up logging on every failover.
   */
  @Test
  public void stringifiedExceptionIsASingleLine() {
    OMNotLeaderException ex = new OMNotLeaderException(CURRENT_PEER,
        SUGGESTED_LEADER, "om2:9872");

    String stringified = StringUtils.stringifyException(ex);
    String[] lines = stringified.split("\\R");

    assertEquals(1, lines.length, "expected no stack frames in: " + stringified);
    assertTrue(lines[0].contains("OM:om1 is not the leader."), lines[0]);
    assertTrue(lines[0].contains("Suggested leader is OM:om2[om2:9872]."), lines[0]);
  }
}
