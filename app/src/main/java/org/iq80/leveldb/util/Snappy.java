/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.util;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Upstream dain/leveldb ships two Snappy backends here (xerial JNI and a pure-Java
 * iq80 implementation), each requiring an external dependency we don't include.
 * Bedrock Edition's own LevelDB fork never actually produces Snappy-compressed
 * blocks (it uses zlib — see table/FileChannelTable.java), so this trimmed copy
 * just fails loudly if that path is ever hit instead of pulling in unused libraries.
 */
public final class Snappy
{
    private Snappy()
    {
    }

    public interface SPI
    {
        int uncompress(ByteBuffer compressed, ByteBuffer uncompressed)
                throws IOException;

        int uncompress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
                throws IOException;

        int compress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
                throws IOException;

        byte[] compress(String text)
                throws IOException;

        int maxCompressedLength(int length);
    }

    public static boolean available()
    {
        return false;
    }

    public static void uncompress(ByteBuffer compressed, ByteBuffer uncompressed)
            throws IOException
    {
        throw new IOException("Snappy support was stripped from this vendored build — Bedrock worlds use zlib, not Snappy");
    }

    public static void uncompress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
            throws IOException
    {
        throw new IOException("Snappy support was stripped from this vendored build — Bedrock worlds use zlib, not Snappy");
    }

    public static int compress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset)
            throws IOException
    {
        throw new IOException("Snappy support was stripped from this vendored build — Bedrock worlds use zlib, not Snappy");
    }

    public static byte[] compress(String text)
            throws IOException
    {
        throw new IOException("Snappy support was stripped from this vendored build — Bedrock worlds use zlib, not Snappy");
    }

    public static int maxCompressedLength(int length)
    {
        return 0;
    }
}
