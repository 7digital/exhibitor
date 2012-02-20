package com.netflix.exhibitor.core.backup.s3;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipCompressor implements Compressor
{
    private final int chunkSize;

    private static final int        DEFAULT_CHUNK_SIZE = 1024 * 1024;   // 1 MB

    public GzipCompressor()
    {
        this(DEFAULT_CHUNK_SIZE);
    }

    public GzipCompressor(int chunkSize)
    {
        this.chunkSize = chunkSize;
    }

    @Override
    public CompressorIterator compress(File f) throws Exception
    {
        final ByteBuffer            buffer = ByteBuffer.allocate(chunkSize);
        final InputStream           in = new BufferedInputStream(new FileInputStream(f));
        final ChunkedStreamer       streamer = new ChunkedStreamer(chunkSize);
        final GZIPOutputStream      out = new GZIPOutputStream(streamer);
        return new CompressorIterator()
        {
            @Override
            public ByteBuffer next() throws Exception
            {
                while ( streamer.isOpen() )
                {
                    ByteBuffer      pending = streamer.getNextBuffer();
                    if ( pending != null )
                    {
                        return pending;
                    }

                    byte[]          bytes = new byte[chunkSize];
                    int             bytesRead = in.read(bytes);
                    if ( bytesRead >= 0  )
                    {
                        out.write(bytes, 0, bytesRead);
                    }
                    else
                    {
                        out.close();
                    }
                }

                return null;
            }

            @Override
            public void close() throws IOException
            {
                in.close();
                out.close();
            }
        };
    }

    @Override
    public CompressorIterator decompress(InputStream in) throws Exception
    {
        final GZIPInputStream     stream = new GZIPInputStream(in);
        return new CompressorIterator()
        {
            @Override
            public ByteBuffer next() throws Exception
            {
                byte[]          bytes = new byte[chunkSize];
                int             bytesRead = stream.read(bytes);
                if ( bytesRead > 0 )
                {
                    return ByteBuffer.wrap(bytes, 0, bytesRead);
                }
                return null;
            }

            @Override
            public void close() throws IOException
            {
            }
        };
    }
}
