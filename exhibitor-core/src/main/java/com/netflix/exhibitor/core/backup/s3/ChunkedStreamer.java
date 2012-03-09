/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.exhibitor.core.backup.s3;

import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

class ChunkedStreamer extends OutputStream
{
    private final List<ByteBuffer>  buffers = Lists.newArrayList();
    private final int               chunkSize;

    private ByteBuffer   currentBuffer = null;
    private State        state = State.OPEN;

    ChunkedStreamer(int chunkSize)
    {
        this.chunkSize = chunkSize;
        allocate();
    }

    private enum State
    {
        OPEN,
        PENDING_CLOSE,
        CLOSED
    }

    @Override
    public void write(int b) throws IOException
    {
        getBuffer().put((byte)(b & 0xff));
    }

    @Override
    public void write(byte[] b) throws IOException
    {
        super.write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException
    {
        while ( len > 0 )
        {
            ByteBuffer  buffer = getBuffer();

            int         thisLen = Math.min(buffer.remaining(), len);
            buffer.put(b, off, thisLen);
            off += thisLen;
            len -= thisLen;
        }
    }

    @Override
    public void close() throws IOException
    {
        if ( state == State.OPEN )
        {
            flush();
            state = State.PENDING_CLOSE;
        }
    }

    public boolean      isOpen()
    {
        if ( state == State.PENDING_CLOSE )
        {
            if ( buffers.size() == 0 )
            {
                state = State.CLOSED;
            }
        }
        return state != State.CLOSED;
    }

    public ByteBuffer getNextBuffer()
    {
        return (buffers.size() > 0) ? buffers.remove(0) : null;
    }

    @Override
    public void flush() throws IOException
    {
        if ( currentBuffer != null )
        {
            if ( currentBuffer.position() > 0 )
            {
                push();
            }
        }
    }

    private ByteBuffer       getBuffer()
    {
        if ( !currentBuffer.hasRemaining() )
        {
            push();
            allocate();
        }
        return currentBuffer;
    }

    private void push()
    {
        currentBuffer.flip();
        buffers.add(currentBuffer);
    }

    private void allocate()
    {
        currentBuffer = ByteBuffer.allocate(chunkSize);
    }
}
