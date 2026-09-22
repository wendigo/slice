/*
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
package io.airlift.slice;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static java.lang.Long.numberOfTrailingZeros;

/**
 * Benchmarks the scan used by {@link Slice#indexOfAnyByte(byte, byte, int)}.
 *
 * <ul>
 *   <li>{@code indexOfAnyByteInline} is the baseline: a local copy of the loop that reads the bytes
 *       through the {@link Slice} instance-field accessors, as {@code indexOfAnyByte} does below
 *       {@code LONG_SCAN_THRESHOLD}.</li>
 *   <li>{@code indexOfAnyByteHelper} is the optimization: the same loop as a static method over the
 *       backing array passed as a parameter, as the shipped {@code scanForAnyByte} does.</li>
 *   <li>{@code indexOfAnyByteDispatched} exercises the shipped {@link Slice#indexOfAnyByte}, which
 *       picks the inline loop below the 4096-byte threshold and the helper at or above it.</li>
 * </ul>
 *
 * <p>Comparing {@code indexOfAnyByteDispatched} against {@code indexOfAnyByteHelper} at or above the
 * threshold measures the helper against itself plus dispatch; use {@code indexOfAnyByteInline} vs
 * {@code indexOfAnyByteHelper} to measure the optimization itself.
 *
 * <p>Worst case: the needle only appears at the very last position, forcing a full scan.
 */
@SuppressWarnings("MethodMayBeStatic")
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 400, timeUnit = TimeUnit.MILLISECONDS)
public class BenchmarkIndexOfAnyByte
{
    private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    @Benchmark
    public int indexOfAnyByteInline(BenchmarkData data)
    {
        return indexOfAnyByteInline(data.slice, data.needle, data.needle2);
    }

    @Benchmark
    public int indexOfAnyByteHelper(BenchmarkData data)
    {
        return indexOfAnyByteHelper(data.bytes, 0, data.bytes.length, data.needle, data.needle2);
    }

    @Benchmark
    public int indexOfAnyByteDispatched(BenchmarkData data)
    {
        return data.slice.indexOfAnyByte(data.needle, data.needle2, 0);
    }

    // Baseline: a local copy of the pre-optimization loop, reading through the Slice accessors.
    static int indexOfAnyByteInline(Slice slice, byte first, byte second)
    {
        int size = slice.length();
        long firstPattern = repeat(first);
        long secondPattern = repeat(second);
        int offset = 0;
        for (; offset < size - 7; offset += 8) {
            long value = slice.getLongUnchecked(offset);
            long matches = match(value, firstPattern) | match(value, secondPattern);
            if (matches != 0) {
                return offset + (numberOfTrailingZeros(matches) >>> 3);
            }
        }

        for (; offset < size; offset++) {
            byte current = slice.getByteUnchecked(offset);
            if (current == first || current == second) {
                return offset;
            }
        }
        return -1;
    }

    // Mirror of Slice.scanForAnyByte: the same loop as a static method over a parameter array.
    static int indexOfAnyByteHelper(byte[] array, int index, int limit, byte first, byte second)
    {
        long firstPattern = repeat(first);
        long secondPattern = repeat(second);
        for (; index <= limit - 8; index += 8) {
            long value = (long) LONG_HANDLE.get(array, index);
            long matches = match(value, firstPattern) | match(value, secondPattern);
            if (matches != 0) {
                return index + (numberOfTrailingZeros(matches) >>> 3);
            }
        }

        for (; index < limit; index++) {
            byte current = array[index];
            if (current == first || current == second) {
                return index;
            }
        }
        return -1;
    }

    private static long repeat(byte b)
    {
        return (b & 0xFFL) * 0x01010101_01010101L;
    }

    private static long match(long value, long pattern)
    {
        long xor = value ^ pattern;
        return (xor - 0x01010101_01010101L) & ~xor & 0x80808080_80808080L;
    }

    @State(Scope.Thread)
    public static class BenchmarkData
    {
        // sizes straddle LONG_SCAN_THRESHOLD (4096) so the dispatch cutoff can be checked
        @Param({"127", "1024", "4095", "4096", "8192", "32779"})
        private int size;

        private byte[] bytes;
        private Slice slice;
        private final byte needle = (byte) 0xAB;
        private final byte needle2 = (byte) 0xCD;

        @Setup(Level.Iteration)
        public void setup()
        {
            bytes = new byte[size];
            // fill with a value that is neither needle; needle appears only at the last position
            Arrays.fill(bytes, (byte) 0x01);
            bytes[size - 1] = needle;
            slice = Slices.wrappedBuffer(bytes);
        }
    }

    static void main()
            throws RunnerException
    {
        Options options = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(".*" + BenchmarkIndexOfAnyByte.class.getSimpleName() + ".*")
                .build();
        new Runner(options).run();
    }
}
