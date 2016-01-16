/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import static org.redisson.client.protocol.RedisCommands.EVAL_OBJECT;
import static org.redisson.client.protocol.RedisCommands.LINDEX;
import static org.redisson.client.protocol.RedisCommands.LPOP;
import static org.redisson.client.protocol.RedisCommands.LPUSH_BOOLEAN;
import static org.redisson.client.protocol.RedisCommands.LRANGE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommand.ValueType;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.client.protocol.convertor.BooleanNumberReplayConvertor;
import org.redisson.client.protocol.convertor.BooleanReplayConvertor;
import org.redisson.client.protocol.convertor.Convertor;
import org.redisson.client.protocol.convertor.IntegerReplayConvertor;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.core.RList;

import io.netty.util.concurrent.Future;

/**
 * Distributed and concurrent implementation of {@link java.util.List}
 *
 * @author Nikita Koksharov
 *
 * @param <V> the type of elements held in this collection
 */
public class RedissonSubList<V> extends RedissonExpirable implements RList<V> {

    public static final RedisCommand<Boolean> EVAL_BOOLEAN_ARGS2 = new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 5, ValueType.OBJECTS);

    final int fromIndex;
    AtomicInteger toIndex = new AtomicInteger();
    int size = -1;

    protected RedissonSubList(Codec codec, CommandAsyncExecutor commandExecutor, String name, int fromIndex, int toIndex) {
        super(codec, commandExecutor, name);
        this.fromIndex = fromIndex;
        this.toIndex.set(toIndex);
    }

    @Override
    public int size() {
        return get(sizeAsync());
    }

    public Future<Integer> sizeAsync() {
        if (size != -1) {
            return newSucceededFuture(size);
        }
        return commandExecutor.readAsync(getName(), codec, new RedisStrictCommand<Integer>("LLEN", new IntegerReplayConvertor() {
            @Override
            public Integer convert(Object obj) {
                int size = ((Long) obj).intValue();
                int subListLen = Math.min(size, toIndex.get()) - fromIndex;
                return Math.max(subListLen, 0);
            }
        }), getName());
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        return get(containsAsync(o));
    }

    @Override
    public Iterator<V> iterator() {
        return listIterator();
    }

    @Override
    public Object[] toArray() {
        List<V> list = readAll();
        return list.toArray();
    }

    private List<V> readAll() {
        return (List<V>) get(readAllAsync());
    }

    private Future<Collection<V>> readAllAsync() {
        return commandExecutor.readAsync(getName(), codec, LRANGE, getName(), fromIndex, toIndex.get()-1);
    }

    @Override
    public <T> T[] toArray(T[] a) {
        List<V> list = readAll();
        return list.toArray(a);
    }

    @Override
    public boolean add(V e) {
        return get(addAsync(e));
    }

    @Override
    public Future<Boolean> addAsync(V e) {
        return addAllAsync(toIndex.get() - fromIndex, Collections.singleton(e));
    }

    @Override
    public boolean remove(Object o) {
        return get(removeAsync(o));
    }

    @Override
    public Future<Boolean> removeAsync(Object o) {
        return removeAllAsync(Collections.singleton(o), 1);
    }

    @Override
    public Future<Boolean> containsAllAsync(Collection<?> c) {
        List<Object> params = new ArrayList<Object>();
        params.add(fromIndex);
        params.add(toIndex.get() - 1);
        params.addAll(c);
        return commandExecutor.evalReadAsync(getName(), codec, new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 6, ValueType.OBJECTS),
                "local fromIndex = table.remove(ARGV, 1);" +
                "local toIndex = table.remove(ARGV, 2);" +
                "local items = redis.call('lrange', KEYS[1], tonumber(fromIndex), tonumber(toIndex)) " +
                "for i=1, #items do " +
                    "for j = 0, #ARGV, 1 do " +
                        "if items[i] == ARGV[j] then " +
                            "table.remove(ARGV, j) " +
                        "end " +
                    "end " +
                "end " +
                "return table.getn(ARGV) == 0 and 1 or 0",
                Collections.<Object>singletonList(getName()), params.toArray());
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return get(containsAllAsync(c));
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        return get(addAllAsync(c));
    }

    @Override
    public Future<Boolean> addAllAsync(Collection<? extends V> c) {
        if (c.isEmpty()) {
            return newSucceededFuture(false);
        }

        return addAllAsync(toIndex.get() - fromIndex, c);
    }

    public Future<Boolean> addAllAsync(int index, Collection<? extends V> coll) {
        checkIndex(index);

        if (coll.isEmpty()) {
            return newSucceededFuture(false);
        }

        if (index == 0) { // prepend elements to list
            List<Object> elements = new ArrayList<Object>(coll);
            Collections.reverse(elements);
            elements.add(0, getName());

            return commandExecutor.writeAsync(getName(), codec, LPUSH_BOOLEAN, elements.toArray());
        }

        List<Object> args = new ArrayList<Object>(coll.size() + 1);
        args.add(index);
        args.addAll(coll);
        return commandExecutor.evalWriteAsync(getName(), codec, EVAL_BOOLEAN_ARGS2,
                "local ind = table.remove(ARGV, 1); " + // index is the first parameter
                        "local size = redis.call('llen', KEYS[1]); " +
                        "assert(tonumber(ind) <= size, 'index: ' .. ind .. ' but current size: ' .. size); " +
                        "local tail = redis.call('lrange', KEYS[1], ind, -1); " +
                        "redis.call('ltrim', KEYS[1], 0, ind - 1); " +
                        "for i=1, #ARGV, 5000 do "
                          + "redis.call('rpush', KEYS[1], unpack(ARGV, i, math.min(i+4999, #ARGV))); "
                      + "end; " +
                        "if #tail > 0 then " +
                            "for i=1, #tail, 5000 do "
                                + "redis.call('rpush', KEYS[1], unpack(tail, i, math.min(i+4999, #tail))); "
                          + "end "
                      + "end;" +
                        "return 1;",
                Collections.<Object>singletonList(getName()), args.toArray());
    }

    @Override
    public boolean addAll(int index, Collection<? extends V> coll) {
        return get(addAllAsync(index, coll));
    }

    @Override
    public Future<Boolean> removeAllAsync(Collection<?> c) {
        return removeAllAsync(c, 0);
    }

    private Future<Boolean> removeAllAsync(Collection<?> c, int count) {
        List<Object> params = new ArrayList<Object>();
        params.add(fromIndex);
        params.add(toIndex.get() - 1);
        params.add(count);
        params.addAll(c);

        return commandExecutor.evalWriteAsync(getName(), codec, new RedisCommand<Boolean>("EVAL", new BooleanReplayConvertor(), 7, ValueType.OBJECTS),
                "local v = 0; " +
                "local fromIndex = table.remove(ARGV, 1);" +
                "local toIndex = table.remove(ARGV, 2);" +
                "local count = table.remove(ARGV, 3);" +
                "local items = redis.call('lrange', KEYS[1], fromIndex, toIndex); " +

                "for i=1, #items do " +
                    "for j = 0, #ARGV, 1 do " +
                        "if items[i] == ARGV[j] then " +
                            "redis.call('lrem', KEYS[1], count, ARGV[i]); " +
                            "v = 1; " +
                        "end; " +
                    "end; " +
                "end; " +
                "return v; ",
                Collections.<Object>singletonList(getName()), params.toArray());
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return get(removeAllAsync(c));
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return get(retainAllAsync(c));
    }

    @Override
    public Future<Boolean> retainAllAsync(Collection<?> c) {
        List<Object> params = new ArrayList<Object>();
        params.add(fromIndex);
        params.add(toIndex.get() - 1);
        params.addAll(c);

        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN_WITH_VALUES,
                "local changed = 0 " +
                "local fromIndex = table.remove(ARGV, 1);" +
                "local toIndex = table.remove(ARGV, 2);" +
                "local items = redis.call('lrange', KEYS[1], fromIndex, toIndex) "
                   + "local i = 1 "
                   + "local s = table.getn(items) "
                   + "while i <= s do "
                        + "local element = items[i] "
                        + "local isInAgrs = false "
                        + "for j = 0, table.getn(ARGV), 1 do "
                            + "if ARGV[j] == element then "
                                + "isInAgrs = true "
                                + "break "
                            + "end "
                        + "end "
                        + "if isInAgrs == false then "
                            + "redis.call('LREM', KEYS[1], 0, element) "
                            + "changed = 1 "
                        + "end "
                        + "i = i + 1 "
                   + "end "
                   + "return changed ",
                Collections.<Object>singletonList(getName()), params.toArray());
    }


    @Override
    public void clear() {
        if (fromIndex == 0) {
            get(commandExecutor.writeAsync(getName(), codec, RedisCommands.LTRIM, getName(), toIndex, -1));
            size = 0;
            return;
        }

        get(commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_VOID,
                        "local tail = redis.call('lrange', KEYS[1], ARGV[2], -1); " +
                        "redis.call('ltrim', KEYS[1], 0, ARGV[1] - 1); " +
                        "if #tail > 0 then " +
                            "for i=1, #tail, 5000 do "
                                + "redis.call('rpush', KEYS[1], unpack(tail, i, math.min(i+4999, #tail))); "
                          + "end "
                      + "end;",
                Collections.<Object>singletonList(getName()), fromIndex, toIndex));
        size = 0;
    }

    @Override
    public Future<V> getAsync(int index) {
        checkIndex(index);
        return commandExecutor.readAsync(getName(), codec, LINDEX, getName(), index);
    }

    @Override
    public V get(int index) {
        return getValue(index);
    }

    V getValue(int index) {
        return get(getAsync(index));
    }

    private void checkIndex(int index) {
        if (!(index >= fromIndex && index < toIndex.get())) {
            throw new IndexOutOfBoundsException("index: " + index + " but current fromIndex: "+ fromIndex + " toIndex: " + toIndex);
        }
    }

    @Override
    public V set(int index, V element) {
        return get(setAsync(index, element));
    }

    @Override
    public Future<V> setAsync(int index, V element) {
        checkIndex(index);
        return commandExecutor.evalWriteAsync(getName(), codec, new RedisCommand<Object>("EVAL", 5),
                "local v = redis.call('lindex', KEYS[1], ARGV[1]); " +
                        "redis.call('lset', KEYS[1], ARGV[1], ARGV[2]); " +
                        "return v",
                Collections.<Object>singletonList(getName()), index, element);
    }

    @Override
    public void fastSet(int index, V element) {
        get(fastSetAsync(index, element));
    }

    @Override
    public Future<Void> fastSetAsync(int index, V element) {
        checkIndex(index);
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.LSET, getName(), index, element);
    }

    @Override
    public void add(int index, V element) {
        addAll(index, Collections.singleton(element));
    }

    @Override
    public V remove(int index) {
        checkIndex(index);

        V v = removeInner(index);
        toIndex.decrementAndGet();
        return v;
    }

    private V removeInner(int index) {
        if (index == 0) {
            Future<V> f = commandExecutor.writeAsync(getName(), codec, LPOP, getName());
            return get(f);
        }

        Future<V> f = commandExecutor.evalWriteAsync(getName(), codec, EVAL_OBJECT,
                "local v = redis.call('lindex', KEYS[1], ARGV[1]); " +
                        "local tail = redis.call('lrange', KEYS[1], ARGV[1] + 1, -1);" +
                        "redis.call('ltrim', KEYS[1], 0, ARGV[1] - 1);" +
                        "if #tail > 0 then " +
                            "for i=1, #tail, 5000 do "
                                + "redis.call('rpush', KEYS[1], unpack(tail, i, math.min(i+4999, #tail))); "
                          + "end "
                      + "end;" +
                        "return v",
                Collections.<Object>singletonList(getName()), fromIndex + index);
        return get(f);
    }

    @Override
    public int indexOf(Object o) {
        return get(indexOfAsync(o));
    }

    @Override
    public Future<Boolean> containsAsync(Object o) {
        return indexOfAsync(o, new BooleanNumberReplayConvertor(-1L));
    }

    private <R> Future<R> indexOfAsync(Object o, Convertor<R> convertor) {
        return commandExecutor.evalReadAsync(getName(), codec, new RedisCommand<R>("EVAL", convertor, 4),
                "local items = redis.call('lrange', KEYS[1], tonumber(ARGV[2]), tonumber(ARGV[3])) " +
                "for i=1,#items do " +
                    "if items[i] == ARGV[1] then " +
                        "return tonumber(ARGV[2]) + i - 1; " +
                    "end; " +
                "end; " +
                "return -1; ",
                Collections.<Object>singletonList(getName()), o, fromIndex, toIndex.get()-1);
    }

    @Override
    public Future<Integer> indexOfAsync(Object o) {
        return indexOfAsync(o, new IntegerReplayConvertor());
    }

    @Override
    public int lastIndexOf(Object o) {
        return get(lastIndexOfAsync(o));
    }

    @Override
    public Future<Integer> lastIndexOfAsync(Object o) {
        return commandExecutor.evalReadAsync(getName(), codec, new RedisCommand<Integer>("EVAL", new IntegerReplayConvertor(), 4),
                "local key = KEYS[1] " +
                "local obj = ARGV[1] " +
                "local fromIndex = table.remove(ARGV, 1);" +
                "local toIndex = table.remove(ARGV, 2);" +
                "local items = redis.call('lrange', key, tonumber(fromIndex), tonumber(toIndexs)) " +
                "for i = #items, 0, -1 do " +
                    "if items[i] == obj then " +
                        "return tonumber(ARGV[2]) + i - 1 " +
                    "end; " +
                "end; " +
                "return -1; ",
                Collections.<Object>singletonList(getName()), o, fromIndex, toIndex.get()-1);
    }

    @Override
    public ListIterator<V> listIterator() {
        return listIterator(fromIndex);
    }

    @Override
    public ListIterator<V> listIterator(final int fromIndex) {
        checkIndex(fromIndex);
        return new ListIterator<V>() {

            private V prevCurrentValue;
            private V nextCurrentValue;
            private V currentValueHasRead;
            private int currentIndex = fromIndex - 1;
            private boolean hasBeenModified = true;

            @Override
            public boolean hasNext() {
                if (currentIndex == toIndex.get()-1) {
                    return false;
                }
                V val = RedissonSubList.this.getValue(currentIndex+1);
                if (val != null) {
                    nextCurrentValue = val;
                }
                return val != null;
            }

            @Override
            public V next() {
                if (nextCurrentValue == null && !hasNext()) {
                    throw new NoSuchElementException("No such element at index " + currentIndex);
                }
                currentIndex++;
                currentValueHasRead = nextCurrentValue;
                nextCurrentValue = null;
                hasBeenModified = false;
                return currentValueHasRead;
            }

            @Override
            public void remove() {
                if (currentValueHasRead == null) {
                    throw new IllegalStateException("Neither next nor previous have been called");
                }
                if (hasBeenModified) {
                    throw new IllegalStateException("Element been already deleted");
                }
                RedissonSubList.this.removeInner(currentIndex - fromIndex);
                toIndex.decrementAndGet();
                hasBeenModified = true;
                currentValueHasRead = null;
            }

            @Override
            public boolean hasPrevious() {
                if (currentIndex <= fromIndex - 1) {
                    return false;
                }
                V val = RedissonSubList.this.getValue(currentIndex);
                if (val != null) {
                    prevCurrentValue = val;
                }
                return val != null;
            }

            @Override
            public V previous() {
                if (prevCurrentValue == null && !hasPrevious()) {
                    throw new NoSuchElementException("No such element at index " + currentIndex);
                }
                currentIndex--;
                hasBeenModified = false;
                currentValueHasRead = prevCurrentValue;
                prevCurrentValue = null;
                return currentValueHasRead;
            }

            @Override
            public int nextIndex() {
                return currentIndex + 1;
            }

            @Override
            public int previousIndex() {
                return currentIndex;
            }

            @Override
            public void set(V e) {
                if (hasBeenModified) {
                    throw new IllegalStateException();
                }
                RedissonSubList.this.set(currentIndex, e);
            }

            @Override
            public void add(V e) {
                RedissonSubList.this.add(currentIndex+1, e);
                currentIndex++;
                hasBeenModified = true;
            }
        };
    }

    @Override
    public RList<V> subList(int fromIndex, int toIndex) {
        if (fromIndex < this.fromIndex || toIndex >= this.toIndex.get()) {
            throw new IndexOutOfBoundsException("fromIndex: " + fromIndex + " toIndex: " + toIndex);
        }
        if (fromIndex > toIndex) {
            throw new IllegalArgumentException("fromIndex: " + fromIndex + " toIndex: " + toIndex);
        }

        return new RedissonSubList<V>(codec, commandExecutor, getName(), fromIndex, toIndex);
    }

    public String toString() {
        Iterator<V> it = iterator();
        if (! it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (;;) {
            V e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (! it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof List))
            return false;

        Iterator<V> e1 = iterator();
        Iterator<?> e2 = ((List<?>) o).iterator();
        while (e1.hasNext() && e2.hasNext()) {
            V o1 = e1.next();
            Object o2 = e2.next();
            if (!(o1==null ? o2==null : o1.equals(o2)))
                return false;
        }
        return !(e1.hasNext() || e2.hasNext());
    }

    @Override
    public int hashCode() {
        int hashCode = 1;
        for (V e : this) {
            hashCode = 31*hashCode + (e==null ? 0 : e.hashCode());
        }
        return hashCode;
    }

}
