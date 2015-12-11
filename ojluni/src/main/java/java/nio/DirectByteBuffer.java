/*
 * Copyright (c) 2000, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.nio;

import java.io.FileDescriptor;
import sun.misc.Cleaner;
import sun.misc.VM;
import sun.nio.ch.DirectBuffer;
import libcore.io.SizeOf;
import libcore.io.Memory;

class DirectByteBuffer extends MappedByteBuffer
    implements DirectBuffer {

    private boolean isAccessible = true;

    // Base address, used in all indexing calculations
    // NOTE: moved up to Buffer.java for speed in JNI GetDirectBufferAddress
    //    protected long address;

    // An object attached to this buffer. If this buffer is a view of another
    // buffer then we use this field to keep a reference to that buffer to
    // ensure that its memory isn't freed before we are done with it.
    private final Object att;

    public Object attachment() {
        return att;
    }

    private Cleaner cleaner;

    private long actualAddress;

    public Cleaner cleaner() { return cleaner; }

    DirectByteBuffer(int capacity, long address, byte[] hb, int offset) {
        super(-1, 0, capacity, capacity, hb, offset);
        // Only have references to java objects, no need for a cleaner since the GC will do all
        // the work.
        this.address = address + offset;
        this.actualAddress = address;
        cleaner = null;
        this.isReadOnly = false;
        att = null;
    }

    DirectByteBuffer(long addr, int cap, Object ob) {
        super(-1, 0, cap, cap);
        address = addr;
        actualAddress = addr;
        cleaner = null;
        att = ob;
    }

    // Invoked only by JNI: NewDirectByteBuffer(void*, long)
    //
    private DirectByteBuffer(long addr, int cap) {
        super(-1, 0, cap, cap);
        address = addr;
        actualAddress = addr;
        cleaner = null;
        att = null;
    }

    // For memory-mapped buffers -- invoked by FileChannelImpl via reflection
    //
    protected DirectByteBuffer(int cap, long addr,
                               FileDescriptor fd,
                               Runnable unmapper) {
        this(cap, addr, fd, unmapper, false);
    }

    protected DirectByteBuffer(int cap, long addr,
                               FileDescriptor fd,
                               Runnable unmapper,
                               boolean isReadOnly) {
        super(-1, 0, cap, cap, fd);
        this.isReadOnly = isReadOnly;
        address = addr;
        actualAddress = addr;
        cleaner = Cleaner.create(this, unmapper);
        att = null;
    }

    // For duplicates and slices
    //
    DirectByteBuffer(DirectByteBuffer db,         // package-private
                     int mark, int pos, int lim, int cap,
                     int off) {
        this(db, mark, pos, lim, cap, off, false);
    }

    DirectByteBuffer(DirectByteBuffer db,         // package-private
                     int mark, int pos, int lim, int cap,
                     int off, boolean isReadOnly) {
        super(mark, pos, lim, cap, db.hb, off);
        this.isReadOnly = isReadOnly;
        address = db.address;
        actualAddress = db.actualAddress;
        cleaner = null;
        att = db;
    }

    public ByteBuffer slice() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);
        int off = (pos << 0) + offset;
        assert (off >= 0);
        return new DirectByteBuffer(this, -1, 0, rem, rem, off, isReadOnly);
    }

    public ByteBuffer duplicate() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return new DirectByteBuffer(this,
                                    this.markValue(),
                                    this.position(),
                                    this.limit(),
                                    this.capacity(),
                                    offset,
                                    isReadOnly);
    }

    public ByteBuffer asReadOnlyBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return new DirectByteBuffer(this,
                                    this.markValue(),
                                    this.position(),
                                    this.limit(),
                                    this.capacity(),
                                    offset,
                                    true);
    }

    public long address() {
        return address;
    }

    private long ix(int i) {
        return actualAddress + offset + (i << 0);
    }

    private byte get(long a) {
        return Memory.peekByte(a);
    }

    public byte get() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return get(ix(nextGetIndex()));
    }

    public byte get(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return get(ix(checkIndex(i)));
    }

    public ByteBuffer get(byte[] dst, int dstOffset, int length) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        checkBounds(dstOffset, length, dst.length);
        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);
        if (length > rem)
            throw new BufferUnderflowException();
        Memory.peekByteArray(ix(pos),
                             dst, dstOffset, length);
        position = pos + length;
        return this;
    }

    public ByteBuffer put(long a, byte x) {
        Memory.pokeByte(a, x);
        return this;
    }

    public ByteBuffer put(byte x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        put(ix(nextPutIndex()), x);
        return this;
    }

    public ByteBuffer put(int i, byte x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        put(ix(checkIndex(i)), x);
        return this;
    }

    public ByteBuffer put(ByteBuffer src) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        if (src.hb != null) {
            int spos = src.position();
            int slim = src.limit();
            assert (spos <= slim);
            int srem = (spos <= slim ? slim - spos : 0);
            if (src == this)
                throw new IllegalArgumentException();
            put(src.hb, src.offset + spos, srem);
            src.position(spos + srem);
        } else {
            super.put(src);
        }
        return this;
    }

    public ByteBuffer put(byte[] src, int srcOffset, int length) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        checkBounds(srcOffset, length, src.length);
        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);
        if (length > rem)
            throw new BufferOverflowException();
        Memory.pokeByteArray(ix(pos),
                             src, srcOffset, length);
        position = pos + length;
        return this;
    }

    public ByteBuffer compact() {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int pos = position();
        int lim = limit();
        assert (pos <= lim);
        int rem = (pos <= lim ? lim - pos : 0);
        System.arraycopy(hb, position + offset, hb, offset, remaining());
        position(rem);
        limit(capacity());
        discardMark();
        return this;
    }

    public boolean isDirect() {
        return true;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    byte _get(int i) {                          // package-private
        return get(i);
    }

    void _put(int i, byte b) {                  // package-private
        put(i, b);
    }

    private char getChar(long a) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return (char) Memory.peekShort(position, !nativeByteOrder);
    }

    public char getChar() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int newPosition = position + SizeOf.CHAR;
        if (newPosition > limit()) {
            throw new BufferUnderflowException();
        }
        char x = (char) Memory.peekShort(ix(position), !nativeByteOrder);
        position = newPosition;
        return x;
    }

    public char getChar(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        checkIndex(i, SizeOf.CHAR);
        char x = (char)Memory.peekShort(ix(i), !nativeByteOrder);
        return x;
    }

    char getCharUnchecked(int i) {
        return (char)Memory.peekShort(ix(i), !nativeByteOrder);
    }

    void getUnchecked(int pos, char[] dst, int dstOffset, int length) {
        Memory.peekCharArray(ix(pos),
                             dst, dstOffset, length, !nativeByteOrder);
    }


    private ByteBuffer putChar(long a, char x) {
        Memory.pokeShort(a, (short) x, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putChar(char x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putChar(ix(nextPutIndex(SizeOf.CHAR)), x);
        return this;
    }

    public ByteBuffer putChar(int i, char x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putChar(ix(checkIndex(i, SizeOf.CHAR)), x);
        return this;
    }

    void putCharUnchecked(int i, char x) {
        putChar(ix(i), x);
    }

    void putUnchecked(int pos, char[] src, int srcOffset, int length) {
        Memory.pokeCharArray(ix(pos),
                             src, srcOffset, length, !nativeByteOrder);
    }

    public CharBuffer asCharBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);
        int size = rem >> 1;
        return  (CharBuffer)(new ByteBufferAsCharBuffer(this,
                                                        -1,
                                                        0,
                                                        size,
                                                        size,
                                                        off,
                                                        order()));
    }

    private short getShort(long a) {
        return Memory.peekShort(a, !nativeByteOrder);
    }

    public short getShort() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getShort(ix(nextGetIndex(SizeOf.SHORT)));
    }

    public short getShort(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getShort(ix(checkIndex(i, SizeOf.SHORT)));
    }

    short getShortUnchecked(int i) {
        return getShort(ix(i));
    }

    void getUnchecked(int pos, short[] dst, int dstOffset, int length) {
        Memory.peekShortArray(ix(pos),
                              dst, dstOffset, length, !nativeByteOrder);
    }


    private ByteBuffer putShort(long a, short x) {
        Memory.pokeShort(a, x, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putShort(short x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putShort(ix(nextPutIndex(SizeOf.SHORT)), x);
        return this;
    }

    public ByteBuffer putShort(int i, short x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putShort(ix(checkIndex(i, SizeOf.SHORT)), x);
        return this;
    }

    void putShortUnchecked(int i, short x) {
        putShort(ix(i), x);
    }

    void putUnchecked(int pos, short[] src, int srcOffset, int length) {
        Memory.pokeShortArray(ix(pos),
                              src, srcOffset, length, !nativeByteOrder);
    }

    public ShortBuffer asShortBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);
        int size = rem >> 1;
        return (ShortBuffer)(new ByteBufferAsShortBuffer(this,
                                                         -1,
                                                         0,
                                                         size,
                                                         size,
                                                         off,
                                                         order()));
    }

    private int getInt(long a) {
        return  Memory.peekInt(a, !nativeByteOrder);
    }

    public int getInt() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getInt(ix(nextGetIndex(SizeOf.INT)));
    }

    public int getInt(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getInt(ix(checkIndex(i, (SizeOf.INT))));
    }

    int getIntUnchecked(int i) {
        return getInt(ix(i));
    }

    void getUnchecked(int pos, int[] dst, int dstOffset, int length) {
        Memory.peekIntArray(ix(pos),
                            dst, dstOffset, length, !nativeByteOrder);
    }

    private ByteBuffer putInt(long a, int x) {
        Memory.pokeInt(a, x, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putInt(int x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putInt(ix(nextPutIndex(SizeOf.INT)), x);
        return this;
    }

    public ByteBuffer putInt(int i, int x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putInt(ix(checkIndex(i, SizeOf.INT)), x);
        return this;
    }

    void putIntUnchecked(int i, int x) {
        putInt(ix(i), x);
    }

    void putUnchecked(int pos, int[] src, int srcOffset, int length) {
        Memory.pokeIntArray(ix(pos),
                            src, srcOffset, length, !nativeByteOrder);
    }


    public IntBuffer asIntBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);
        int size = rem >> 2;
        return (IntBuffer)(new ByteBufferAsIntBuffer(this,
                                                     -1,
                                                     0,
                                                     size,
                                                     size,
                                                     off,
                                                     order()));
    }

    private long getLong(long a) {
        return Memory.peekLong(a, !nativeByteOrder);
    }

    public long getLong() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getLong(ix(nextGetIndex(SizeOf.LONG)));
    }

    public long getLong(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getLong(ix(checkIndex(i, SizeOf.LONG)));
    }

    long getLongUnchecked(int i) {
        return getLong(ix(i));
    }

    void getUnchecked(int pos, long[] dst, int dstOffset, int length) {
        Memory.peekLongArray(ix(pos),
                             dst, dstOffset, length, !nativeByteOrder);
    }

    private ByteBuffer putLong(long a, long x) {
        Memory.pokeLong(a, x, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putLong(long x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putLong(ix(nextPutIndex(SizeOf.LONG)), x);
        return this;
    }

    public ByteBuffer putLong(int i, long x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putLong(ix(checkIndex(i, SizeOf.LONG)), x);
        return this;
    }

    void putLongUnchecked(int i, long x) {
        putLong(ix(i), x);
    }

    void putUnchecked(int pos, long[] src, int srcOffset, int length) {
        Memory.pokeLongArray(ix(pos),
                             src, srcOffset, length, !nativeByteOrder);
    }


    public LongBuffer asLongBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);
        int size = rem >> 3;
        return (LongBuffer)(new ByteBufferAsLongBuffer(this,
                                                       -1,
                                                       0,
                                                       size,
                                                       size,
                                                       off,
                                                       order()));
    }

    private float getFloat(long a) {
        int x = Memory.peekInt(a, !nativeByteOrder);
        return Float.intBitsToFloat(x);
    }

    public float getFloat() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getFloat(ix(nextGetIndex(SizeOf.FLOAT)));
    }

    public float getFloat(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getFloat(ix(checkIndex(i, SizeOf.FLOAT)));
    }

    float getFloatUnchecked(int i) {
        return getFloat(ix(i));
    }

    void getUnchecked(int pos, float[] dst, int dstOffset, int length) {
        Memory.peekFloatArray(ix(pos),
                              dst, dstOffset, length, !nativeByteOrder);
    }

    private ByteBuffer putFloat(long a, float x) {
        int y = Float.floatToRawIntBits(x);
        Memory.pokeInt(a, y, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putFloat(float x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putFloat(ix(nextPutIndex(SizeOf.FLOAT)), x);
        return this;
    }

    public ByteBuffer putFloat(int i, float x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putFloat(ix(checkIndex(i, SizeOf.FLOAT)), x);
        return this;
    }

    void putFloatUnchecked(int i, float x) {
        putFloat(ix(i), x);
    }

    void putUnchecked(int pos, float[] src, int srcOffset, int length) {
        Memory.pokeFloatArray(ix(pos),
                              src, srcOffset, length, !nativeByteOrder);
    }

    public FloatBuffer asFloatBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);
        int size = rem >> 2;
        return (FloatBuffer)(new ByteBufferAsFloatBuffer(this,
                                                         -1,
                                                         0,
                                                         size,
                                                         size,
                                                         off,
                                                         order()));
    }

    private double getDouble(long a) {
        long x = Memory.peekLong(a, !nativeByteOrder);
        return Double.longBitsToDouble(x);
    }

    public double getDouble() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getDouble(ix(nextGetIndex(SizeOf.DOUBLE)));
    }

    public double getDouble(int i) {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        return getDouble(ix(checkIndex(i, SizeOf.DOUBLE)));
    }

    double getDoubleUnchecked(int i) {
        return getDouble(ix(i));
    }

    void getUnchecked(int pos, double[] dst, int dstOffset, int length) {
        Memory.peekDoubleArray(ix(pos),
                               dst, dstOffset, length, !nativeByteOrder);
    }

    private ByteBuffer putDouble(long a, double x) {
        long y = Double.doubleToRawLongBits(x);
        Memory.pokeLong(a, y, !nativeByteOrder);
        return this;
    }

    public ByteBuffer putDouble(double x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putDouble(ix(nextPutIndex(SizeOf.DOUBLE)), x);
        return this;
    }

    public ByteBuffer putDouble(int i, double x) {
        if (isReadOnly) {
            throw new ReadOnlyBufferException();
        }
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        putDouble(ix(checkIndex(i, SizeOf.DOUBLE)), x);
        return this;
    }

    void putDoubleUnchecked(int i, double x) {
        putDouble(ix(i), x);
    }

    void putUnchecked(int pos, double[] src, int srcOffset, int length) {
        Memory.pokeDoubleArray(ix(pos),
                               src, srcOffset, length, !nativeByteOrder);
    }

    public DoubleBuffer asDoubleBuffer() {
        if (!isAccessible) {
            throw new IllegalStateException("buffer is inaccessible");
        }
        int off = this.position();
        int lim = this.limit();
        assert (off <= lim);
        int rem = (off <= lim ? lim - off : 0);

        int size = rem >> 3;
        return (DoubleBuffer)(new ByteBufferAsDoubleBuffer(this,
                                                           -1,
                                                           0,
                                                           size,
                                                           size,
                                                           off,
                                                           order()));
    }

    public boolean isAccessible() {
        return isAccessible;
    }

    public void setAccessible(boolean value) {
        isAccessible = value;
    }
}
