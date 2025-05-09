/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.compress;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;

/**
 * Similar class to the {@link String} class except that it internally stores
 * data using a compressed representation in order to require less permanent
 * memory. Note that the compressed string might still sometimes need to be
 * decompressed in order to perform equality checks or to compute hash codes.
 */
public final class CompressedXContent {

    private static int crc32(BytesReference data) {
        CRC32 crc32 = new CRC32();
        try {
            data.writeTo(new CheckedOutputStream(Streams.NULL_OUTPUT_STREAM, crc32));
        } catch (IOException bogus) {
            // cannot happen
            throw new Error(bogus);
        }
        return (int) crc32.getValue();
    }

    private final byte[] bytes;
    private final int crc32;

    // Used for serialization
    private CompressedXContent(byte[] compressed, int crc32) {
        this.bytes = compressed;
        this.crc32 = crc32;
        assertConsistent();
    }

    /**
     * Create a {@link CompressedXContent} out of a mapping
     */
    public CompressedXContent(Map<String, Object> mapping) {
        try {
            BytesStreamOutput bStream = new BytesStreamOutput();
            OutputStream compressedStream = CompressorFactory.COMPRESSOR.threadLocalOutputStream(bStream);
            CRC32 crc32 = new CRC32();
            try (OutputStream checkedStream = new CheckedOutputStream(compressedStream, crc32)) {
                try (XContentBuilder builder = XContentFactory.builder(XContentType.JSON, checkedStream)) {
                    builder.startObject();
                    builder.mapContents(mapping);
                    builder.endObject();
                }
            }
            this.bytes = BytesReference.toBytes(bStream.bytes());
            this.crc32 = (int) crc32.getValue();
            assertConsistent();
        } catch (IOException e) {
            // We are operating on a BytesOutputStream so this should never happen
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Create a {@link CompressedXContent} out of serialized content
     * that may already be compressed.
     */
    public CompressedXContent(BytesReference data) {
        Compressor compressor = CompressorFactory.compressor(data);
        if (compressor != null) {
            // already compressed...
            this.bytes = BytesReference.toBytes(data);
            this.crc32 = crc32(uncompressed());
        } else {
            this.bytes = BytesReference.toBytes(CompressorFactory.COMPRESSOR.compress(data));
            this.crc32 = crc32(data);
        }
        assertConsistent();
    }

    private void assertConsistent() {
        assert CompressorFactory.compressor(new BytesArray(bytes)) != null;
        assert this.crc32 == crc32(uncompressed());
    }

    public CompressedXContent(byte[] data) {
        this(new BytesArray(data));
    }

    public CompressedXContent(String str) {
        this(new BytesArray(str.getBytes(StandardCharsets.UTF_8)));
    }

    /** Return the compressed bytes. */
    public byte[] compressed() {
        return this.bytes;
    }

    /** Return the compressed bytes as a {@link BytesReference}. */
    public BytesReference compressedReference() {
        return new BytesArray(bytes);
    }

    /** Return the uncompressed bytes. */
    public BytesReference uncompressed() {
        return CompressorFactory.uncompress(new BytesArray(bytes));
    }

    public String string() {
        return uncompressed().utf8ToString();
    }

    public static CompressedXContent readCompressedString(StreamInput in) throws IOException {
        int crc32 = in.readInt();
        return new CompressedXContent(in.readByteArray(), crc32);
    }

    public void writeTo(StreamOutput out) throws IOException {
        out.writeInt(crc32);
        out.writeByteArray(bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CompressedXContent that = (CompressedXContent) o;

        if (Arrays.equals(compressed(), that.compressed())) {
            return true;
        }

        if (crc32 != that.crc32) {
            return false;
        }

        return uncompressed().equals(that.uncompressed());
    }

    @Override
    public int hashCode() {
        return crc32;
    }

    @Override
    public String toString() {
        return string();
    }
}
