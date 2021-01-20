/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.cache.decorators;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;

import org.apache.ibatis.cache.Cache;

/**
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 * SoftCache 中缓存项的 value 是 SoftEntry 对象（见 putObject() 方法），SoftEntry 继承了
 * SoftReference 其中指向 key 的引用是强引用，而指向 value 的引用是软引用
 *
 * @author Clinton Begin
 */
public class SoftCache implements Cache {

    /**
     * 在 SoftCache 中，最近使用的一部分缓存项不会被 GC 回收，这就是通过将其 value 添加到
     * hardLinksToAvoidGarbageCollection 集合中实现的（即有强引用指向其 value）<br />
     * hardLinksToAvoidGarbageCollection 集合是 LinkedList 类型
     */
    private final Deque<Object> hardLinksToAvoidGarbageCollection;
    /**
     * ReferenceQueue，引用队列，用于记录已经被 GC 回收的缓存项所对应的 SoftEntry 对象
     */
    private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
    private final Cache delegate;
    /**
     * 强连接的个数，默认值是 256
     */
    private int numberOfHardLinks;

    public SoftCache(Cache delegate) {
        this.delegate = delegate;
        this.numberOfHardLinks = 256;
        this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
        this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        removeGarbageCollectedItems();
        return delegate.getSize();
    }

    public void setSize(int size) {
        this.numberOfHardLinks = size;
    }

    @Override
    public void putObject(Object key, Object value) {
        removeGarbageCollectedItems();
        delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
    }

    @Override
    public Object getObject(Object key) {
        Object result = null;
        @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
        SoftReference<Object> softReference = (SoftReference<Object>)delegate.getObject(key);
        if (softReference != null) {
            result = softReference.get();
            if (result == null) {//说明已经被 GC 回收，将其从缓存中移除
                delegate.removeObject(key);
            } else {//未被 GC 回收
                // See #586 (and #335) modifications need more than a read lock
                synchronized (hardLinksToAvoidGarbageCollection) {
                    //缓存项的 value 添加到 hardLinksToAvoidGarbageCollection 集合中保存
                    hardLinksToAvoidGarbageCollection.addFirst(result);

                    //超过 numberOfHardLinks，则将最老的缓存项从 hardLinksToAvoidGarbageCollection
                    //集合中移除，有点类似于先进先出队列
                    if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
                        hardLinksToAvoidGarbageCollection.removeLast();
                    }
                }
            }
        }
        return result;
    }

    @Override
    public Object removeObject(Object key) {
        removeGarbageCollectedItems();
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        synchronized (hardLinksToAvoidGarbageCollection) {
            hardLinksToAvoidGarbageCollection.clear();
        }
        removeGarbageCollectedItems();
        delegate.clear();
    }

    /**
     * 清除已经被 GC 回收的缓存项
     */
    private void removeGarbageCollectedItems() {
        SoftEntry sv;
        //遍历被 GC 回收的缓存项，并将其从缓存中清除
        while ((sv = (SoftEntry)queueOfGarbageCollectedEntries.poll()) != null) {
            delegate.removeObject(sv.key);
        }
    }

    private static class SoftEntry extends SoftReference<Object> {
        private final Object key;

        SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
            //指向 value 的引用是软引用，且关联了引用队列
            super(value, garbageCollectionQueue);
            this.key = key;//强引用
        }
    }

}
