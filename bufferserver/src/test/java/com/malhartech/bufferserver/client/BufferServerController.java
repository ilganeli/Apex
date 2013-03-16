/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.bufferserver.client;

import com.malhartech.bufferserver.Buffer.Message;
import java.io.IOException;
import malhar.netlet.DefaultEventLoop;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class BufferServerController extends AbstractSocketSubscriber<Message>
{
  private final String sourceId;
  public long windowId;
  public Message data;

  public BufferServerController(String sourceId)
  {
    this.sourceId = sourceId;
  }

  public void purge()
  {
    data = null;
    write(ClientHandler.getPurgeRequest(sourceId, windowId));
  }

  public void reset()
  {
    data = null;
    write(ClientHandler.getResetRequest(sourceId, windowId));
  }

  @Override
  public void onMessage(Message data)
  {
    this.data = data;
  }

  @Override
  public void handleException(Exception cce, DefaultEventLoop el)
  {
    if (cce instanceof IOException) {
      el.disconnect(this);
    }
    else {
      throw new RuntimeException(cce);
    }
  }

}